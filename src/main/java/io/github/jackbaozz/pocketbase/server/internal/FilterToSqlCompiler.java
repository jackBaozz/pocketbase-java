package io.github.jackbaozz.pocketbase.server.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class FilterToSqlCompiler {

    private FilterToSqlCompiler() {}

    public record CompiledFilter(String sql, List<Object> bindings) {
    }

    public interface ContainsRenderer {
        CompiledFilter render(CompiledFilter left, CompiledFilter right, boolean negated);
    }

    public static String compile(String filter) {
        return compileInternal(filter, FilterToSqlCompiler::standardQuoteIdentifier, false, null, JooqDatabase.Engine.SQLITE).sql();
    }

    public static CompiledFilter compileBound(String filter, Function<String, String> quoteIdentifier) {
        return compileBound(filter, quoteIdentifier, null);
    }

    public static CompiledFilter compileBound(String filter, Function<String, String> quoteIdentifier, ContainsRenderer containsRenderer) {
        return compileBound(filter, quoteIdentifier, containsRenderer, JooqDatabase.Engine.SQLITE);
    }

    public static CompiledFilter compileBound(String filter, Function<String, String> quoteIdentifier, ContainsRenderer containsRenderer, JooqDatabase.Engine engine) {
        return compileInternal(filter, quoteIdentifier, true, containsRenderer, engine);
    }

    private static CompiledFilter compileInternal(String filter, Function<String, String> quoteIdentifier, boolean bindLiterals) {
        return compileInternal(filter, quoteIdentifier, bindLiterals, null, JooqDatabase.Engine.SQLITE);
    }

    private static CompiledFilter compileInternal(String filter, Function<String, String> quoteIdentifier, boolean bindLiterals, ContainsRenderer containsRenderer, JooqDatabase.Engine engine) {
        if (filter == null || filter.isBlank()) {
            return new CompiledFilter("1=1", List.of());
        }
        List<Token> tokens = tokenize(filter);
        Parser parser = new Parser(tokens, quoteIdentifier, bindLiterals, containsRenderer, engine);
        SqlPart part = parser.parseExpression();
        parser.requireEnd();
        return new CompiledFilter(part.sql(), List.copyOf(part.bindings()));
    }

    private static String standardQuoteIdentifier(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    private record SqlPart(String sql, List<Object> bindings) {
        static SqlPart of(String sql) {
            return new SqlPart(sql, List.of());
        }

        static SqlPart bind(Object value) {
            return new SqlPart("?", List.of(value));
        }

        SqlPart wrap() {
            return new SqlPart("(" + sql + ")", bindings);
        }

        static SqlPart join(SqlPart left, String operator, SqlPart right) {
            List<Object> bindings = new ArrayList<>(left.bindings);
            bindings.addAll(right.bindings);
            return new SqlPart("(" + left.sql + " " + operator + " " + right.sql + ")", bindings);
        }
    }

    private enum TokenType {
        IDENTIFIER, NUMBER, STRING, OPERATOR, LPAREN, RPAREN, EOF
    }

    private static class Token {
        TokenType type;
        String value;

        Token(TokenType type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int len = input.length();

        while (i < len) {
            char c = input.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (c == '(') {
                tokens.add(new Token(TokenType.LPAREN, "("));
                i++;
                continue;
            }
            if (c == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")"));
                i++;
                continue;
            }

            if (c == '\'' || c == '"') {
                char quote = c;
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < len && input.charAt(i) != quote) {
                    if (input.charAt(i) == '\\' && i + 1 < len) {
                        i++;
                    }
                    sb.append(input.charAt(i));
                    i++;
                }
                if (i < len) i++;
                tokens.add(new Token(TokenType.STRING, sb.toString()));
                continue;
            }

            if (Character.isDigit(c) || c == '-' || c == '+') {
                StringBuilder sb = new StringBuilder();
                if (c == '-' || c == '+') {
                    sb.append(c);
                    i++;
                }
                boolean hasDot = false;
                while (i < len && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) {
                    if (input.charAt(i) == '.') {
                        if (hasDot) break;
                        hasDot = true;
                    }
                    sb.append(input.charAt(i));
                    i++;
                }
                if (sb.length() > 0 && !sb.toString().equals("-") && !sb.toString().equals("+")) {
                    tokens.add(new Token(TokenType.NUMBER, sb.toString()));
                    continue;
                } else {
                    i -= sb.length();
                }
            }

            if (c == '?' && i + 1 < len && (input.charAt(i + 1) == '=' || input.charAt(i + 1) == '!' || input.charAt(i + 1) == '>' || input.charAt(i + 1) == '<' || input.charAt(i + 1) == '~')) {
                StringBuilder sb = new StringBuilder();
                sb.append(c);
                i++;
                sb.append(input.charAt(i));
                i++;
                if (i < len && (input.charAt(i) == '=' || input.charAt(i) == '~')) {
                    sb.append(input.charAt(i));
                    i++;
                }
                tokens.add(new Token(TokenType.OPERATOR, sb.toString()));
                continue;
            }

            if (c == '=' || c == '!' || c == '>' || c == '<' || c == '~') {
                StringBuilder sb = new StringBuilder();
                sb.append(c);
                i++;
                if (i < len && (input.charAt(i) == '=' || input.charAt(i) == '~')) {
                    sb.append(input.charAt(i));
                    i++;
                }
                tokens.add(new Token(TokenType.OPERATOR, sb.toString()));
                continue;
            }

            if (Character.isLetter(c) || c == '_' || c == '@') {
                StringBuilder sb = new StringBuilder();
                while (i < len && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_' || input.charAt(i) == '.' || input.charAt(i) == '@')) {
                    sb.append(input.charAt(i));
                    i++;
                }
                String val = sb.toString();
                if (val.equalsIgnoreCase("true") || val.equalsIgnoreCase("false") || val.equalsIgnoreCase("null")) {
                    tokens.add(new Token(TokenType.IDENTIFIER, val.toLowerCase()));
                } else if (val.equalsIgnoreCase("and") || val.equalsIgnoreCase("or")) {
                    tokens.add(new Token(TokenType.OPERATOR, val.toUpperCase()));
                } else {
                    tokens.add(new Token(TokenType.IDENTIFIER, val));
                }
                continue;
            }

            throw invalidFilter("Invalid filter syntax near character: " + c);
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    private static class Parser {
        private final List<Token> tokens;
        private final Function<String, String> quoteIdentifier;
        private final boolean bindLiterals;
        private final ContainsRenderer containsRenderer;
        private final JooqDatabase.Engine engine;
        private int pos = 0;

        Parser(List<Token> tokens, Function<String, String> quoteIdentifier, boolean bindLiterals, ContainsRenderer containsRenderer, JooqDatabase.Engine engine) {
            this.tokens = tokens;
            this.quoteIdentifier = quoteIdentifier;
            this.bindLiterals = bindLiterals;
            this.containsRenderer = containsRenderer;
            this.engine = engine;
        }

        private Token peek() {
            if (pos < tokens.size()) return tokens.get(pos);
            return tokens.get(tokens.size() - 1);
        }

        private Token consume() {
            Token t = peek();
            if (t.type != TokenType.EOF) pos++;
            return t;
        }

        private boolean match(TokenType type) {
            if (peek().type == type) {
                consume();
                return true;
            }
            return false;
        }

        SqlPart parseExpression() {
            return parseOr();
        }

        private void requireEnd() {
            if (peek().type != TokenType.EOF) {
                throw invalidFilter("Unexpected token in filter: " + peek().value);
            }
        }

        private SqlPart parseOr() {
            SqlPart left = parseAnd();
            while (peek().type == TokenType.OPERATOR && peek().value.equals("OR")) {
                consume();
                SqlPart right = parseAnd();
                left = SqlPart.join(left, "OR", right);
            }
            return left;
        }

        private SqlPart parseAnd() {
            SqlPart left = parseCondition();
            while (peek().type == TokenType.OPERATOR && peek().value.equals("AND")) {
                consume();
                SqlPart right = parseCondition();
                left = SqlPart.join(left, "AND", right);
            }
            return left;
        }

        private static class Operand {
            final SqlPart sql;
            final boolean isCollection;
            final String collectionName;
            final String fieldName;

            Operand(SqlPart sql, boolean isCollection, String collectionName, String fieldName) {
                this.sql = sql;
                this.isCollection = isCollection;
                this.collectionName = collectionName;
                this.fieldName = fieldName;
            }
        }

        private SqlPart parseCondition() {
            if (match(TokenType.LPAREN)) {
                SqlPart expr = parseExpression();
                if (!match(TokenType.RPAREN)) {
                    throw invalidFilter("Missing closing parenthesis in filter.");
                }
                return expr.wrap();
            }

            Operand left = parseOperand();
            if (peek().type == TokenType.OPERATOR) {
                String op = consume().value;
                Operand right = parseOperand();
                return buildSqlCondition(left, op, right);
            }
            return left.sql;
        }

        private Operand parseOperand() {
            Token t = consume();
            if (t.type == TokenType.STRING) {
                return new Operand(literal(t.value), false, null, null);
            }
            if (t.type == TokenType.NUMBER) {
                return new Operand(literal(parseNumber(t.value)), false, null, null);
            }
            if (t.type == TokenType.IDENTIFIER) {
                if (t.value.equalsIgnoreCase("null") || t.value.equalsIgnoreCase("true") || t.value.equalsIgnoreCase("false")) {
                    return new Operand(keywordLiteral(t.value), false, null, null);
                }
                if (t.value.startsWith("@request.auth.")) {
                    return new Operand(SqlPart.of(dialectJsonExtract(":request_auth", t.value.substring("@request.auth.".length()))), false, null, null);
                }
                if (t.value.startsWith("@request.body.")) {
                    return new Operand(SqlPart.of(dialectJsonExtract(":request_body", t.value.substring("@request.body.".length()))), false, null, null);
                }
                if (t.value.startsWith("@request.query.")) {
                    return new Operand(SqlPart.of(dialectJsonExtract(":request_query", t.value.substring("@request.query.".length()))), false, null, null);
                }
                if (t.value.equals("@request.method")) {
                    return new Operand(SqlPart.of(":request_method"), false, null, null);
                }
                if (t.value.startsWith("@collection.")) {
                    String[] parts = t.value.split("\\.");
                    if (parts.length >= 3) {
                        String colName = parts[1];
                        String fieldName = t.value.substring(("@collection." + colName + ".").length());
                        return new Operand(SqlPart.of(t.value), true, colName, fieldName);
                    }
                }
                return new Operand(SqlPart.of(escapeIdentifier(t.value)), false, null, null);
            }
            throw invalidFilter("Unexpected token in filter: " + t.value);
        }

        private String dialectJsonExtract(String column, String path) {
            return switch (engine) {
                case MYSQL -> "JSON_EXTRACT(" + column + ", '$." + path + "')";
                case POSTGRES -> column + " ->> '" + path + "'";
                default -> "json_extract(" + column + ", '$." + path + "')"; // SQLITE
            };
        }

        private SqlPart literal(Object value) {
            if (bindLiterals) {
                return SqlPart.bind(value);
            }
            if (value instanceof Number) {
                return SqlPart.of(value.toString());
            }
            return SqlPart.of("'" + value.toString().replace("'", "''") + "'");
        }

        private SqlPart keywordLiteral(String value) {
            return switch (value.toLowerCase()) {
                case "null" -> SqlPart.of("NULL");
                case "true" -> bindLiterals ? SqlPart.bind(true) : SqlPart.of("TRUE");
                case "false" -> bindLiterals ? SqlPart.bind(false) : SqlPart.of("FALSE");
                default -> throw invalidFilter("Unexpected keyword: " + value);
            };
        }

        private Number parseNumber(String value) {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Long.parseLong(value);
        }

        private String escapeIdentifier(String id) {
            return quoteIdentifier.apply(id);
        }

        private SqlPart buildSqlCondition(Operand left, String op, Operand right) {
            String baseOp = op.startsWith("?") ? op.substring(1) : op;

            if (left.isCollection) {
                SqlPart collectionField = SqlPart.of(escapeIdentifier(left.collectionName) + "." + escapeIdentifier(left.fieldName));
                SqlPart comparison = buildComparison(collectionField, baseOp, right.sql);
                String subquery = "SELECT 1 FROM " + escapeIdentifier(left.collectionName) +
                                  " WHERE " + comparison.sql();
                return new SqlPart("EXISTS (" + subquery + ")", comparison.bindings());
            }
            if (right.isCollection) {
                SqlPart collectionField = SqlPart.of(escapeIdentifier(right.collectionName) + "." + escapeIdentifier(right.fieldName));
                SqlPart comparison = buildComparison(collectionField, invertOp(baseOp), left.sql);
                String subquery = "SELECT 1 FROM " + escapeIdentifier(right.collectionName) +
                                  " WHERE " + comparison.sql();
                return new SqlPart("EXISTS (" + subquery + ")", comparison.bindings());
            }
            return buildComparison(left.sql, baseOp, right.sql);
        }

        private SqlPart buildComparison(SqlPart left, String op, SqlPart right) {
            if ("~".equals(op) || "!~".equals(op)) {
                return renderContains(left, right, "!~".equals(op));
            }
            SqlPart mapped = mapOpToSql(op, right);
            List<Object> bindings = new ArrayList<>(left.bindings());
            bindings.addAll(mapped.bindings());
            return new SqlPart(left.sql() + " " + mapped.sql(), bindings);
        }

        private SqlPart renderContains(SqlPart left, SqlPart right, boolean negated) {
            if (containsRenderer != null) {
                CompiledFilter rendered = containsRenderer.render(
                        new CompiledFilter(left.sql(), left.bindings()),
                        new CompiledFilter(right.sql(), right.bindings()),
                        negated
                );
                return new SqlPart(rendered.sql(), rendered.bindings());
            }
            SqlPart mapped;
            if (engine == JooqDatabase.Engine.MYSQL) {
                mapped = new SqlPart(
                        (negated ? "NOT LIKE " : "LIKE ")
                                + "CONCAT('%', replace(replace(replace(" + right.sql() + ", '\\\\', '\\\\\\\\'), '%', '\\\\%'), '_', '\\\\_'), '%') ESCAPE '\\\\'",
                        right.bindings()
                );
            } else {
                mapped = new SqlPart(
                        (negated ? "NOT LIKE " : "LIKE ")
                                + "'%' || "
                                + "replace(replace(replace(" + right.sql() + ", '\\', '\\\\'), '%', '\\%'), '_', '\\_')"
                                + " || '%' ESCAPE '\\'",
                        right.bindings()
                );
            }
            List<Object> bindings = new ArrayList<>(left.bindings());
            bindings.addAll(mapped.bindings());
            return new SqlPart(left.sql() + " " + mapped.sql(), bindings);
        }

        private String invertOp(String op) {
            return switch (op) {
                case ">" -> "<";
                case "<" -> ">";
                case ">=" -> "<=";
                case "<=" -> ">=";
                default -> op;
            };
        }

        private SqlPart mapOpToSql(String op, SqlPart rightSql) {
            if ("NULL".equals(rightSql.sql()) && rightSql.bindings().isEmpty()) {
                return switch (op) {
                    case "=", "==" -> new SqlPart("IS NULL", List.of());
                    case "!=" -> new SqlPart("IS NOT NULL", List.of());
                    default -> throw invalidFilter("Unsupported null comparison operator: " + op);
                };
            }
            return switch (op) {
                case "=", "==" -> new SqlPart("= " + rightSql.sql(), rightSql.bindings());
                case "!=" -> new SqlPart("!= " + rightSql.sql(), rightSql.bindings());
                case ">" -> new SqlPart("> " + rightSql.sql(), rightSql.bindings());
                case ">=" -> new SqlPart(">= " + rightSql.sql(), rightSql.bindings());
                case "<" -> new SqlPart("< " + rightSql.sql(), rightSql.bindings());
                case "<=" -> new SqlPart("<= " + rightSql.sql(), rightSql.bindings());
                default -> throw invalidFilter("Unsupported operator: " + op);
            };
        }
    }

    private static ApiException invalidFilter(String message) {
        return new ApiException(400, "Invalid filter.", ApiErrors.invalidField("filter", message));
    }
}
