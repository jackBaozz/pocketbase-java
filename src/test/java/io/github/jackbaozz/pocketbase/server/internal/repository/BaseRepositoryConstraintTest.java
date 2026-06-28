package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseRepositoryConstraintTest {

    private final ProbeRepository repository = new ProbeRepository();

    @Test
    void normalizesSqliteUniqueConstraintErrors() {
        ApiException error = assertThrows(ApiException.class, () ->
                repository.probe(new SQLException("UNIQUE constraint failed: users.email")));

        assertEquals(400, error.status());
        assertEquals("validation_not_unique", field(error, "email", "code"));
    }

    @Test
    void normalizesPostgresUniqueConstraintErrors() {
        SQLException sql = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"users_email_key\"\n  Detail: Key (email)=(root@example.com) already exists.",
                "23505"
        );

        ApiException error = assertThrows(ApiException.class, () -> repository.probe(sql));

        assertEquals(400, error.status());
        assertEquals("validation_not_unique", field(error, "email", "code"));
    }

    @Test
    void normalizesMysqlDuplicateEntryErrors() {
        SQLException sql = new SQLException(
                "Duplicate entry 'root@example.com' for key 'users.email'",
                "23000",
                1062
        );

        ApiException error = assertThrows(ApiException.class, () -> repository.probe(sql));

        assertEquals(400, error.status());
        assertEquals("validation_not_unique", field(error, "email", "code"));
    }

    @SuppressWarnings("unchecked")
    private String field(ApiException error, String field, String key) {
        Map<String, Object> data = (Map<String, Object>) error.data();
        Map<String, Object> item = (Map<String, Object>) data.get(field);
        return String.valueOf(item.get(key));
    }

    private static final class ProbeRepository extends BaseRepository {
        private ProbeRepository() {
            super(null, new ObjectMapper());
        }

        private void probe(Throwable error) {
            handleSqlConstraintException(error);
        }
    }
}
