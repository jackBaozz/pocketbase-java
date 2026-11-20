package io.github.jackbaozz.pocketbase.server.internal;

import java.io.IOException;
import java.util.Locale;

/** Classifies client disconnects only after a successful response has been committed. */
final class HttpResponseFailureSupport {
  private HttpResponseFailureSupport() {
  }

  static boolean isBenign(int status, IOException failure) {
    if (status < 200 || status >= 400 || failure == null) {
      return false;
    }
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
      String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains("broken pipe") || message.contains("connection reset")
          || message.contains("write error: client disconnected")
          || message.contains("http2: stream closed")
          || message.contains("http2: request body closed due to handler exiting")) {
        return true;
      }
    }
    return false;
  }
}
