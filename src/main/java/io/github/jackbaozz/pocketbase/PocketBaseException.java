package io.github.jackbaozz.pocketbase;

import java.net.URI;

/**
 * Runtime exception for non-2xx PocketBase HTTP responses or client transport failures.
 */
public class PocketBaseException extends RuntimeException {
    private final int statusCode;
    private final String method;
    private final URI uri;
    private final String responseBody;
    private final PocketBaseError error;

    PocketBaseException(int statusCode, String method, URI uri, String responseBody, PocketBaseError error) {
        super(buildMessage(statusCode, method, uri, responseBody, error));
        this.statusCode = statusCode;
        this.method = method;
        this.uri = uri;
        this.responseBody = responseBody;
        this.error = error;
    }

    PocketBaseException(String method, URI uri, Throwable cause) {
        super("PocketBase request failed: " + method + " " + uri + " - " + cause.getMessage(), cause);
        this.statusCode = -1;
        this.method = method;
        this.uri = uri;
        this.responseBody = "";
        this.error = null;
    }

    public int statusCode() {
        return statusCode;
    }

    public String method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    public String responseBody() {
        return responseBody;
    }

    public PocketBaseError error() {
        return error;
    }

    private static String buildMessage(
            int statusCode,
            String method,
            URI uri,
            String responseBody,
            PocketBaseError error
    ) {
        String message = error != null && error.message() != null && !error.message().isBlank()
                ? error.message()
                : responseBody;
        return "PocketBase request failed: " + method + " " + uri + " -> " + statusCode + " " + message;
    }
}
