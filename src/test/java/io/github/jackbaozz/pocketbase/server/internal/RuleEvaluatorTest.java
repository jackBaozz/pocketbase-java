package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEvaluatorTest {

  @Test
  void matchesEmptyExpression() {
    assertTrue(
        RuleEvaluator.matches(null, RuleEvaluator.context(Map.of(), null, null, null, null)));
    assertTrue(RuleEvaluator.matches("", RuleEvaluator.context(Map.of(), null, null, null, null)));
    assertTrue(
        RuleEvaluator.matches("   ", RuleEvaluator.context(Map.of(), null, null, null, null)));
  }

  @Test
  void matchesEquality() {
    var ctx = RuleEvaluator.context(Map.of("name", "John"), null, null, null, null);
    assertTrue(RuleEvaluator.matches("name = 'John'", ctx));
    assertFalse(RuleEvaluator.matches("name = 'Jane'", ctx));
    assertTrue(RuleEvaluator.matches("name == 'John'", ctx));
  }

  @Test
  void matchesInequality() {
    var ctx = RuleEvaluator.context(Map.of("age", 25), null, null, null, null);
    assertTrue(RuleEvaluator.matches("age != 30", ctx));
    assertFalse(RuleEvaluator.matches("age != 25", ctx));
  }

  @Test
  void matchesComparison() {
    var ctx = RuleEvaluator.context(Map.of("age", 25, "score", 95.5), null, null, null, null);
    assertTrue(RuleEvaluator.matches("age > 20", ctx));
    assertTrue(RuleEvaluator.matches("age >= 25", ctx));
    assertTrue(RuleEvaluator.matches("age < 30", ctx));
    assertTrue(RuleEvaluator.matches("age <= 25", ctx));
    assertTrue(RuleEvaluator.matches("score > 90.0", ctx));
  }

  @Test
  void matchesLogical() {
    var ctx = RuleEvaluator.context(Map.of("name", "John", "age", 25), null, null, null, null);
    assertTrue(RuleEvaluator.matches("name = 'John' && age = 25", ctx));
    assertFalse(RuleEvaluator.matches("name = 'John' && age = 30", ctx));
    assertTrue(RuleEvaluator.matches("name = 'Jane' || age = 25", ctx));
    assertFalse(RuleEvaluator.matches("name = 'Jane' || age = 30", ctx));
  }

  @Test
  void matchesParentheses() {
    var ctx = RuleEvaluator.context(Map.of("role", "admin", "age", 15), null, null, null, null);
    assertTrue(RuleEvaluator.matches("(role = 'admin' || role = 'manager') && age < 20", ctx));
    assertFalse(RuleEvaluator.matches("role = 'user' || (role = 'admin' && age > 20)", ctx));
  }

  @Test
  void matchesContains() {
    var ctx = RuleEvaluator.context(Map.of("title", "Hello World"), null, null, null, null);
    assertTrue(RuleEvaluator.matches("title ~ 'World'", ctx));
    assertTrue(RuleEvaluator.matches("title ~ 'hello'", ctx)); // case insensitive
    assertFalse(RuleEvaluator.matches("title ~ 'Goodbye'", ctx));
    assertTrue(RuleEvaluator.matches("title !~ 'Goodbye'", ctx));
  }

  @Test
  void matchesNull() {
    var ctx = RuleEvaluator.context(Map.of("name", "John"), null, null, null, null);
    assertTrue(RuleEvaluator.matches("missing = null", ctx));
    assertFalse(RuleEvaluator.matches("name = null", ctx));
    assertTrue(RuleEvaluator.matches("name != null", ctx));
  }

  @Test
  void matchesBoolean() {
    var ctx =
        RuleEvaluator.context(Map.of("active", true, "verified", false), null, null, null, null);
    assertTrue(RuleEvaluator.matches("active = true", ctx));
    assertTrue(RuleEvaluator.matches("verified = false", ctx));
    assertFalse(RuleEvaluator.matches("active = false", ctx));
  }

  @Test
  void contextVariables() {
    var auth =
        RequestPrincipal.fromClaims(
            Map.of(
                "sub", "user123",
                "email", "test@example.com",
                "collectionName", "users",
                "verified", true));
    Map<String, Object> body = Map.of("status", "published", "count", 10);
    Map<String, String> query = Map.of("filter", "active=true");
    Map<String, Object> record = Map.of("userId", "user123");

    var ctx = RuleEvaluator.context(record, body, query, "POST", auth);

    assertTrue(RuleEvaluator.matches("@request.auth.id = userId", ctx));
    assertTrue(RuleEvaluator.matches("@request.auth.email = 'test@example.com'", ctx));
    assertTrue(RuleEvaluator.matches("@request.auth.collectionName = 'users'", ctx));
    assertTrue(RuleEvaluator.matches("@request.auth.verified = true", ctx));
    assertTrue(RuleEvaluator.matches("@request.body.status = 'published'", ctx));
    assertTrue(RuleEvaluator.matches("@request.body.count > 5", ctx));
    assertTrue(RuleEvaluator.matches("@request.query.filter = 'active=true'", ctx));
    assertTrue(RuleEvaluator.matches("@request.method = 'POST'", ctx));
  }

  @Test
  void requestHeadersAreCaseInsensitiveAndSupportIsset() {
    var ctx =
        RuleEvaluator.context(
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("X-Rule-Token", "allow", "X-Empty", ""),
            "GET",
            null,
            ignored -> List.of(),
            null);

    assertTrue(RuleEvaluator.matches("@request.headers.x-rule-token = 'allow'", ctx));
    assertTrue(RuleEvaluator.matches("@request.headers.X-RULE-TOKEN = 'allow'", ctx));
    assertTrue(RuleEvaluator.matches("@request.headers.x-empty:isset = true", ctx));
    assertTrue(RuleEvaluator.matches("@request.headers.missing:isset = false", ctx));
    assertTrue(RuleEvaluator.matches("@request.context = 'default'", ctx));

    var batchCtx =
        RuleEvaluator.context(
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            RuleRequestContext.BATCH,
            "POST",
            null,
            ignored -> List.of(),
            null);
    assertTrue(RuleEvaluator.matches("@request.context = 'batch'", batchCtx));
  }

  @Test
  void collectionRelations() {
    var ctx =
        RuleEvaluator.context(
            Map.of("authorId", "auth1"),
            null,
            null,
            null,
            null,
            col -> {
              if ("users".equals(col)) {
                return List.of(
                    Map.of("id", "auth1", "role", "admin", "tags", List.of("staff", "writer")),
                    Map.of("id", "auth2", "role", "user"));
              }
              return List.of();
            });

    // Any relation matches
    assertTrue(RuleEvaluator.matches("@collection.users.role ?= 'admin'", ctx));
    assertFalse(RuleEvaluator.matches("@collection.users.role ?= 'manager'", ctx));
    assertFalse(RuleEvaluator.matches("@collection.users.role = 'admin'", ctx));

    // Array contents
    assertTrue(RuleEvaluator.matches("@collection.users.tags ?~ 'writer'", ctx));

    // Empty array comparison
    var emptyArrayCtx = RuleEvaluator.context(Map.of("items", List.of()), null, null, null, null);
    assertTrue(RuleEvaluator.matches("items != null", emptyArrayCtx)); // Array exists but is empty
  }

  @Test
  void distinguishesMultiMatchAllAndAnyOperators() {
    var ctx =
        RuleEvaluator.context(
            Map.of(),
            null,
            null,
            null,
            null,
            ignored -> List.of(),
            identifier -> "reviewers.name".equals(identifier)
                ? RuleEvaluator.Resolution.resolved(
                    RuleEvaluator.multiMatch(List.of("Ada", "Grace")))
                : RuleEvaluator.Resolution.unresolved());

    assertFalse(RuleEvaluator.matches("reviewers.name = 'Ada'", ctx));
    assertTrue(RuleEvaluator.matches("reviewers.name ?= 'Ada'", ctx));
    assertTrue(RuleEvaluator.matches("reviewers.name != 'Linus'", ctx));
    assertFalse(RuleEvaluator.matches("reviewers.name ?= 'Linus'", ctx));
  }

  @Test
  void supportsOfficialFieldModifiers() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "new");
    body.put("same", "value");
    body.put("nullable", null);
    var ctx =
        RuleEvaluator.context(
            Map.of(
                "title", "Bravo",
                "tags", List.of("red", "green"),
                "status", "old",
                "same", "value"),
            body,
            Map.of("flag", ""),
            "PATCH",
            null);

    assertTrue(RuleEvaluator.matches("title:lower = 'bravo'", ctx));
    assertTrue(RuleEvaluator.matches("tags:length = 2", ctx));
    assertTrue(RuleEvaluator.matches("tags:each = 'green'", ctx));
    assertTrue(RuleEvaluator.matches("@request.query.flag:isset = true", ctx));
    assertTrue(RuleEvaluator.matches("@request.body.nullable:isset = true", ctx));
    assertTrue(RuleEvaluator.matches("@request.body.missing:isset = false", ctx));
    assertTrue(RuleEvaluator.matches("@request.body.status:changed = true", ctx));
    assertTrue(RuleEvaluator.matches("@request.body.same:changed = false", ctx));

    ApiException error =
        assertThrows(
            ApiException.class, () -> RuleEvaluator.matches("title:unknown = 'bravo'", ctx));
    assertEquals(400, error.status());
    assertEquals("Invalid filter.", error.getMessage());
  }

  @Test
  void supportsOfficialFilterTokenFunctions() {
    var ctx =
        RuleEvaluator.context(
            Map.of("created", "2026-07-18T10:30:45.123Z", "location", Map.of("lon", 0.1, "lat", 0)),
            null,
            null,
            "GET",
            null);

    assertTrue(RuleEvaluator.matches("strftime('%Y-%m', created) = '2026-07'", ctx));
    assertTrue(
        RuleEvaluator.matches(
            "strftime('%F', created, 'start of month', '+1 month') = '2026-08-01'", ctx));
    assertTrue(RuleEvaluator.matches("strftime('%s', 0, 'unixepoch') = '0'", ctx));
    assertTrue(
        RuleEvaluator.matches("strftime('%F', '2024-02-29', '+1 year') = '2025-03-01'", ctx));
    assertTrue(
        RuleEvaluator.matches(
            "strftime('%F', '2024-02-29', '+1 year', 'floor') = '2025-02-28'", ctx));
    assertTrue(RuleEvaluator.matches("geoDistance(location.lon, location.lat, 0, 0) < 20", ctx));
    assertEquals(
        List.of("created", "location.lon", "location.lat"),
        RuleEvaluator.identifiers(
            "strftime('%Y', created) = '2026' && geoDistance(location.lon, location.lat, 0, 0) < 20"));

    var multiCtx =
        RuleEvaluator.context(
            Map.of(),
            null,
            null,
            "GET",
            null,
            ignored -> List.of(),
            identifier -> switch (identifier) {
              case "events.created" ->
                RuleEvaluator.Resolution.resolved(
                    RuleEvaluator.multiMatch(
                        List.of("2026-07-01T00:00:00Z", "2026-08-01T00:00:00Z")));
              case "offices.lon" ->
                RuleEvaluator.Resolution.resolved(RuleEvaluator.multiMatch(List.of(50, 0.1)));
              case "offices.lat" ->
                RuleEvaluator.Resolution.resolved(RuleEvaluator.multiMatch(List.of(0, 0)));
              default -> RuleEvaluator.Resolution.unresolved();
            });
    assertFalse(RuleEvaluator.matches("strftime('%Y-%m', events.created) = '2026-07'", multiCtx));
    assertTrue(RuleEvaluator.matches("strftime('%Y-%m', events.created) ?= '2026-07'", multiCtx));
    assertTrue(RuleEvaluator.matches("geoDistance(offices.lon, offices.lat, 0, 0) < 20", multiCtx));

    ApiException invalid =
        assertThrows(
            ApiException.class,
            () -> RuleEvaluator.matches("geoDistance(location.lon, 0) < 20", ctx));
    assertEquals("Invalid filter.", invalid.getMessage());
    assertThrows(
        ApiException.class, () -> RuleEvaluator.matches("unknownFunction(created) = 'x'", ctx));
  }

  @Test
  void matchesDateComparison() {
    var ctx =
        RuleEvaluator.context(
            Map.of("created", "2026-06-28 10:30:00.000Z"), null, null, null, null);
    assertTrue(RuleEvaluator.matches("created >= '2026-06-28 10:00:00.000Z'", ctx));
    assertTrue(RuleEvaluator.matches("created < '2026-06-29 00:00:00.000Z'", ctx));
  }

  @Test
  void matchesEmptyStringAndPlaceholderLiteralExpressions() {
    var ctx =
        RuleEvaluator.context(
            Map.of(
                "title", "",
                "quoted", "''",
                "tag", "{:other}",
                "escaped", "hello 'world'"),
            null,
            null,
            null,
            null);

    // Empty string matches
    assertTrue(RuleEvaluator.matches("title = ''", ctx));
    assertTrue(RuleEvaluator.matches("title = \"\"", ctx));
    assertFalse(RuleEvaluator.matches("title != ''", ctx));
    assertFalse(RuleEvaluator.matches("title = 'anything'", ctx));

    // Literal containing quotes vs empty string
    assertTrue(RuleEvaluator.matches("quoted = \"''\"", ctx));
    assertFalse(RuleEvaluator.matches("title = \"''\"", ctx));

    // Literal containing placeholder pattern like {:other}
    assertTrue(RuleEvaluator.matches("tag = '{:other}'", ctx));
    assertTrue(RuleEvaluator.matches("tag ~ '{:other}'", ctx));
    assertFalse(RuleEvaluator.matches("tag = '{:different}'", ctx));

    // Escaped quotes in string literals
    assertTrue(RuleEvaluator.matches("escaped = 'hello \\'world\\''", ctx));
  }
}
