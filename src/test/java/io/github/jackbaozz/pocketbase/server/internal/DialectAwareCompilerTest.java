package io.github.jackbaozz.pocketbase.server.internal;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DialectAwareCompilerTest {

    @Test
    void testJsonExtractMySql() {
        var result = FilterToSqlCompiler.compileBound(
                "@request.auth.id = '123'",
                id -> "`" + id + "`",
                null,
                JooqDatabase.Engine.MYSQL
        );
        assertTrue(result.sql().contains("JSON_EXTRACT(:request_auth, '$.id')"));
    }

    @Test
    void testJsonExtractPostgres() {
        var result = FilterToSqlCompiler.compileBound(
                "@request.auth.id = '123'",
                id -> "\"" + id + "\"",
                null,
                JooqDatabase.Engine.POSTGRES
        );
        assertTrue(result.sql().contains(":request_auth ->> 'id'"));
    }

    @Test
    void testJsonExtractSqlite() {
        var result = FilterToSqlCompiler.compileBound(
                "@request.auth.id = '123'",
                id -> "\"" + id + "\"",
                null,
                JooqDatabase.Engine.SQLITE
        );
        assertTrue(result.sql().contains("json_extract(:request_auth, '$.id')"));
    }

    @Test
    void testLikeMySql() {
        var result = FilterToSqlCompiler.compileBound(
                "name ~ 'john'",
                id -> "`" + id + "`",
                null,
                JooqDatabase.Engine.MYSQL
        );
        assertTrue(result.sql().contains("CONCAT('%', replace"));
    }

    @Test
    void testLikePostgres() {
        var result = FilterToSqlCompiler.compileBound(
                "name ~ 'john'",
                id -> "\"" + id + "\"",
                null,
                JooqDatabase.Engine.POSTGRES
        );
        assertTrue(result.sql().contains("'%' || replace"));
    }
}
