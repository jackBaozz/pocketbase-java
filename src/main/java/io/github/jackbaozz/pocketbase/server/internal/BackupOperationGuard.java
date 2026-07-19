package io.github.jackbaozz.pocketbase.server.internal;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class BackupOperationGuard {
    private final AtomicReference<String> activeKey = new AtomicReference<>();

    public boolean available() {
        return activeKey.get() == null;
    }

    public boolean active(String key) {
        return Objects.equals(activeKey.get(), key);
    }

    public <T> T run(String key, Supplier<T> operation) {
        String operationKey = key == null ? "" : key;
        if (!activeKey.compareAndSet(null, operationKey)) {
            throw new ApiException(400, "Try again later - another backup/restore process has already been started.");
        }
        try {
            return operation.get();
        } finally {
            activeKey.compareAndSet(operationKey, null);
        }
    }
}
