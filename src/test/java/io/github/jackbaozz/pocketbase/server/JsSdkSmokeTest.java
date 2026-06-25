package io.github.jackbaozz.pocketbase.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsSdkSmokeTest {

    @TempDir
    Path dataDir;

    private LocalPocketBase server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        // Start the Java pocketbase server
        ServerConfig config = new ServerConfig("127.0.0.1", 0, dataDir, null, null);
        TestDatabaseFactory.init();
        server = LocalPocketBase.start(config);
        baseUrl = "http://127.0.0.1:" + server.port();

        // Ensure npm modules are installed in our test resources dir
        File jsDir = new File("src/test/resources/js-sdk-smoke");
        if (jsDir.exists()) {
            ProcessBuilder npmPb = new ProcessBuilder("npm", "install");
            npmPb.directory(jsDir);
            npmPb.start().waitFor();
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testOfficialJsSdkCompatibility() throws Exception {
        File jsScript = new File("src/test/resources/js-sdk-smoke/smoke.js");
        assertTrue(jsScript.exists(), "Smoke test script should exist");

        ProcessBuilder pb = new ProcessBuilder("node", "smoke.js", baseUrl);
        pb.directory(jsScript.getParentFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                System.out.println("[JS-SDK] " + line);
            }
        }

        int exitCode = process.waitFor();

        System.out.println("Node script finished with exit code: " + exitCode);
        assertEquals(0, exitCode, "JS SDK smoke test failed with output:\n" + output.toString());
        assertTrue(output.toString().contains("JS SDK Smoke Test Passed!"), "Success message should be present");
    }
}
