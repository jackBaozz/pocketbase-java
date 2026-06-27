package io.github.jackbaozz.pocketbase.server.internal;

import org.jooq.DataType;
import org.jooq.impl.SQLDataType;

/**
 * Maps PocketBase field types to appropriate SQL column types.
 *
 * <p>PocketBase's Go implementation uses a similar strategy: most scalar types
 * get a concrete SQL type, while compound types (select, relation, file, json)
 * are stored as text containing JSON arrays/objects. This class centralises
 * that mapping so that collection DDL and value normalisation stay consistent
 * across SQLite, MySQL, and PostgreSQL.</p>
 */
public final class FieldTypeMapping {

    private FieldTypeMapping() {}

    /**
     * Returns the jOOQ {@link DataType} that should be used for a field of the
     * given PocketBase type when creating or altering a table.
     */
    public static DataType<?> sqlType(String pocketBaseFieldType) {
        if (pocketBaseFieldType == null) return SQLDataType.CLOB;
        return switch (pocketBaseFieldType.toLowerCase().trim()) {
            case "text", "editor" -> SQLDataType.VARCHAR(2000);
            case "email" -> SQLDataType.VARCHAR(255);
            case "url" -> SQLDataType.VARCHAR(2048);
            case "password" -> SQLDataType.VARCHAR(255);
            case "number" -> SQLDataType.DECIMAL(20, 8);
            case "bool", "boolean" -> SQLDataType.BOOLEAN;
            case "date", "autodate" -> SQLDataType.VARCHAR(64);
            case "select" -> SQLDataType.CLOB;       // JSON array as text
            case "json" -> SQLDataType.CLOB;          // arbitrary JSON as text
            case "file" -> SQLDataType.CLOB;          // JSON array of filenames
            case "relation" -> SQLDataType.CLOB;      // JSON array of IDs
            case "geopoint" -> SQLDataType.CLOB;      // JSON object as text
            default -> SQLDataType.CLOB;              // fallback: store as text
        };
    }

    /**
     * Returns {@code true} if the PocketBase field type is stored as a JSON
     * string in the database (and therefore needs serialisation on write and
     * deserialisation on read).
     */
    public static boolean isJsonStoredType(String pocketBaseFieldType) {
        if (pocketBaseFieldType == null) return false;
        return switch (pocketBaseFieldType.toLowerCase().trim()) {
            case "select", "json", "file", "relation", "geopoint" -> true;
            default -> false;
        };
    }
}
