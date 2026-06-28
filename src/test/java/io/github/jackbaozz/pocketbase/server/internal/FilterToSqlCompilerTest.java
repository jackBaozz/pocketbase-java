package io.github.jackbaozz.pocketbase.server.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilterToSqlCompilerTest {

    @Test
    void emptyFilter() {
        assertEquals("1=1", FilterToSqlCompiler.compile(""));
        assertEquals("1=1", FilterToSqlCompiler.compile(null));
        assertEquals("1=1", FilterToSqlCompiler.compile("  "));
    }

    @Test
    void equalityOperator() {
        String sql = FilterToSqlCompiler.compile("title = 'hello'");
        assertTrue(sql.contains("="));
        assertTrue(sql.contains("hello"));
    }

    @Test
    void notEqualOperator() {
        String sql = FilterToSqlCompiler.compile("status != 'draft'");
        assertTrue(sql.contains("!=") || sql.contains("<>"));
        assertTrue(sql.contains("draft"));
    }

    @Test
    void comparisonOperators() {
        assertNotNull(FilterToSqlCompiler.compile("count > 5"));
        assertNotNull(FilterToSqlCompiler.compile("count >= 5"));
        assertNotNull(FilterToSqlCompiler.compile("count < 10"));
        assertNotNull(FilterToSqlCompiler.compile("count <= 10"));
    }

    @Test
    void logicalOperators() {
        String sql = FilterToSqlCompiler.compile("title = 'hello' AND status = 'published'");
        assertTrue(sql.contains("AND"));
        assertTrue(sql.contains("\"title\""));
        assertTrue(sql.contains("\"status\""));
    }

    @Test
    void orOperator() {
        String sql = FilterToSqlCompiler.compile("status = 'draft' OR status = 'published'");
        assertTrue(sql.contains("OR"));
    }

    @Test
    void parentheses() {
        String sql = FilterToSqlCompiler.compile("(title = 'a' OR title = 'b') AND status = 'published'");
        assertTrue(sql.contains("AND"));
        assertTrue(sql.contains("("));
        assertTrue(sql.contains(")"));
    }

    @Test
    void containsOperator() {
        String sql = FilterToSqlCompiler.compile("title ~ 'hello'");
        assertTrue(sql.contains("LIKE"));
        assertTrue(sql.contains("ESCAPE"));
        assertTrue(sql.contains("replace("));
    }

    @Test
    void notContainsOperator() {
        String sql = FilterToSqlCompiler.compile("title !~ 'spam'");
        assertTrue(sql.contains("NOT LIKE"));
        assertTrue(sql.contains("ESCAPE"));
    }

    @Test
    void nullComparison() {
        String sql = FilterToSqlCompiler.compile("title = null");
        assertEquals("\"title\" IS NULL", sql);
        assertEquals("\"title\" IS NOT NULL", FilterToSqlCompiler.compile("title != null"));
        assertThrows(ApiException.class, () -> FilterToSqlCompiler.compile("title > null"));
    }

    @Test
    void booleanLiteral() {
        String sql = FilterToSqlCompiler.compile("active = true");
        assertTrue(sql.contains("TRUE") || sql.contains("1"));
    }

    @Test
    void numericComparison() {
        String sql = FilterToSqlCompiler.compile("count > 42");
        assertTrue(sql.contains("42"));
        assertTrue(sql.contains(">"));
    }

    @Test
    void dateStringComparisonUsesBinding() {
        var result = FilterToSqlCompiler.compileBound("created >= '2026-06-28 10:30:00.000Z'",
                id -> "\"" + id + "\"");
        assertEquals("\"created\" >= ?", result.sql());
        assertEquals(List.of("2026-06-28 10:30:00.000Z"), result.bindings());
    }

    @Test
    void decimalNumber() {
        String sql = FilterToSqlCompiler.compile("price <= 9.99");
        assertTrue(sql.contains("9.99") || sql.contains("9,99"));
    }

    @Test
    void negativeNumber() {
        String sql = FilterToSqlCompiler.compile("balance < -100");
        assertTrue(sql.contains("-100") || sql.contains("100"));
    }

    @Test
    void stringWithSingleQuote() {
        String sql = FilterToSqlCompiler.compile("title = 'Bob\\'s Post'");
        assertEquals("\"title\" = 'Bob''s Post'", sql);
    }

    @Test
    void boundStringDoesNotInlineLiteral() {
        var result = FilterToSqlCompiler.compileBound("title = 'Bob\\'s Post' AND count >= 10",
                id -> "\"" + id + "\"");
        assertEquals("(\"title\" = ? AND \"count\" >= ?)", result.sql());
        assertEquals(List.of("Bob's Post", 10L), result.bindings());
        assertFalse(result.sql().contains("Bob"));
    }

    @Test
    void containsEscapesLikeWildcards() {
        var result = FilterToSqlCompiler.compileBound("title ~ '100%_\\\\match'",
                id -> "\"" + id + "\"");
        assertTrue(result.sql().contains("replace(replace(replace(?"));
        assertTrue(result.sql().contains("ESCAPE"));
        assertEquals(List.of("100%_\\match"), result.bindings());
    }

    @Test
    void requestAuthReference() {
        // @request.auth.email should compile to json_extract for SQLite
        var result = FilterToSqlCompiler.compileBound("email = @request.auth.email",
                id -> "\"" + id + "\"");
        assertTrue(result.sql().contains("request_auth") || result.sql().contains("@request"));
    }

    @Test
    void requestBodyReference() {
        var result = FilterToSqlCompiler.compileBound("status = @request.body.status",
                id -> "\"" + id + "\"");
        assertTrue(result.sql().contains("request_body") || result.sql().contains("@request"));
    }

    @Test
    void requestQueryReference() {
        var result = FilterToSqlCompiler.compileBound("category = @request.query.cat",
                id -> "\"" + id + "\"");
        assertTrue(result.sql().contains("request_query") || result.sql().contains("@request"));
    }

    @Test
    void requestMethodReference() {
        var result = FilterToSqlCompiler.compileBound("@request.method = 'POST'",
                id -> "\"" + id + "\"");
        assertTrue(result.sql().contains("request_method") || result.sql().contains("@request"));
    }

    @Test
    void requestHeadersReference() {
        var result = FilterToSqlCompiler.compileBound("host = @request.headers.host",
                id -> "\"" + id + "\"");
        assertTrue(result.sql().contains("request_headers") || result.sql().contains("@request"));
    }

    @Test
    void collectionReference() {
        var result = FilterToSqlCompiler.compileBound("@collection.users.email = 'test@example.com'",
                id -> "\"" + id + "\"");
        assertTrue(result.sql().contains("EXISTS") || result.sql().contains("users"));
    }

    @Test
    void boundParameters() {
        var result = FilterToSqlCompiler.compileBound("title = 'hello' AND count > 5",
                id -> "\"" + id + "\"");
        // With bindLiterals=true, values should be parameterized
        assertEquals(2, result.bindings().size());
        assertEquals("hello", result.bindings().get(0));
        assertEquals(5L, result.bindings().get(1));
    }

    @Test
    void identifierQuoting() {
        var result = FilterToSqlCompiler.compileBound("title = 'hello'",
                id -> "`" + id + "`");
        assertTrue(result.sql().contains("`title`"));
    }

    @Test
    void invalidFilterSyntax() {
        assertThrows(ApiException.class, () -> FilterToSqlCompiler.compile("!!!"));
    }

    @Test
    void rejectsTrailingTokens() {
        assertThrows(ApiException.class, () -> FilterToSqlCompiler.compile("title = 'hello' status = 'draft'"));
        assertThrows(ApiException.class, () -> FilterToSqlCompiler.compile("title = 'hello' OR"));
    }

    @Test
    void unsupportedOperator() {
        assertThrows(ApiException.class, () -> FilterToSqlCompiler.compile("title ^= 'hello'"));
    }

    @Test
    void nestedParentheses() {
        String sql = FilterToSqlCompiler.compile("((a = '1' OR b = '2') AND c = '3')");
        assertTrue(sql.contains("AND"));
        assertTrue(sql.contains("OR"));
    }

    @Test
    void complexFilter() {
        // Real-world-ish filter combining multiple features
        String sql = FilterToSqlCompiler.compile("title ~ 'hello' AND (status = 'published' OR featured = true) AND count > 0");
        assertNotNull(sql);
        assertTrue(sql.contains("AND"));
        assertTrue(sql.contains("OR"));
    }
}
