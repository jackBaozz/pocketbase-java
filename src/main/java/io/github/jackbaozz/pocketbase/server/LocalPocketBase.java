package io.github.jackbaozz.pocketbase.server;

import com.sun.net.httpserver.HttpServer;
import io.github.jackbaozz.pocketbase.server.internal.HttpApi;
import io.github.jackbaozz.pocketbase.server.internal.JsonFileStore;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import io.github.jackbaozz.pocketbase.server.internal.RealtimeHub;
import io.github.jackbaozz.pocketbase.server.internal.StorageEngine;
import io.github.jackbaozz.pocketbase.server.internal.RelationalStorageEngine;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Programmatic handle for the embedded PocketBase-like runtime.
 */
public final class LocalPocketBase implements AutoCloseable {
    private final ServerConfig config;
    private final HttpServer httpServer;
    private final StorageEngine store;
    private final ExecutorService executor;

    private LocalPocketBase(ServerConfig config, HttpServer httpServer, StorageEngine store, ExecutorService executor) {
        this.config = config;
        this.httpServer = httpServer;
        this.store = store;
        this.executor = executor;
    }

    public static LocalPocketBase start(ServerConfig config) throws IOException {
        StorageEngine store;
        String storageType = System.getProperty("storage");
        if ("sqlite".equalsIgnoreCase(storageType)
                || "mysql".equalsIgnoreCase(storageType)
                || "mariadb".equalsIgnoreCase(storageType)
                || "postgres".equalsIgnoreCase(storageType)
                || "postgresql".equalsIgnoreCase(storageType)) {
            store = RelationalStorageEngine.open(
                    config.dataDir(),
                    config.bootstrapSuperuserEmail(),
                    config.bootstrapSuperuserPassword(),
                    JooqDatabase.Engine.fromStorageType(storageType)
            );
        } else {
            store = JsonFileStore.open(
                    config.dataDir(),
                    config.bootstrapSuperuserEmail(),
                    config.bootstrapSuperuserPassword()
            );
        }
        RealtimeHub realtimeHub = new RealtimeHub(store.mapper());
        store.realtimeHub(realtimeHub);
        HttpServer server = HttpServer.create(config.bindAddress(), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", new HttpApi(store, realtimeHub));
        server.start();
        return new LocalPocketBase(config, server, store, executor);
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public String baseUrl() {
        return config.displayUrl(port());
    }

    public StorageEngine store() {
        return store;
    }

    @Override
    public void close() {
        httpServer.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        store.close();
    }
}
