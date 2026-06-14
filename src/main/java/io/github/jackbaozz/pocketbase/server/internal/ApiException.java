package io.github.jackbaozz.pocketbase.server.internal;

import java.util.Map;

final class ApiException extends RuntimeException {
    private final int status;
    private final Object data;

    ApiException(int status, String message) {
        this(status, message, Map.of());
    }

    ApiException(int status, String message, Object data) {
        super(message);
        this.status = status;
        this.data = data == null ? Map.of() : data;
    }

    int status() {
        return status;
    }

    Object data() {
        return data;
    }
}
