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

  /**
   * Keeps an internal failure available to callers that need to decide whether an operation is
   * safe to retry. The HTTP response still exposes only {@link #status()}, {@link #getMessage()},
   * and {@link #data()}.
   */
  public ApiException(int status, String message, Object data, Throwable cause) {
    super(message, cause);
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
