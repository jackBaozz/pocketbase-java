package io.github.jackbaozz.pocketbase;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal in-memory auth state holder.
 */
public final class AuthStore {
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<JsonNode> record = new AtomicReference<>();

    public Optional<String> token() {
        return Optional.ofNullable(token.get()).filter(value -> !value.isBlank());
    }

    public Optional<JsonNode> record() {
        return Optional.ofNullable(record.get());
    }

    public boolean isValid() {
        return token().isPresent();
    }

    public void save(AuthResponse response) {
        if (response == null || response.token() == null || response.token().isBlank()) {
            clear();
            return;
        }
        token.set(response.token());
        record.set(response.record());
    }

    public void save(String newToken, JsonNode newRecord) {
        if (newToken == null || newToken.isBlank()) {
            clear();
            return;
        }
        token.set(newToken);
        record.set(newRecord);
    }

    public void clear() {
        token.set(null);
        record.set(null);
    }
}
