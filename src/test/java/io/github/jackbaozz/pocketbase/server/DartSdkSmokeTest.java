package io.github.jackbaozz.pocketbase.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DartSdkSmokeTest {
    private static final String REQUIRE_DART_SMOKE_PROPERTY = "requireDartSdkSmoke";

    private LocalPocketBase server;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabaseFactory.init();
        server = TestDatabaseFactory.start(new ServerConfig("127.0.0.1", 0, dataDir, null, null, null));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void officialDartSdkCanAuthenticateAndCrudRecords() throws Exception {
        ProcessBuilder dartCheck = new ProcessBuilder("dart", "--version");
        dartCheck.redirectErrorStream(true);
        try {
            Process check = dartCheck.start();
            requireDart(check.waitFor(5, TimeUnit.SECONDS), "dart --version timed out");
            requireDart(check.exitValue() == 0, "Dart SDK not available on PATH");
        } catch (Exception e) {
            requireDart(false, "Dart SDK not available on PATH");
            return;
        }

        File dartDir = new File("src/test/resources/dart-sdk-smoke");
        Process pubGet = new ProcessBuilder("dart", "pub", "get")
                .directory(dartDir)
                .redirectErrorStream(true)
                .start();
        byte[] pubOutput = pubGet.getInputStream().readAllBytes();
        assertTrue(pubGet.waitFor(60, TimeUnit.SECONDS), "dart pub get timed out");
        assertEquals(0, pubGet.exitValue(), "dart pub get failed:\n" + new String(pubOutput, StandardCharsets.UTF_8));

        Process smoke = new ProcessBuilder("dart", "run", "smoke.dart", server.baseUrl())
                .directory(dartDir)
                .redirectErrorStream(true)
                .start();
        byte[] output = smoke.getInputStream().readAllBytes();
        assertTrue(smoke.waitFor(60, TimeUnit.SECONDS), "Dart SDK smoke timed out");
        String text = new String(output, StandardCharsets.UTF_8);
        assertEquals(0, smoke.exitValue(), "Dart SDK smoke failed:\n" + text);
        assertTrue(text.contains("Dart SDK Smoke Test Passed!"), "Success message should be present");
    }

    private static void requireDart(boolean available, String message) {
        if (Boolean.getBoolean(REQUIRE_DART_SMOKE_PROPERTY)) {
            assertTrue(available, message);
        } else {
            Assumptions.assumeTrue(available, message);
        }
    }
}
