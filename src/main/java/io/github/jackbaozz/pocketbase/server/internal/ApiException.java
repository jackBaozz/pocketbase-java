package io.github.jackbaozz.pocketbase.server.internal;

import java.util.Map;

public final class ApiException extends RuntimeException {
    private final int status;
    private final Object data;

    public ApiException(int status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(int status, String message, Object data) {
        super(message);
        this.status = status;
        this.data = data == null ? Map.of() : data;
    }

    public int status() {
        return status;
    }

    public Object data() {
        return data;
    }
}
