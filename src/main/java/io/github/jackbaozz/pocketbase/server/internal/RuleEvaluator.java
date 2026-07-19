package io.github.jackbaozz.pocketbase.server.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Small PocketBase filter/rule evaluator for the common server-side API rule subset.
 */
public final class RuleEvaluator {
    private RuleEvaluator() {
    }

    public static boolean matches(String expression, Context context) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        Parser parser = new Parser(tokenize(stripComments(expression)), context);
        return truthy(parser.parse());
    }

    public static void validate(String expression) {
        matches(expression, context(Map.of(), Map.of(), Map.of(), "GET", null));
    }

    public static List<String> identifiers(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        List<Token> tokens = tokenize(stripComments(expression));
        List<String> identifiers = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.IDENTIFIER
                    && (i + 1 >= tokens.size() || tokens.get(i + 1).type != TokenType.LPAREN)) {
                identifiers.add(token.value);
            }
        }
        return List.copyOf(identifiers);
    }

    public static Context context(
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            String method,
            RequestPrincipal principal
    ) {
        return context(record, body, query, method, principal, ignored -> List.of());
    }

    public static Context context(
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            String method,
            RequestPrincipal principal,
            Function<String, List<Map<String, Object>>> collectionResolver
    ) {
        return context(record, body, query, method, principal, collectionResolver, null);
    }

    public static Context context(
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            String method,
            RequestPrincipal principal,
            Function<String, List<Map<String, Object>>> collectionResolver,
            PathResolver pathResolver
    ) {
        return context(record, body, query, Map.of(), method, principal, collectionResolver, pathResolver);
    }

    public static Context context(
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            Map<String, String> headers,
            String method,
            RequestPrincipal principal,
            Function<String, List<Map<String, Object>>> collectionResolver,
            PathResolver pathResolver
    ) {
        return context(
                record,
                body,
                query,
                headers,
                RuleRequestContext.DEFAULT,
                method,
                principal,
                collectionResolver,
                pathResolver
        );
    }

    public static Context context(
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            Map<String, String> headers,
            String requestContext,
            String method,
            RequestPrincipal principal,
            Function<String, List<Map<String, Object>>> collectionResolver,
            PathResolver pathResolver
    ) {
        return new Context(record, body, query, headers, requestContext, method, principal, collectionResolver, pathResolver);
    }

    private static List<Token> tokenize(String expression) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < expression.length()) {
            char ch = expression.charAt(index);
            if (Character.isWhitespace(ch)) {
                index++;
                continue;
            }
            if (ch == '(') {
                tokens.add(new Token(TokenType.LPAREN, "("));
                index++;
                continue;
            }
            if (ch == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")"));
                index++;
                continue;
            }
            if (ch == ',') {
                tokens.add(new Token(TokenType.COMMA, ","));
                index++;
                continue;
            }
            if (index + 1 < expression.length()) {
                String two = expression.substring(index, index + 2);
                if ("&&".equals(two)) {
                    tokens.add(new Token(TokenType.AND, two));
                    index += 2;
                    continue;
                }
                if ("||".equals(two)) {
                    tokens.add(new Token(TokenType.OR, two));
                    index += 2;
                    continue;
                }
            }
            String operator = readOperator(expression, index);
            if (operator != null) {
                tokens.add(new Token(TokenType.OPERATOR, operator));
                index += operator.length();
                continue;
            }
            if (ch == '"' || ch == '\'') {
                StringBuilder out = new StringBuilder();
                char quote = ch;
                index++;
                while (index < expression.length()) {
                    char current = expression.charAt(index++);
                    if (current == quote) {
                        break;
                    }
                    if (current == '\\' && index < expression.length()) {
                        char escaped = expression.charAt(index++);
                        out.append(switch (escaped) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            default -> escaped;
                        });
                    } else {
                        out.append(current);
                    }
                }
                tokens.add(new Token(TokenType.STRING, out.toString()));
                continue;
            }
            if (Character.isDigit(ch) || ch == '-') {
                int start = index++;
                while (index < expression.length()) {
                    char current = expression.charAt(index);
                    if (!Character.isDigit(current) && current != '.') {
                        break;
                    }
                    index++;
                }
                tokens.add(new Token(TokenType.NUMBER, expression.substring(start, index)));
                continue;
            }

            int start = index++;
            while (index < expression.length()) {
                char current = expression.charAt(index);
                if (Character.isWhitespace(current) || current == '(' || current == ')' || current == ',' || startsOperator(expression, index)) {
                    break;
                }
                index++;
            }
            String value = expression.substring(start, index);
            tokens.add(switch (value) {
                case "true", "false" -> new Token(TokenType.BOOLEAN, value);
                case "null" -> new Token(TokenType.NULL, value);
                default -> new Token(TokenType.IDENTIFIER, value);
            });
        }
        tokens.add(new Token(TokenType.END, ""));
        return tokens;
    }

    private static String stripComments(String expression) {
        StringBuilder out = new StringBuilder();
        for (String line : expression.split("\\R", -1)) {
            int comment = line.indexOf("//");
            out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return out.toString();
    }

    private static boolean startsOperator(String expression, int index) {
        return readOperator(expression, index) != null
                || expression.startsWith("&&", index)
                || expression.startsWith("||", index);
    }

    private static String readOperator(String expression, int index) {
        String[] operators = {"?!=", "?>=", "?<=", "?!~", "?=", "?>", "?<", "?~", "!=", ">=", "<=", "!~", "==", "=", ">", "<", "~"};
        for (String operator : operators) {
            if (expression.startsWith(operator, index)) {
                return operator;
            }
        }
        return null;
    }

    private static boolean compare(Object left, String operator, Object right) {
        boolean any = operator.startsWith("?");
        String actualOperator = any ? operator.substring(1) : operator;
        if (left instanceof AnyMatchValues anyMatch) {
            return anyMatch.values().stream().anyMatch(value -> compareOne(value, actualOperator, right));
        }
        if (right instanceof AnyMatchValues anyMatch) {
            return anyMatch.values().stream().anyMatch(value -> compareOne(left, actualOperator, value));
        }
        if (left instanceof MultiMatchValues multiMatch) {
            if (multiMatch.values().isEmpty()) {
                return false;
            }
            return any
                    ? multiMatch.values().stream().anyMatch(value -> compareOne(value, actualOperator, right))
                    : multiMatch.values().stream().allMatch(value -> compareOne(value, actualOperator, right));
        }
        if (right instanceof MultiMatchValues multiMatch) {
            if (multiMatch.values().isEmpty()) {
                return false;
            }
            return any
                    ? multiMatch.values().stream().anyMatch(value -> compareOne(left, actualOperator, value))
                    : multiMatch.values().stream().allMatch(value -> compareOne(left, actualOperator, value));
        }
        if (any) {
            Collection<?> values = toCollection(left);
            if (values.isEmpty()) {
                return false;
            }
            for (Object value : values) {
                if (compareOne(value, actualOperator, right)) {
                    return true;
                }
            }
            return false;
        }
        return compareOne(left, actualOperator, right);
    }

    private static boolean compareOne(Object left, String operator, Object right) {
        if (left instanceof EachValues each) {
            return each.values().stream().anyMatch(item -> compareOne(item, operator, right));
        }
        if (right instanceof EachValues each) {
            return each.values().stream().anyMatch(item -> compareOne(left, operator, item));
        }
        return switch (operator) {
            case "=", "==" -> valuesEqual(left, right);
            case "!=" -> !valuesEqual(left, right);
            case ">" -> compareOrder(left, right) > 0;
            case ">=" -> compareOrder(left, right) >= 0;
            case "<" -> compareOrder(left, right) < 0;
            case "<=" -> compareOrder(left, right) <= 0;
            case "~" -> contains(left, right);
            case "!~" -> !contains(left, right);
            default -> false;
        };
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> valuesEqual(item, right));
        }
        if (right instanceof Collection<?> collection) {
            return collection.contains(left) || collection.stream().anyMatch(item -> valuesEqual(left, item));
        }
        Double leftNumber = asNumber(left);
        Double rightNumber = asNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber) == 0;
        }
        return Objects.equals(normalizeScalar(left), normalizeScalar(right));
    }

    private static int compareOrder(Object left, Object right) {
        Double leftNumber = asNumber(left);
        Double rightNumber = asNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber);
        }
        return String.valueOf(normalizeScalar(left)).compareTo(String.valueOf(normalizeScalar(right)));
    }

    private static boolean contains(Object left, Object right) {
        if (left instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> contains(item, right));
        }
        String source = String.valueOf(normalizeScalar(left)).toLowerCase(Locale.ROOT);
        String needle = String.valueOf(normalizeScalar(right)).toLowerCase(Locale.ROOT);
        return source.contains(needle);
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0D;
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return !String.valueOf(value).isBlank();
    }

    private static Object normalizeScalar(Object value) {
        return value == null ? "" : value;
    }

    private static Double asNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Collection<?> toCollection(Object value) {
        if (value instanceof EachValues each) {
            return each.values();
        }
        if (value instanceof MultiMatchValues multiMatch) {
            return multiMatch.values();
        }
        if (value instanceof AnyMatchValues anyMatch) {
            return anyMatch.values();
        }
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        if (value == null) {
            return List.of();
        }
        return List.of(value);
    }

    private record EachValues(Collection<?> values) {
    }

    public record MultiMatchValues(List<Object> values) {
        public MultiMatchValues {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record AnyMatchValues(List<Object> values) {
        public AnyMatchValues {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public static MultiMatchValues multiMatch(Collection<?> values) {
        return new MultiMatchValues(values == null ? List.of() : new ArrayList<>(values));
    }

    public static AnyMatchValues anyMatch(Collection<?> values) {
        return new AnyMatchValues(values == null ? List.of() : new ArrayList<>(values));
    }

    public static Object firstSortValue(Object value) {
        if (value instanceof MultiMatchValues multiMatch) {
            return multiMatch.values().isEmpty() ? null : multiMatch.values().get(0);
        }
        if (value instanceof AnyMatchValues anyMatch) {
            return anyMatch.values().isEmpty() ? null : anyMatch.values().get(0);
        }
        return value;
    }

    @FunctionalInterface
    public interface PathResolver {
        Resolution resolve(String identifier);
    }

    public record Resolution(boolean resolved, Object value) {
        public static Resolution unresolved() {
            return new Resolution(false, null);
        }

        public static Resolution resolved(Object value) {
            return new Resolution(true, value);
        }
    }

    private enum TokenType {
        LPAREN,
        RPAREN,
        COMMA,
        AND,
        OR,
        OPERATOR,
        STRING,
        NUMBER,
        BOOLEAN,
        NULL,
        IDENTIFIER,
        END
    }

    private record Token(TokenType type, String value) {
    }

    public record Context(
            Map<String, Object> record,
            Map<String, Object> body,
            Map<String, String> query,
            Map<String, String> headers,
            String requestContext,
            String method,
            RequestPrincipal principal,
            Function<String, List<Map<String, Object>>> collectionResolver,
            PathResolver pathResolver
    ) {
        public Context {
            record = record == null ? Map.of() : record;
            body = body == null ? Map.of() : body;
            query = query == null ? Map.of() : query;
            headers = RuleRequestContext.of(Map.of(), headers).headers();
            requestContext = requestContext == null || requestContext.isBlank()
                    ? RuleRequestContext.DEFAULT
                    : requestContext;
            method = method == null ? "GET" : method;
            collectionResolver = collectionResolver == null ? ignored -> List.of() : collectionResolver;
        }

        Object resolve(String identifier) {
            ModifierParts parts = modifierParts(identifier);
            String normalized = parts.field();
            if ("isset".equals(parts.modifier())) {
                return pathExists(normalized);
            }
            if ("changed".equals(parts.modifier())) {
                return fieldChanged(normalized);
            }
            Object value = switch (normalized) {
                case "@now" -> DateTimeFormatter.ISO_INSTANT.format(Instant.now());
                case "@todayStart" -> LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
                case "@todayEnd" -> LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().minusNanos(1).toInstant(ZoneOffset.UTC).toString();
                case "@request.method" -> method;
                case "@request.context" -> requestContext;
                case "@request.headers" -> headers;
                case "@request.auth.id" -> principal != null ? principal.id() : null;
                case "@request.auth.collectionId" -> principal != null ? principal.collectionId() : null;
                case "@request.auth.collectionName" -> principal != null ? principal.collectionName() : null;
                default -> resolvePath(normalized);
            };
            return applyModifier(value, parts.modifier());
        }

        private Object applyModifier(Object value, String modifier) {
            if (value instanceof MultiMatchValues multiMatch) {
                List<Object> modified = new ArrayList<>();
                for (Object item : multiMatch.values()) {
                    if ("each".equals(modifier)) {
                        modified.addAll(toCollection(item));
                    } else {
                        modified.add(applyScalarModifier(item, modifier));
                    }
                }
                return new MultiMatchValues(modified);
            }
            return applyScalarModifier(value, modifier);
        }

        private Object applyScalarModifier(Object value, String modifier) {
            return switch (modifier) {
                case "" -> value;
                case "lower" -> value == null ? null : String.valueOf(value).toLowerCase(Locale.ROOT);
                case "length" -> valueLength(value);
                case "each" -> new EachValues(toCollection(value));
                default -> throw invalidFilter("Unknown filter modifier `" + modifier + "`.");
            };
        }

        private ModifierParts modifierParts(String identifier) {
            int modifier = identifier.lastIndexOf(':');
            if (modifier < 0 || modifier < identifier.lastIndexOf('.')) {
                return new ModifierParts(identifier, "");
            }
            String value = identifier.substring(modifier + 1);
            if (!List.of("lower", "length", "each", "isset", "changed").contains(value)) {
                throw invalidFilter("Unknown filter modifier `" + value + "`.");
            }
            return new ModifierParts(identifier.substring(0, modifier), value);
        }

        private Object resolvePath(String identifier) {
            if (identifier.startsWith("@collection.")) {
                return resolveCollection(identifier.substring("@collection.".length()));
            }
            if (pathResolver != null) {
                Resolution resolution = pathResolver.resolve(identifier);
                if (resolution != null && resolution.resolved()) {
                    return resolution.value();
                }
            }
            if (identifier.startsWith("@request.auth.")) {
                return read(principal == null ? Map.of() : principal.asRuleMap(), identifier.substring("@request.auth.".length()));
            }
            if (identifier.startsWith("@request.body.")) {
                return read(body, identifier.substring("@request.body.".length()));
            }
            if (identifier.startsWith("@request.query.")) {
                return read(query, identifier.substring("@request.query.".length()));
            }
            if (identifier.startsWith("@request.headers.")) {
                return read(headers, identifier.substring("@request.headers.".length()).toLowerCase(Locale.ROOT));
            }
            if (record.containsKey(identifier)) {
                return record.get(identifier);
            }
            if (body.containsKey(identifier)) {
                return body.get(identifier);
            }
            return read(record, identifier);
        }

        private MultiMatchValues resolveCollection(String path) {
            int dot = path.indexOf('.');
            if (dot <= 0 || dot == path.length() - 1) {
                return multiMatch(List.of());
            }
            String collection = path.substring(0, dot);
            int alias = collection.indexOf(':');
            if (alias >= 0) {
                collection = collection.substring(0, alias);
            }
            String fieldPath = path.substring(dot + 1);
            List<Object> values = new ArrayList<>();
            for (Map<String, Object> relatedRecord : collectionResolver.apply(collection)) {
                Object value = read(relatedRecord, fieldPath);
                if (value != null) {
                    values.add(value);
                }
            }
            return multiMatch(values);
        }

        private Object read(Map<?, ?> source, String path) {
            Object current = source;
            for (String part : path.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(part);
                if (current == null) {
                    return null;
                }
            }
            return current;
        }

        private boolean pathExists(String identifier) {
            if (identifier.startsWith("@request.body.")) {
                return containsPath(body, identifier.substring("@request.body.".length()));
            }
            if (identifier.startsWith("@request.query.")) {
                return query.containsKey(identifier.substring("@request.query.".length()));
            }
            if (identifier.startsWith("@request.auth.")) {
                return principal != null && containsPath(
                        principal.asRuleMap(),
                        identifier.substring("@request.auth.".length())
                );
            }
            if (identifier.startsWith("@request.headers.")) {
                return headers.containsKey(identifier.substring("@request.headers.".length()).toLowerCase(Locale.ROOT));
            }
            return containsPath(record, identifier);
        }

        private boolean fieldChanged(String identifier) {
            if (!identifier.startsWith("@request.body.")) {
                return false;
            }
            String path = identifier.substring("@request.body.".length());
            return containsPath(body, path) && !valuesEqual(read(body, path), read(record, path));
        }

        private boolean containsPath(Map<?, ?> source, String path) {
            Object current = source;
            for (String part : path.split("\\.")) {
                if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                    return false;
                }
                current = map.get(part);
            }
            return true;
        }

        private int valueLength(Object value) {
            if (value == null) {
                return 0;
            }
            if (value instanceof Collection<?> collection) {
                return collection.size();
            }
            if (value instanceof Map<?, ?> map) {
                return map.size();
            }
            return String.valueOf(value).isBlank() ? 0 : 1;
        }

        private record ModifierParts(String field, String modifier) {
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final Context context;
        private int index;

        private Parser(List<Token> tokens, Context context) {
            this.tokens = tokens;
            this.context = context;
        }

        Object parse() {
            Object value = parseOr();
            if (peek().type != TokenType.END) {
                throw invalidFilter("Invalid filter expression near `" + peek().value + "`.");
            }
            return value;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (match(TokenType.OR)) {
                Object right = parseAnd();
                left = truthy(left) || truthy(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parsePrimary();
            while (match(TokenType.AND)) {
                Object right = parsePrimary();
                left = truthy(left) && truthy(right);
            }
            return left;
        }

        private Object parsePrimary() {
            if (match(TokenType.LPAREN)) {
                Object value = parseOr();
                consume(TokenType.RPAREN, "Expected `)`.");
                return value;
            }
            Object left = parseOperand();
            if (peek().type == TokenType.OPERATOR) {
                String operator = advance().value;
                Object right = parseOperand();
                return compare(left, operator, right);
            }
            return left;
        }

        private Object parseOperand() {
            Token token = advance();
            return switch (token.type) {
                case STRING -> token.value;
                case NUMBER -> token.value.contains(".") ? Double.parseDouble(token.value) : Long.parseLong(token.value);
                case BOOLEAN -> Boolean.parseBoolean(token.value);
                case NULL -> null;
                case IDENTIFIER -> peek().type == TokenType.LPAREN
                        ? parseFunction(token.value)
                        : context.resolve(token.value);
                default -> throw invalidFilter("Expected filter operand near `" + token.value + "`.");
            };
        }

        private Object parseFunction(String name) {
            consume(TokenType.LPAREN, "Expected `(` after filter function name.");
            List<FilterFunctionSupport.Argument> arguments = new ArrayList<>();
            if (peek().type != TokenType.RPAREN) {
                do {
                    Token argument = advance();
                    FilterFunctionSupport.ArgumentType type = switch (argument.type) {
                        case STRING -> FilterFunctionSupport.ArgumentType.TEXT;
                        case NUMBER -> FilterFunctionSupport.ArgumentType.NUMBER;
                        case IDENTIFIER -> FilterFunctionSupport.ArgumentType.IDENTIFIER;
                        case NULL -> FilterFunctionSupport.ArgumentType.NULL;
                        case BOOLEAN -> FilterFunctionSupport.ArgumentType.BOOLEAN;
                        default -> throw invalidFilter("Invalid argument for filter function `" + name + "`.");
                    };
                    Object value = switch (argument.type) {
                        case NUMBER -> argument.value.contains(".")
                                ? Double.parseDouble(argument.value)
                                : Long.parseLong(argument.value);
                        case NULL -> null;
                        case BOOLEAN -> Boolean.parseBoolean(argument.value);
                        default -> argument.value;
                    };
                    arguments.add(new FilterFunctionSupport.Argument(type, value));
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RPAREN, "Expected `)` after filter function arguments.");
            try {
                return FilterFunctionSupport.evaluate(name, arguments, context::resolve);
            } catch (IllegalArgumentException e) {
                throw invalidFilter(e.getMessage());
            }
        }

        private boolean match(TokenType type) {
            if (peek().type != type) {
                return false;
            }
            index++;
            return true;
        }

        private Token consume(TokenType type, String message) {
            if (peek().type == type) {
                return advance();
            }
            throw invalidFilter(message);
        }

        private Token advance() {
            return tokens.get(index++);
        }

        private Token peek() {
            return tokens.get(index);
        }
    }

    private static ApiException invalidFilter(String message) {
        return new ApiException(400, "Invalid filter.", ApiErrors.invalidField("filter", message));
    }
}
