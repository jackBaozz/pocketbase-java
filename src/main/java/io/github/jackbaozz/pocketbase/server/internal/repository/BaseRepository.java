package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.regex.Pattern;

public abstract class BaseRepository {
    protected static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    protected static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");
    
    protected final JooqDatabase database;
    protected final ObjectMapper mapper;

    protected BaseRepository(JooqDatabase database, ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper;
    }

    protected void validateIdentifier(String identifier, String fieldName) {
        if (identifier == null || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new ApiException(400, "Invalid identifier.", java.util.Map.of(
                    fieldName,
                    java.util.Map.of("code", "validation_invalid_format", "message", "Use letters, numbers and underscore.")
            ));
        }
    }

    protected void handleSqlConstraintException(Throwable e) {
        if (e == null) {
            return;
        }
        String msg = null;
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.sql.SQLException || current instanceof org.jooq.exception.DataAccessException) {
                msg = current.getMessage();
                if (msg != null && msg.contains("UNIQUE constraint failed")) {
                    break;
                }
            }
            current = current.getCause();
        }
        if (msg != null && msg.contains("UNIQUE constraint failed")) {
            String field = "unknown";
            String[] parts = msg.split(":");
            if (parts.length > 1) {
                String[] fp = parts[1].trim().split("\\.");
                if (fp.length > 1) {
                    field = fp[1];
                } else if (fp.length == 1) {
                    field = fp[0];
                }
            }
            throw new ApiException(400, "Value must be unique.", java.util.Map.of(field, java.util.Map.of("code", "validation_not_unique", "message", "Value must be unique.")));
        }
    }

    protected void validateSqlIdentifier(String identifier) {
        if (identifier == null || !SQL_IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new ApiException(400, "Invalid identifier.");
        }
    }

    protected String qi(String identifier) {
        validateSqlIdentifier(identifier);
        return database.quoteIdentifier(identifier);
    }

    protected Table<?> qt(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.table(DSL.name(identifier));
    }

    protected Field<Object> qf(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.field(DSL.name(identifier));
    }

    protected Field<String> qfs(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.field(DSL.name(identifier), String.class);
    }

    protected Field<Integer> qfi(String identifier) {
        validateSqlIdentifier(identifier);
        return DSL.field(DSL.name(identifier), Integer.class);
    }
}
