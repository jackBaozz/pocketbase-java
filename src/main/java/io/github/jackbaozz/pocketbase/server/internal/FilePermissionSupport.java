package io.github.jackbaozz.pocketbase.server.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Stream;

/** Applies private filesystem permissions to PocketBase runtime data where the platform supports it. */
public final class FilePermissionSupport {
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
      Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);
  private static final Set<PosixFilePermission> PRIVATE_FILE =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private FilePermissionSupport() {
  }

  public static void secureDirectory(Path directory) throws IOException {
    if (directory != null && Files.isSymbolicLink(directory)) {
      throw new IOException(
          "runtime directory must not be a symbolic link: " + directory.getFileName());
    }
    Files.createDirectories(directory);
    setPermissions(directory, PRIVATE_DIRECTORY);
  }

  public static void secureFile(Path file) throws IOException {
    if (file == null) {
      return;
    }
    if (Files.isSymbolicLink(file)) {
      throw new IOException("runtime file must not be a symbolic link: " + file.getFileName());
    }
    if (!Files.exists(file)) {
      return;
    }
    setPermissions(file, PRIVATE_FILE);
  }

  /** Secure an existing runtime tree, including SQLite sidecar files and uploaded content. */
  public static void secureTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.forEach(
          path -> {
            try {
              // Never chmod through or operate with an attacker-controlled symlink in the
              // runtime tree. Refuse startup/operation instead of silently leaving it active.
              if (Files.isSymbolicLink(path)) {
                throw new IOException(
                    "runtime tree must not contain a symbolic link: " + path.getFileName());
              }
              setPermissions(path, Files.isDirectory(path) ? PRIVATE_DIRECTORY : PRIVATE_FILE);
            } catch (IOException e) {
              throw new PermissionRuntimeException(e);
            }
          });
    } catch (PermissionRuntimeException e) {
      throw e.ioException;
    }
  }

  /** Create a new private file before writing its contents where POSIX attributes are available. */
  public static void createPrivateFile(Path file) throws IOException {
    if (file == null) {
      throw new IllegalArgumentException("file must not be null");
    }
    if (Files.isSymbolicLink(file)) {
      throw new IOException("runtime file must not be a symbolic link: " + file.getFileName());
    }
    secureDirectory(file.toAbsolutePath().getParent());
    FileAttribute<Set<PosixFilePermission>> attributes =
        PosixFilePermissions.asFileAttribute(PRIVATE_FILE);
    try {
      Files.createFile(file, attributes);
    } catch (UnsupportedOperationException e) {
      Files.createFile(file);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      if (Files.isSymbolicLink(file)) {
        throw new IOException("runtime file must not be a symbolic link: " + file.getFileName(), e);
      }
      secureFile(file);
    }
  }

  /** Secure the SQLite database and files created by WAL mode. */
  public static void secureSqliteFiles(Path dataDir) throws IOException {
    if (dataDir == null) {
      return;
    }
    secureFile(dataDir.resolve("pocketbase.db"));
    secureFile(dataDir.resolve("pocketbase.db-journal"));
    secureFile(dataDir.resolve("pocketbase.db-wal"));
    secureFile(dataDir.resolve("pocketbase.db-shm"));
  }

  private static void setPermissions(Path path, Set<PosixFilePermission> permissions)
      throws IOException {
    PosixFileAttributeView view =
        Files.getFileAttributeView(path, PosixFileAttributeView.class);
    if (view != null) {
      view.setPermissions(permissions);
    }
  }

  private static final class PermissionRuntimeException extends RuntimeException {
    private final IOException ioException;

    private PermissionRuntimeException(IOException ioException) {
      this.ioException = ioException;
    }
  }
}
