package io.github.jackbaozz.pocketbase.server.internal.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.internal.ApiErrors;
import io.github.jackbaozz.pocketbase.server.internal.ApiException;
import io.github.jackbaozz.pocketbase.server.internal.JooqDatabase;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public abstract class BaseRepository {
    protected static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    protected static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");
    private static final Pattern SQLITE_UNIQUE_FIELD = Pattern.compile("([A-Za-z0-9_]+)\\.([A-Za-z0-9_]+)");
    private static final Pattern POSTGRES_UNIQUE_DETAIL = Pattern.compile("Key \\(([^)]+)\\)=\\(([^)]*)\\) already exists", Pattern.CASE_INSENSITIVE);
    private static final Pattern MYSQL_DUPLICATE_KEY = Pattern.compile("Duplicate entry .* for key ['`]?([^'`]+)['`]?", Pattern.CASE_INSENSITIVE);
    
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
        handleSqlConstraintException(e, ApiErrors.MESSAGE_VALUE_MUST_BE_UNIQUE);
    }

    protected void handleSqlConstraintException(Throwable e, String message) {
        if (e == null) {
            return;
        }
        Throwable current = e;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                ApiException apiException = uniqueConstraintException(sqlException, message);
                if (apiException != null) {
                    throw apiException;
                }
            } else if (current instanceof org.jooq.exception.DataAccessException dataAccessException) {
                ApiException apiException = uniqueConstraintException(dataAccessException, message);
                if (apiException != null) {
                    throw apiException;
                }
            }
            current = current.getCause();
        }
    }

    private ApiException uniqueConstraintException(Throwable throwable, String apiMessage) {
        String exceptionMessage = throwable.getMessage();
        if (exceptionMessage == null || exceptionMessage.isBlank()) {
            return null;
        }
        if (isUniqueViolation(throwable, exceptionMessage)) {
            Map<String, Object> data = uniqueConstraintData(exceptionMessage);
            return new ApiException(400, apiMessage, data);
        }
        return null;
    }

    private boolean isUniqueViolation(Throwable throwable, String message) {
        String lowered = message.toLowerCase();
        if (lowered.contains("unique constraint failed")
                || lowered.contains("duplicate key value violates unique constraint")
                || lowered.contains("duplicate entry")) {
            return true;
        }
        if (throwable instanceof SQLException sqlException) {
            return "23505".equals(sqlException.getSQLState()) || sqlException.getErrorCode() == 1062;
        }
        return false;
    }

    private Map<String, Object> uniqueConstraintData(String message) {
        List<String> fields = sqliteFields(message);
        if (fields.isEmpty()) {
            fields = postgresFields(message);
        }
        if (fields.isEmpty()) {
            fields = mysqlFields(message);
        }
        if (fields.isEmpty()) {
            fields = List.of("unknown");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        for (String field : fields) {
            data.put(field, ApiErrors.validationError("validation_not_unique", ApiErrors.MESSAGE_VALUE_MUST_BE_UNIQUE));
        }
        return data;
    }

    private List<String> sqliteFields(String message) {
        if (!message.contains("UNIQUE constraint failed")) {
            return List.of();
        }
        Matcher matcher = SQLITE_UNIQUE_FIELD.matcher(message);
        java.util.ArrayList<String> fields = new java.util.ArrayList<>();
        while (matcher.find()) {
            fields.add(matcher.group(2));
        }
        return fields;
    }

    private List<String> postgresFields(String message) {
        Matcher matcher = POSTGRES_UNIQUE_DETAIL.matcher(message);
        if (!matcher.find()) {
            return List.of();
        }
        return java.util.Arrays.stream(matcher.group(1).split(","))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
    }

    private List<String> mysqlFields(String message) {
        Matcher matcher = MYSQL_DUPLICATE_KEY.matcher(message);
        if (!matcher.find()) {
            return List.of();
        }
        String key = matcher.group(1).trim();
        if (key.isBlank()) {
            return List.of();
        }
        if (key.contains(".")) {
            return List.of(key.substring(key.lastIndexOf('.') + 1));
        }
        if (key.startsWith("uk__")) {
            String suffix = key.substring("uk__".length());
            if (suffix.contains("_")) {
                return List.of(suffix.substring(suffix.lastIndexOf('_') + 1));
            }
            return List.of(suffix);
        }
        return List.of(key);
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
