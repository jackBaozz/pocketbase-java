package io.github.jackbaozz.pocketbase.server.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleEvaluatorTest {

    @Test
    void matchesEmptyExpression() {
        assertTrue(RuleEvaluator.matches(null, RuleEvaluator.context(Map.of(), null, null, null, null)));
        assertTrue(RuleEvaluator.matches("", RuleEvaluator.context(Map.of(), null, null, null, null)));
        assertTrue(RuleEvaluator.matches("   ", RuleEvaluator.context(Map.of(), null, null, null, null)));
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
        var ctx = RuleEvaluator.context(Map.of("active", true, "verified", false), null, null, null, null);
        assertTrue(RuleEvaluator.matches("active = true", ctx));
        assertTrue(RuleEvaluator.matches("verified = false", ctx));
        assertFalse(RuleEvaluator.matches("active = false", ctx));
    }

    @Test
    void contextVariables() {
        var auth = RequestPrincipal.fromClaims(Map.of(
                "sub", "user123",
                "email", "test@example.com",
                "collectionName", "users",
                "verified", true
        ));
        Map<String, Object> body = Map.of("status", "published", "count", 10);
        Map<String, String> query = Map.of("filter", "active=true");
        Map<String, Object> record = Map.of("userId", "user123");
        
        var ctx = RuleEvaluator.context(record, body, query, "POST", auth);
        
        assertTrue(RuleEvaluator.matches("@request.auth.id = userId", ctx));
        assertTrue(RuleEvaluator.matches("@request.auth.email = 'test@example.com'", ctx));
        assertTrue(RuleEvaluator.matches("@request.auth.verified = true", ctx));
        assertTrue(RuleEvaluator.matches("@request.body.status = 'published'", ctx));
        assertTrue(RuleEvaluator.matches("@request.body.count > 5", ctx));
        assertTrue(RuleEvaluator.matches("@request.query.filter = 'active=true'", ctx));
        assertTrue(RuleEvaluator.matches("@request.method = 'POST'", ctx));
    }

    @Test
    void collectionRelations() {
        var ctx = RuleEvaluator.context(
                Map.of("authorId", "auth1"),
                null, null, null, null,
                col -> {
                    if ("users".equals(col)) {
                        return List.of(
                                Map.of("id", "auth1", "role", "admin", "tags", List.of("staff", "writer")),
                                Map.of("id", "auth2", "role", "user")
                        );
                    }
                    return List.of();
                }
        );

        // Any relation matches
        assertTrue(RuleEvaluator.matches("@collection.users.role ?= 'admin'", ctx));
        assertFalse(RuleEvaluator.matches("@collection.users.role ?= 'manager'", ctx));
        
        // Array contents
        assertTrue(RuleEvaluator.matches("@collection.users.tags ?~ 'writer'", ctx));
        
        // Empty array comparison
        var emptyArrayCtx = RuleEvaluator.context(
                Map.of("items", List.of()),
                null, null, null, null
        );
        assertTrue(RuleEvaluator.matches("items != null", emptyArrayCtx)); // Array exists but is empty
    }
    void matchesDateComparison() {
        var ctx = RuleEvaluator.context(Map.of("created", "2026-06-28 10:30:00.000Z"), null, null, null, null);
        assertTrue(RuleEvaluator.matches("created >= '2026-06-28 10:00:00.000Z'", ctx));
        assertTrue(RuleEvaluator.matches("created < '2026-06-29 00:00:00.000Z'", ctx));
    }
}
