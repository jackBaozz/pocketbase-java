package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PocketBaseServerProcessTest {

  @TempDir
  Path tempDir;

  private String getJavaExecutable() {
    return ProcessHandle.current().info().command().orElse("java");
  }

  private String getClasspath() {
    return System.getProperty("java.class.path");
  }

  @Test
  void testUnknownArgumentExitsNonZero() throws Exception {
    ProcessBuilder pb = new ProcessBuilder(
        getJavaExecutable(),
        "-cp",
        getClasspath(),
        "io.github.jackbaozz.pocketbase.server.PocketBaseServer",
        "--unrecognized-flag-xyz"
    );
    Process process = pb.start();
    boolean finished = process.waitFor(10, TimeUnit.SECONDS);
    assertTrue(finished, "Process should terminate quickly on invalid argument");
    assertEquals(1, process.exitValue());
  }

  @Test
  void testHelpArgumentExitsZero() throws Exception {
    ProcessBuilder pb = new ProcessBuilder(
        getJavaExecutable(),
        "-cp",
        getClasspath(),
        "io.github.jackbaozz.pocketbase.server.PocketBaseServer",
        "--help"
    );
    Process process = pb.start();
    StringBuilder stdout = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        stdout.append(line).append("\n");
      }
    }
    boolean finished = process.waitFor(10, TimeUnit.SECONDS);
    assertTrue(finished);
    assertEquals(0, process.exitValue());
    assertTrue(stdout.toString().contains("Usage:"));
  }

  @Test
  void testServerStartsAndTerminatesOnSignal() throws Exception {
    Path dbDir = tempDir.resolve("pb_data");
    ProcessBuilder pb = new ProcessBuilder(
        getJavaExecutable(),
        "-cp",
        getClasspath(),
        "io.github.jackbaozz.pocketbase.server.PocketBaseServer",
        "--dir=" + dbDir,
        "--port=0"
    );
    Process process = pb.start();

    // Wait until server prints listening
    boolean started = false;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      for (int i = 0; i < 50; i++) {
        if (reader.ready()) {
          String line = reader.readLine();
          if (line != null && line.contains("listening on")) {
            started = true;
            break;
          }
        }
        Thread.sleep(100);
      }
    }

    assertTrue(started, "Server should output listening confirmation");
    assertTrue(process.isAlive());

    // Terminate via destroy (SIGTERM on Unix)
    process.destroy();
    boolean exited = process.waitFor(10, TimeUnit.SECONDS);
    assertTrue(exited, "Server should exit cleanly on SIGTERM");
  }
}
