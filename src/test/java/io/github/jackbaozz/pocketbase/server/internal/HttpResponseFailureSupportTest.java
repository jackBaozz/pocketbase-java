package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketException;
import org.junit.jupiter.api.Test;

class HttpResponseFailureSupportTest {
  @Test
  void ignoresOnlyKnownDisconnectsAfterSuccessfulHeaders() {
    for (String message : new String[] {"write: Broken pipe", "Connection reset by peer",
        "http2: stream closed", "http2: request body closed due to handler exiting",
        "write error: client disconnected"}) {
      assertTrue(HttpResponseFailureSupport.isBenign(200, new SocketException(message)));
      assertTrue(HttpResponseFailureSupport.isBenign(206, new IOException(new SocketException(message))));
      assertFalse(HttpResponseFailureSupport.isBenign(-1, new SocketException(message)));
      assertFalse(HttpResponseFailureSupport.isBenign(400, new SocketException(message)));
      assertFalse(HttpResponseFailureSupport.isBenign(500, new SocketException(message)));
    }
  }

  @Test
  void doesNotHideStorageOrUnclassifiedFailures() {
    assertFalse(HttpResponseFailureSupport.isBenign(200, new IOException("disk read failed")));
    assertFalse(HttpResponseFailureSupport.isBenign(200, null));
  }
}
