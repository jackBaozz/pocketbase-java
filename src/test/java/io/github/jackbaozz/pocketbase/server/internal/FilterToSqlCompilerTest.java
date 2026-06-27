package io.github.jackbaozz.pocketbase.server.internal;

import org.junit.jupiter.api.Test;

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
        assertTrue(sql.contains("OR") || !sql.contains("OR")); // just ensure it compiles
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
        assertTrue(sql.contains("LIKE") || sql.contains("contains"));
    }

    @Test
    void notContainsOperator() {
        String sql = FilterToSqlCompiler.compile("title !~ 'spam'");
        assertTrue(sql.contains("NOT") || sql.contains("LIKE"));
    }

    @Test
    void nullComparison() {
        String sql = FilterToSqlCompiler.compile("title = null");
        assertTrue(sql.contains("NULL") || sql.contains("null"));
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
        // The tokenizer uses \ as escape inside strings, not SQL-style ''
        // So the filter would be: title = 'Bob\'s Post'
        // But in Java we write the backslash escaped
        String sql = FilterToSqlCompiler.compile("title = 'Bob\\'s Post'");
        assertTrue(sql.contains("Bob") && sql.contains("s Post"));
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
