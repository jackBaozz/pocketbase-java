package io.github.jackbaozz.pocketbase.server.internal;

import java.util.ArrayList;
import java.util.List;

public final class FilterToSqlCompiler {

    private FilterToSqlCompiler() {}

    public static String compile(String filter) {
        if (filter == null || filter.isBlank()) {
            return "1=1";
        }
        List<Token> tokens = tokenize(filter);
        Parser parser = new Parser(tokens);
        return parser.parseExpression();
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

            throw new ApiException(400, "Invalid filter syntax near character: " + c);
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    private static class Parser {
        private final List<Token> tokens;
        private int pos = 0;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
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

        String parseExpression() {
            return parseOr();
        }

        private String parseOr() {
            String left = parseAnd();
            while (peek().type == TokenType.OPERATOR && peek().value.equals("OR")) {
                consume();
                String right = parseAnd();
                left = "(" + left + " OR " + right + ")";
            }
            return left;
        }

        private String parseAnd() {
            String left = parseCondition();
            while (peek().type == TokenType.OPERATOR && peek().value.equals("AND")) {
                consume();
                String right = parseCondition();
                left = "(" + left + " AND " + right + ")";
            }
            return left;
        }

        private static class Operand {
            final String sql;
            final boolean isCollection;
            final String collectionName;
            final String fieldName;

            Operand(String sql, boolean isCollection, String collectionName, String fieldName) {
                this.sql = sql;
                this.isCollection = isCollection;
                this.collectionName = collectionName;
                this.fieldName = fieldName;
            }
        }

        private String parseCondition() {
            if (match(TokenType.LPAREN)) {
                String expr = parseExpression();
                if (!match(TokenType.RPAREN)) {
                    throw new ApiException(400, "Missing closing parenthesis in filter.");
                }
                return "(" + expr + ")";
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
                return new Operand("'" + t.value.replace("'", "''") + "'", false, null, null);
            }
            if (t.type == TokenType.NUMBER) {
                return new Operand(t.value, false, null, null);
            }
            if (t.type == TokenType.IDENTIFIER) {
                if (t.value.equalsIgnoreCase("null") || t.value.equalsIgnoreCase("true") || t.value.equalsIgnoreCase("false")) {
                    return new Operand(t.value.toUpperCase(), false, null, null);
                }
                if (t.value.startsWith("@request.auth.")) {
                    return new Operand("json_extract(:request_auth, '$." + t.value.substring("@request.auth.".length()) + "')", false, null, null);
                }
                if (t.value.startsWith("@request.body.")) {
                    return new Operand("json_extract(:request_body, '$." + t.value.substring("@request.body.".length()) + "')", false, null, null);
                }
                if (t.value.startsWith("@request.query.")) {
                    return new Operand("json_extract(:request_query, '$." + t.value.substring("@request.query.".length()) + "')", false, null, null);
                }
                if (t.value.equals("@request.method")) {
                    return new Operand(":request_method", false, null, null);
                }
                if (t.value.startsWith("@collection.")) {
                    String[] parts = t.value.split("\\.");
                    if (parts.length >= 3) {
                        String colName = parts[1];
                        String fieldName = t.value.substring(("@collection." + colName + ".").length());
                        return new Operand(t.value, true, colName, fieldName);
                    }
                }
                return new Operand(escapeIdentifier(t.value), false, null, null);
            }
            throw new ApiException(400, "Unexpected token in filter: " + t.value);
        }

        private String escapeIdentifier(String id) {
            return "\"" + id.replace("\"", "\"\"") + "\"";
        }

        private String buildSqlCondition(Operand left, String op, Operand right) {
            String baseOp = op.startsWith("?") ? op.substring(1) : op;

            if (left.isCollection) {
                String subquery = "SELECT 1 FROM " + escapeIdentifier(left.collectionName) +
                                  " WHERE " + escapeIdentifier(left.collectionName) + "." + escapeIdentifier(left.fieldName) +
                                  " " + mapOpToSql(baseOp, right.sql);
                return "EXISTS (" + subquery + ")";
            }
            if (right.isCollection) {
                String subquery = "SELECT 1 FROM " + escapeIdentifier(right.collectionName) +
                                  " WHERE " + escapeIdentifier(right.collectionName) + "." + escapeIdentifier(right.fieldName) +
                                  " " + mapOpToSql(invertOp(baseOp), left.sql);
                return "EXISTS (" + subquery + ")";
            }
            return left.sql + " " + mapOpToSql(baseOp, right.sql);
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

        private String mapOpToSql(String op, String rightSql) {
            return switch (op) {
                case "=", "==" -> "= " + rightSql;
                case "!=" -> "!= " + rightSql;
                case ">" -> "> " + rightSql;
                case ">=" -> ">= " + rightSql;
                case "<" -> "< " + rightSql;
                case "<=" -> "<= " + rightSql;
                case "~" -> "LIKE '%' || " + rightSql + " || '%'";
                case "!~" -> "NOT LIKE '%' || " + rightSql + " || '%'";
                default -> throw new ApiException(400, "Unsupported operator: " + op);
            };
        }
    }
}
