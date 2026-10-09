package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpCorsPolicyTest {
  @Test
  void strictFastPathMatchesUpstreamScenarios() {
    String[][] matches = {
        {"abc://example.com", "abc://example.com"},
        {"https://example.com", "https://example.com"},
        {"https://a.example.com", "https://*.example.com"},
        {"https://a.b.example.com", "https://a.*.example.com"},
        {"https://a.b", "https://a.*"}
    };
    String[][] rejected = {
        {"", ""},
        {"http://example.com", ""},
        {"", "http://example.com"},
        {"http://example.com", "https://example.com"},
        {"https://a.example.com", "https://example.com"},
        {"https://example.com", "https://a.example.com"},
        {"https://example.com", "https://*.example.com"},
        {"https://a.example.com", "https://a.*.example.com"},
        {"https://a.b.example.com", "https://a2.*.example.com"},
        {"https://a.b.example.com", "https://a.*"},
        {"https://a.b.example", "https://a.*"}
    };
    for (String[] pair : matches) {
      assertTrue(HttpCorsPolicy.exactWildcardMatch(pair[0], pair[1]), String.join(" / ", pair));
    }
    for (String[] pair : rejected) {
      assertFalse(HttpCorsPolicy.exactWildcardMatch(pair[0], pair[1]), String.join(" / ", pair));
    }
  }

  @Test
  void wildcardNeverSkipsTheRequiredPrefixOrDomainSuffix() {
    var policy = new HttpCorsPolicy(List.of("https://trusted.*.example.com"));
    assertEquals("https://trusted.a.example.com", policy.allowOrigin("https://trusted.a.example.com"));
    assertNull(policy.allowOrigin("https://evil.a.example.com"));
    assertNull(policy.allowOrigin("https://trusted.a.example.com.evil.test"));
    assertNull(policy.allowOrigin("http://trusted.a.example.com"));
    assertNull(policy.allowOrigin("https://trusted.a.example.com:8443"));
  }

  @Test
  void preservesTheBoundedFallbackIncludingNestedSubdomainsAndQuestionMarks() {
    var policy = new HttpCorsPolicy(List.of("https://*.example.com", "http://api?.test:8090"));
    assertEquals("https://a.b.example.com", policy.allowOrigin("https://a.b.example.com"));
    assertEquals("http://api1.test:8090", policy.allowOrigin("http://api1.test:8090"));
    assertNull(policy.allowOrigin("http://api12.test:8090"));
    assertNull(policy.allowOrigin("https://example.com"));
    assertNull(policy.allowOrigin("https://example.com.evil.test"));
  }

  @Test
  void ignoresOverlongWildcardOriginsButPreservesExplicitMatches() {
    String longOrigin = "https://" + "a".repeat(254) + ".example.com";
    assertNull(new HttpCorsPolicy(List.of("https://*.example.com")).allowOrigin(longOrigin));
    assertFalse(HttpCorsPolicy.exactWildcardMatch(longOrigin, "https://*.example.com"));
    assertEquals(longOrigin, new HttpCorsPolicy(List.of(longOrigin)).allowOrigin(longOrigin));
  }

  @Test
  void handlesDefaultWildcardOpaqueOriginsAndRequestsWithoutOrigins() {
    assertEquals("*", new HttpCorsPolicy(List.of()).allowOrigin("https://example.com"));
    var exact = new HttpCorsPolicy(List.of("null", "https://example.com"));
    assertEquals("null", exact.allowOrigin("null"));
    assertNull(exact.allowOrigin("https://untrusted.test"));
    assertNull(exact.allowOrigin(null));
    assertNull(exact.allowOrigin(""));
  }
}
