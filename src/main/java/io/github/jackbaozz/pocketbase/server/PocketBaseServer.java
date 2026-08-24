package io.github.jackbaozz.pocketbase.server;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/** Command line entry point for the embedded server. */
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
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      System.exit(1);
      return;
    }

    printBanner(config.applicationName());

    LocalPocketBase server;
    try {
      server = LocalPocketBase.start(config);
    } catch (Exception e) {
      System.err.println("Failed to start server: " + e.getMessage());
      System.exit(1);
      return;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "pocketbase-java-shutdown"));

    System.out.println("pocketbase-java listening on " + server.baseUrl());
    System.out.println("Admin UI: " + server.baseUrl() + "/_/");
    new CountDownLatch(1).await();
  }

  private static void printBanner(String applicationName) {
    System.out.println();
    System.out.println(
        "                   _        _   _                          _                  ");
    System.out.println(
        "  _ __   ___   ___| | _____| |_| |__   __ _ ___  ___      (_) __ ___   ____ _ ");
    System.out.println(
        " | '_ \\ / _ \\ / __| |/ / _ \\ __| '_ \\ / _` / __|/ _ \\_____| |/ _` \\ \\ / / _` |");
    System.out.println(
        " | |_) | (_) | (__|   <  __/ |_| |_) | (_| \\__ \\  __/_____| | (_| |\\ V / (_| |");
    System.out.println(
        " | .__/ \\___/ \\___|_|\\_\\___|\\__|_.__/ \\__,_|___/\\___|    _/ |\\__,_| \\_/ \\__,_|");
    System.out.println(
        " |_|                                                    |__/                  ");
    System.out.println();
    String displayName =
        applicationName == null || applicationName.isBlank()
            ? "PocketBase Java"
            : applicationName.trim();
    System.out.println(" :: " + displayName + " ::");
    System.out.println();
  }
}
