package io.github.jackbaozz.pocketbase.server.testutils;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.net.URLDecoder;

public class MockOAuth2Server {
    private HttpServer server;
    private final int port;
    private final ObjectMapper mapper = new ObjectMapper();

    public MockOAuth2Server(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        server.createContext("/auth", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String redirectUri = "http://localhost:8090/api/oauth2-redirect";
            String state = "";

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2) {
                        if (pair[0].equals("redirect_uri")) redirectUri = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        if (pair[0].equals("state")) state = pair[1];
                    }
                }
            }

            String redirect = redirectUri + "?code=mock_code_123&state=" + state;
            exchange.getResponseHeaders().set("Location", redirect);
            exchange.sendResponseHeaders(302, -1);
        });

        server.createContext("/token", exchange -> {
            Map<String, Object> response = Map.of(
                "access_token", "mock_access_token",
                "token_type", "Bearer",
                "expires_in", 3600,
                "id_token", createMockIdToken()
            );

            String json = mapper.writeValueAsString(response);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.createContext("/userinfo", exchange -> {
            Map<String, Object> response = Map.of(
                "sub", "mock_user_123",
                "email", "mock@example.com",
                "name", "Mock User",
                "picture", "https://example.com/avatar.png"
            );

            String json = mapper.writeValueAsString(response);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private String createMockIdToken() {
        try {
            Map<String, Object> header = Map.of("alg", "none", "typ", "JWT");
            Map<String, Object> claims = Map.of(
                "sub", "mock_user_123",
                "email", "mock@example.com"
            );

            String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(header));
            String encodedClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims));

            return encodedHeader + "." + encodedClaims + ".";
        } catch (Exception e) {
            return "";
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
