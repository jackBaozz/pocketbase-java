package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePermissionSupportTest {

  @TempDir
  Path tempDir;

  @Test
  void createsPrivateFilesAndRejectsSymlinkTargets() throws Exception {
    Assumptions.assumeTrue(
        Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null);

    Path file = tempDir.resolve("secret");
    FilePermissionSupport.createPrivateFile(file);
    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(file));

    Path target = tempDir.resolve("target");
    Files.writeString(target, "safe");
    Path link = tempDir.resolve("link");
    try {
      Files.createSymbolicLink(link, target.getFileName());
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.assumeTrue(false, "symbolic links are not available");
    }
    assertThrows(IOException.class, () -> FilePermissionSupport.createPrivateFile(link));

    Path brokenLink = tempDir.resolve("broken-link");
    Files.createSymbolicLink(brokenLink, tempDir.resolve("missing-target").getFileName());
    assertThrows(IOException.class, () -> FilePermissionSupport.secureFile(brokenLink));
    assertThrows(IOException.class, () -> FilePermissionSupport.secureTree(tempDir));
  }
}
