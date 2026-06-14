package io.github.jackbaozz.pocketbase.server;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Command line entry point for the embedded server.
 */
public final class PocketBaseServer {
    private PocketBaseServer() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ServerConfig config;
        try {
            config = ServerConfig.fromArgs(args);
        } catch (ServerConfig.HelpRequested ignored) {
            System.out.print(ServerConfig.usage());
            return;
        }

        LocalPocketBase server = LocalPocketBase.start(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "pocketbase-java-shutdown"));

        System.out.println("pocketbase-java listening on " + server.baseUrl());
        System.out.println("Admin UI: " + server.baseUrl() + "/_/");
        new CountDownLatch(1).await();
    }
}
