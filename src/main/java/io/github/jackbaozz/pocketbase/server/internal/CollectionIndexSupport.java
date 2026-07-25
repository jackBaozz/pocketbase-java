package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses, validates, and normalizes PocketBase collection CREATE INDEX definitions.
 */
public final class CollectionIndexSupport {
    private static final Pattern INDEX_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(unique\\s+)?index\\s+(if\\s+not\\s+exists\\s+)?(\\S+)\\s+on\\s+(\\S+)\\s*\\((.*)\\)\\s*(?:where\\s+(.+))?\\s*$"
    );
    private static final Pattern COLUMN_PATTERN = Pattern.compile(
            "(?is)^(.+?)(?:\\s+collate\\s+([a-zA-Z0-9_]+))?(?:\\s+(asc|desc))?$"
    );
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern EXPRESSION_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> SQL_WORDS = Set.of(
            "and", "or", "not", "null", "is", "in", "like", "glob", "between", "collate",
            "asc", "desc", "true", "false", "case", "when", "then", "else", "end", "cast",
            "as", "distinct", "text", "integer", "real", "numeric", "blob", "date", "datetime", "json"
    );

    private CollectionIndexSupport() {
    }

    public static List<String> normalize(
            CollectionSchema collection,
            Collection<String> externalIndexNames,
            String message
    ) {
        List<String> rawIndexes = collection.indexes == null ? List.of() : collection.indexes;
        if ("view".equals(collection.type) && !rawIndexes.isEmpty()) {
            throw new ApiException(400, message, ApiErrors.fieldError(
                    "indexes",
                    "validation_indexes_not_supported",
                    "View collections don't support indexes."
            ));
        }

        Set<String> knownFields = new HashSet<>(List.of("id", "created", "updated"));
        for (FieldSchema field : collection.fields == null ? List.<FieldSchema>of() : collection.fields) {
            if (field != null && field.name != null) {
                knownFields.add(field.name.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> externalNames = new HashSet<>();
        for (String name : externalIndexNames == null ? List.<String>of() : externalIndexNames) {
            if (name != null) {
                externalNames.add(name.toLowerCase(Locale.ROOT));
            }
        }

        Set<String> names = new HashSet<>();
        Set<String> definitions = new HashSet<>();
        List<String> normalized = new ArrayList<>();
        for (int i = 0; i < rawIndexes.size(); i++) {
            ParsedIndex parsed = parse(rawIndexes.get(i));
            if (parsed == null) {
                throw indexError(message, i, "validation_invalid_index_expression", "Invalid CREATE INDEX expression.");
            }
            String lowerName = parsed.name().toLowerCase(Locale.ROOT);
            if (!names.add(lowerName)) {
                throw indexError(message, i, "validation_duplicated_index_name", "The index name already exists.");
            }
            if (externalNames.contains(lowerName)) {
                throw indexError(message, i, "validation_existing_index_name", "The index name is already used in another collection.");
            }
            for (IndexColumn column : parsed.columns()) {
                if (!expressionUsesKnownFields(column.expression(), knownFields)) {
                    throw indexError(message, i, "validation_invalid_index_expression", "Invalid CREATE INDEX expression.");
                }
            }
            if (!parsed.where().isBlank() && !expressionUsesKnownFields(parsed.where(), knownFields)) {
                throw indexError(message, i, "validation_invalid_index_expression", "Invalid CREATE INDEX expression.");
            }
            String definition = definitionKey(parsed);
            if (!definitions.add(definition)) {
                throw indexError(message, i, "validation_duplicated_index_definition", "The index definition already exists.");
            }
            normalized.add(build(parsed, collection.name, CollectionIndexSupport::backtick));
        }
        return normalized;
    }

    public static String indexName(String raw) {
        ParsedIndex parsed = parse(raw);
        return parsed == null ? "" : parsed.name();
    }

    public static boolean isSingleColumnUnique(String raw, String columnName) {
        ParsedIndex parsed = parse(raw);
        if (parsed == null || !parsed.unique() || parsed.columns().size() != 1) {
            return false;
        }
        String parsedColumn = simpleColumnName(parsed.columns().get(0).expression());
        return parsedColumn != null && parsedColumn.equalsIgnoreCase(columnName);
    }

    public static String createSql(String raw, String table, Function<String, String> quoteIdentifier) {
        ParsedIndex parsed = parse(raw);
        return parsed == null ? "" : build(parsed, table, quoteIdentifier);
    }

    /**
     * Produces executable DDL for a specific relational engine while retaining
     * the unmodified PocketBase index definition in collection metadata.
     */
    public static String createSql(
            String raw,
            String table,
            Function<String, String> quoteIdentifier,
            JooqDatabase.Engine engine,
            List<FieldSchema> fields
    ) {
        ParsedIndex parsed = parse(raw);
        return parsed == null ? "" : build(parsed, table, quoteIdentifier, engine, fields);
    }

    public static String normalizeDefinition(String raw, String table) {
        ParsedIndex parsed = parse(raw);
        return parsed == null ? "" : build(parsed, table, CollectionIndexSupport::backtick);
    }

    public static String dropSql(
            String raw,
            String table,
            JooqDatabase.Engine engine,
            Function<String, String> quoteIdentifier
    ) {
        String name = indexName(raw);
        if (name.isBlank()) {
            return "";
        }
        String sql = "DROP INDEX " + quoteIdentifier.apply(name);
        return engine == JooqDatabase.Engine.MYSQL ? sql + " ON " + quoteIdentifier.apply(table) : sql;
    }

    private static ParsedIndex parse(String raw) {
        Matcher matcher = INDEX_PATTERN.matcher(raw == null ? "" : raw);
        if (!matcher.matches()) {
            return null;
        }
        String name = unquote(lastIdentifierPart(matcher.group(3)));
        String table = unquote(lastIdentifierPart(matcher.group(4)));
        if (name.isBlank() || table.isBlank()) {
            return null;
        }
        List<String> rawColumns = splitColumns(matcher.group(5));
        List<IndexColumn> columns = new ArrayList<>();
        for (String rawColumn : rawColumns) {
            Matcher columnMatcher = COLUMN_PATTERN.matcher(rawColumn.trim());
            if (!columnMatcher.matches()) {
                return null;
            }
            String expression = columnMatcher.group(1).trim();
            if (expression.isBlank()) {
                return null;
            }
            columns.add(new IndexColumn(
                    expression,
                    text(columnMatcher.group(2)),
                    text(columnMatcher.group(3)).toUpperCase(Locale.ROOT)
            ));
        }
        if (columns.isEmpty()) {
            return null;
        }
        return new ParsedIndex(
                matcher.group(1) != null,
                matcher.group(2) != null,
                name,
                table,
                columns,
                text(matcher.group(6)).trim()
        );
    }

    private static String build(ParsedIndex index, String table, Function<String, String> quoteIdentifier) {
        return build(index, table, quoteIdentifier, null, List.of());
    }

    private static String build(
            ParsedIndex index,
            String table,
            Function<String, String> quoteIdentifier,
            JooqDatabase.Engine engine,
            List<FieldSchema> fields
    ) {
        StringBuilder sql = new StringBuilder("CREATE ");
        if (index.unique()) {
            sql.append("UNIQUE ");
        }
        sql.append("INDEX ");
        if (index.optional()) {
            sql.append("IF NOT EXISTS ");
        }
        sql.append(quoteIdentifier.apply(index.name()))
                .append(" ON ")
                .append(quoteIdentifier.apply(table))
                .append(" (");
        Map<String, Integer> mysqlPrefixes = engine == JooqDatabase.Engine.MYSQL
                ? mysqlPrefixLengths(index, fields)
                : Map.of();
        String mysqlPartialCondition = engine == JooqDatabase.Engine.MYSQL && !index.where().isBlank()
                ? rewriteQuotedIdentifiers(index.where(), quoteIdentifier)
                : "";
        List<String> columns = new ArrayList<>();
        for (IndexColumn column : index.columns()) {
            String expression = engine == JooqDatabase.Engine.MYSQL
                    ? mysqlIndexExpression(index, column, quoteIdentifier, mysqlPrefixes, mysqlPartialCondition)
                    : normalizedColumnExpression(column.expression(), quoteIdentifier);
            if (!column.collate().isBlank()) {
                expression += " COLLATE " + column.collate();
            }
            if (!column.sort().isBlank()) {
                expression += " " + column.sort();
            }
            columns.add(expression);
        }
        sql.append(String.join(", ", columns)).append(")");
        if (!index.where().isBlank() && engine != JooqDatabase.Engine.MYSQL) {
            sql.append(" WHERE ").append(rewriteQuotedIdentifiers(index.where(), quoteIdentifier));
        }
        return sql.toString();
    }

    private static String mysqlIndexExpression(
            ParsedIndex index,
            IndexColumn column,
            Function<String, String> quoteIdentifier,
            Map<String, Integer> prefixes,
            String partialCondition
    ) {
        String name = simpleColumnName(column.expression());
        Integer prefix = name == null ? null : prefixes.get(name.toLowerCase(Locale.ROOT));
        String expression = prefix == null
                ? normalizedColumnExpression(column.expression(), quoteIdentifier)
                : quoteIdentifier.apply(name) + "(" + prefix + ")";

        if (!partialCondition.isBlank()) {
            // MySQL only permits the `column(prefix)` form in a key part, not
            // inside the CASE expression used to emulate a partial index. A
            // fixed-size SHA-256 key is valid in either context and avoids
            // truncating unique values.
            if (prefix != null) {
                expression = mysqlHashExpression(name, quoteIdentifier);
            }
            return mysqlFunctionalKeyPart("CASE WHEN " + partialCondition + " THEN " + expression + " ELSE NULL END");
        }

        if (prefix == null || !index.unique()) {
            return expression;
        }

        // A UNIQUE prefix index changes the constraint to "first N
        // characters are unique", rejecting otherwise distinct values. Use
        // a compact deterministic hash key instead so the complete value
        // remains part of the uniqueness check without exceeding InnoDB's
        // key-size limit.
        return mysqlFunctionalKeyPart(mysqlHashExpression(name, quoteIdentifier));
    }

    private static String mysqlHashExpression(String name, Function<String, String> quoteIdentifier) {
        return "UNHEX(SHA2(" + quoteIdentifier.apply(name) + ", 256))";
    }

    private static String mysqlFunctionalKeyPart(String expression) {
        return "(" + expression + ")";
    }

    private static Map<String, Integer> mysqlPrefixLengths(ParsedIndex index, List<FieldSchema> fields) {
        Map<String, FieldSchema> fieldsByName = new LinkedHashMap<>();
        for (FieldSchema field : fields == null ? List.<FieldSchema>of() : fields) {
            if (field != null && field.name != null && !field.name.isBlank()) {
                fieldsByName.put(field.name.toLowerCase(Locale.ROOT), field);
            }
        }

        List<MysqlStringColumn> strings = new ArrayList<>();
        for (IndexColumn column : index.columns()) {
            String name = simpleColumnName(column.expression());
            if (name == null) {
                continue;
            }
            MysqlStringColumn stringColumn = mysqlStringColumn(name, fieldsByName.get(name.toLowerCase(Locale.ROOT)));
            if (stringColumn != null) {
                strings.add(stringColumn);
            }
        }
        if (strings.isEmpty()) {
            return Map.of();
        }

        int totalCharacters = strings.stream().mapToInt(MysqlStringColumn::maxCharacters).sum();
        boolean needsPrefix = totalCharacters > 768 || strings.stream().anyMatch(MysqlStringColumn::forcePrefix);
        if (!needsPrefix) {
            return Map.of();
        }

        strings.sort(Comparator.comparingInt(MysqlStringColumn::maxCharacters));
        int remainingCharacters = 768;
        Map<String, Integer> prefixes = new LinkedHashMap<>();
        for (int i = 0; i < strings.size(); i++) {
            MysqlStringColumn column = strings.get(i);
            int remainingColumns = strings.size() - i;
            int allocation = Math.min(column.maxCharacters(), Math.max(1, remainingCharacters / remainingColumns));
            if (column.forcePrefix() || allocation < column.maxCharacters()) {
                prefixes.put(column.name().toLowerCase(Locale.ROOT), allocation);
            }
            remainingCharacters -= allocation;
        }
        return prefixes;
    }

    private static MysqlStringColumn mysqlStringColumn(String name, FieldSchema field) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if ("id".equals(normalizedName)) {
            return new MysqlStringColumn(name, 255, false);
        }
        if ("created".equals(normalizedName) || "updated".equals(normalizedName)) {
            return new MysqlStringColumn(name, 64, false);
        }
        if (field == null || field.type == null) {
            return null;
        }

        String type = field.type.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "text", "editor" -> new MysqlStringColumn(
                    name,
                    "tokenKey".equals(field.name) && field.system ? 255 : 2000,
                    false
            );
            case "email", "password" -> new MysqlStringColumn(name, 255, false);
            case "url" -> new MysqlStringColumn(name, 2048, false);
            case "date", "autodate" -> new MysqlStringColumn(name, 64, false);
            case "select", "json", "file", "relation", "geopoint" -> new MysqlStringColumn(name, 768, true);
            default -> null;
        };
    }

    private static String normalizedColumnExpression(String raw, Function<String, String> quoteIdentifier) {
        String value = raw.trim();
        String unquoted = unquote(value);
        return SIMPLE_IDENTIFIER.matcher(unquoted).matches()
                ? quoteIdentifier.apply(unquoted)
                : rewriteQuotedIdentifiers(value, quoteIdentifier);
    }

    private static String rewriteQuotedIdentifiers(String expression, Function<String, String> quoteIdentifier) {
        String result = expression;
        for (Pattern pattern : List.of(
                Pattern.compile("`([^`]+)`"),
                Pattern.compile("\\[([^]]+)]"),
                Pattern.compile("\"([^\"]+)\"")
        )) {
            Matcher matcher = pattern.matcher(result);
            StringBuffer normalized = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(normalized, Matcher.quoteReplacement(quoteIdentifier.apply(matcher.group(1))));
            }
            matcher.appendTail(normalized);
            result = normalized.toString();
        }
        return result;
    }

    private static String simpleColumnName(String raw) {
        String value = unquote(raw.trim());
        return SIMPLE_IDENTIFIER.matcher(value).matches() ? value : null;
    }

    private static boolean expressionUsesKnownFields(String raw, Set<String> knownFields) {
        String expression = stripStringLiterals(raw == null ? "" : raw);
        Matcher matcher = EXPRESSION_IDENTIFIER.matcher(expression);
        while (matcher.find()) {
            String identifier = matcher.group().toLowerCase(Locale.ROOT);
            if (knownFields.contains(identifier) || SQL_WORDS.contains(identifier)) {
                continue;
            }
            int next = matcher.end();
            while (next < expression.length() && Character.isWhitespace(expression.charAt(next))) {
                next++;
            }
            if (next < expression.length() && expression.charAt(next) == '(') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static String stripStringLiterals(String value) {
        StringBuilder result = new StringBuilder(value.length());
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    if (i + 1 < value.length() && value.charAt(i + 1) == quote) {
                        result.append(' ').append(' ');
                        i++;
                        continue;
                    }
                    quote = 0;
                }
                result.append(' ');
                continue;
            }
            if (current == '\'') {
                quote = current;
                result.append(' ');
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static List<String> splitColumns(String value) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (quote != 0) {
                if (current == quote && (i == 0 || value.charAt(i - 1) != '\\')) {
                    quote = 0;
                }
                continue;
            }
            if (current == '`' || current == '"' || current == '\'') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ',' && depth == 0) {
                result.add(value.substring(start, i));
                start = i + 1;
            }
        }
        result.add(value.substring(start));
        return result;
    }

    private static String definitionKey(ParsedIndex index) {
        List<String> columns = new ArrayList<>();
        for (IndexColumn column : index.columns()) {
            columns.add((column.expression() + "|" + column.collate() + "|" + column.sort()).toLowerCase(Locale.ROOT));
        }
        return index.unique() + "|" + String.join(",", columns) + "|" + index.where().toLowerCase(Locale.ROOT);
    }

    private static ApiException indexError(String message, int index, String code, String errorMessage) {
        return new ApiException(400, message, Map.of(
                "indexes",
                Map.of(String.valueOf(index), ApiErrors.validationError(code, errorMessage))
        ));
    }

    private static String lastIdentifierPart(String value) {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static String unquote(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if (first == '`' && last == '`'
                    || first == '"' && last == '"'
                    || first == '[' && last == ']'
                    || first == '\'' && last == '\'') {
                result = result.substring(1, result.length() - 1);
            }
        }
        return result;
    }

    private static String backtick(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private record ParsedIndex(
            boolean unique,
            boolean optional,
            String name,
            String table,
            List<IndexColumn> columns,
            String where
    ) {
    }

    private record MysqlStringColumn(String name, int maxCharacters, boolean forcePrefix) {
    }

    private record IndexColumn(String expression, String collate, String sort) {
    }
}
