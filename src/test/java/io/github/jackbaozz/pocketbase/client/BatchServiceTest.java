package io.github.jackbaozz.pocketbase.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchServiceTest {
    private HttpServer server;
    private PocketBaseClient client;
    private String lastMethod;
    private String lastPath;
    private String lastBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/batch", this::handleBatch);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = PocketBaseClient.builder(baseUrl).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testBatchService() {
        // TODO: test batch request structure
    }

    private void handleBatch(HttpExchange exchange) throws IOException {
        lastMethod = exchange.getRequestMethod();
        lastPath = exchange.getRequestURI().getPath();
        
        try (InputStream input = exchange.getRequestBody()) {
            lastBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
