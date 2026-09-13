package io.github.jackbaozz.pocketbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class JsSdkSmokeTest {

  @TempDir
  Path dataDir;

  @TempDir
  Path smokeTempDir;

  private LocalPocketBase server;
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    // Start the Java pocketbase server
    ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null, null);
    TestDatabaseFactory.init();
    server = TestDatabaseFactory.start(config);
    baseUrl = "http://127.0.0.1:" + server.port();

    // Install into a JUnit-owned temporary directory so npm never rewrites tracked fixture files.
    Path sourceJsDir = Path.of("src/test/resources/js-sdk-smoke");
    Path jsDir = smokeTempDir.resolve("js-sdk-smoke");
    Files.createDirectories(jsDir);
    Files.copy(sourceJsDir.resolve("package.json"), jsDir.resolve("package.json"));
    Files.copy(sourceJsDir.resolve("package-lock.json"), jsDir.resolve("package-lock.json"));
    Files.copy(sourceJsDir.resolve("smoke.js"), jsDir.resolve("smoke.js"));

    Path npmLog = jsDir.resolve("npm-install.log");
    ProcessBuilder npmPb = new ProcessBuilder("npm", "ci", "--ignore-scripts");
    npmPb.directory(jsDir.toFile());
    npmPb.redirectErrorStream(true);
    npmPb.redirectOutput(npmLog.toFile());
    Process npm = npmPb.start();
    boolean finished = npm.waitFor(120, TimeUnit.SECONDS);
    if (!finished) {
      npm.destroyForcibly();
      npm.waitFor(5, TimeUnit.SECONDS);
    }
    int exitCode = finished ? npm.exitValue() : -1;
    String npmOutput = Files.exists(npmLog)
        ? Files.readString(npmLog, StandardCharsets.UTF_8)
        : "<npm produced no output>";
    assertEquals(0, exitCode, "npm ci failed for the JS SDK smoke fixture:\n" + npmOutput);

    Path sdkPackage = jsDir.resolve("node_modules/pocketbase/package.json");
    assertTrue(sdkPackage.toFile().isFile(), "JS SDK 0.28.1 must be installed for the smoke test");
    String sdkMetadata = Files.readString(sdkPackage, StandardCharsets.UTF_8);
    assertTrue(
        sdkMetadata.contains("\"version\": \"0.28.1\""),
        "JS SDK smoke must execute against pocketbase 0.28.1");
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void testOfficialJsSdkCompatibility() throws Exception {
    File jsScript = smokeTempDir.resolve("js-sdk-smoke/smoke.js").toFile();
    assertTrue(jsScript.exists(), "Smoke test script should exist");

    ProcessBuilder pb = new ProcessBuilder("node", "smoke.js", baseUrl);
    pb.directory(jsScript.getParentFile());
    pb.redirectErrorStream(true);

    Process process = pb.start();

    StringBuilder output = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append("\n");
        System.out.println("[JS-SDK] " + line);
      }
    }

    int exitCode = process.waitFor();

    System.out.println("Node script finished with exit code: " + exitCode);
    assertEquals(0, exitCode, "JS SDK smoke test failed with output:\n" + output.toString());
    assertTrue(
        output.toString().contains("JS SDK Smoke Test Passed!"),
        "Success message should be present");
  }
}
