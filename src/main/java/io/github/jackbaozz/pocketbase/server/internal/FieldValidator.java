package io.github.jackbaozz.pocketbase.server.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackbaozz.pocketbase.server.model.FieldSchema;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

public final class FieldValidator {

    public static final class Unchanged {
        public static final Unchanged INSTANCE = new Unchanged();
    }

    private FieldValidator() {}

    public static Object normalizeFieldValue(
            ObjectMapper mapper,
            FieldSchema field,
            JsonNode value,
            boolean update,
            Map<String, Object> errors,
            BiPredicate<String, String> recordExists
    ) {
        String type = normalizeType(field.type);
        if (value.isNull() || isBlankText(value)) {
            if (field.required && !(update && "password".equals(type))) {
                errors.put(field.name, validationError(field.name + " is required."));
            }
            return update && "password".equals(type) ? Unchanged.INSTANCE : null;
        }

        try {
            return switch (type) {
                case "email" -> normalizeEmail(field, value, errors);
                case "password" -> normalizePassword(field, value, errors);
                case "bool", "boolean" -> normalizeBoolean(field, value, errors);
                case "number" -> normalizeNumber(field, value, errors);
                case "select" -> normalizeSelect(mapper, field, value, errors);
                case "url" -> normalizeUrl(field, value, errors);
                case "text", "editor" -> normalizeText(field, value, errors);
                case "date", "autodate" -> normalizeDate(field, value, errors);
                case "relation" -> normalizeRelation(mapper, field, value, errors, recordExists);
                case "json", "file" -> mapper.convertValue(value, Object.class);
                default -> value.asText();
            };
        } catch (IllegalArgumentException e) {
            errors.put(field.name, validationError(e.getMessage()));
            return null;
        }
    }

    private static String normalizeType(String type) {
        return type == null ? "text" : type.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean isBlankText(JsonNode node) {
        return node.isTextual() && node.asText().isBlank();
    }

    private static Map<String, Object> validationError(String message) {
        return Map.of("code", "validation_failed", "message", message);
    }

    private static Object normalizeEmail(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        String email = value.asText().trim().toLowerCase(Locale.ROOT);
        if (!email.contains("@") || email.startsWith("@") || email.endsWith("@")) {
            errors.put(field.name, validationError("Invalid email address."));
            return email;
        }

        if (field.options != null) {
            if (field.options.containsKey("exceptDomains") && field.options.get("exceptDomains").isArray()) {
                for (JsonNode domainNode : field.options.get("exceptDomains")) {
                    String domain = domainNode.asText();
                    if (email.endsWith("@" + domain) || email.endsWith("." + domain)) {
                        errors.put(field.name, validationError("Email domain is not allowed."));
                        break;
                    }
                }
            }
            if (field.options.containsKey("onlyDomains") && field.options.get("onlyDomains").isArray() && !field.options.get("onlyDomains").isEmpty()) {
                boolean matched = false;
                for (JsonNode domainNode : field.options.get("onlyDomains")) {
                    String domain = domainNode.asText();
                    if (email.endsWith("@" + domain) || email.endsWith("." + domain)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    errors.put(field.name, validationError("Email domain is not allowed."));
                }
            }
        }
        return email;
    }

    private static Object normalizePassword(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        String pwd = value.asText();
        if (field.options != null) {
            if (field.options.containsKey("min")) {
                int min = field.options.get("min").asInt();
                if (pwd.length() < min) {
                    errors.put(field.name, validationError("Cannot be less than " + min + " characters."));
                }
            }
            if (field.options.containsKey("max")) {
                int max = field.options.get("max").asInt();
                if (pwd.length() > max) {
                    errors.put(field.name, validationError("Cannot be more than " + max + " characters."));
                }
            }
            if (field.options.containsKey("pattern") && !field.options.get("pattern").asText().isEmpty()) {
                String pattern = field.options.get("pattern").asText();
                try {
                    if (!Pattern.compile(pattern).matcher(pwd).matches()) {
                        errors.put(field.name, validationError("Invalid password format."));
                    }
                } catch (Exception e) {
                    // ignore invalid regex in schema
                }
            }
        }
        return PasswordHasher.hash(pwd);
    }

    private static Object normalizeBoolean(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            int intValue = value.numberValue().intValue();
            if (intValue == 0 || intValue == 1) {
                return intValue == 1;
            }
            errors.put(field.name, validationError("Expected boolean value."));
            return null;
        }
        String text = value.asText().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text)) {
            return false;
        }
        errors.put(field.name, validationError("Expected boolean value."));
        return null;
    }

    private static Object normalizeNumber(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        Double numVal = null;
        if (value.isNumber()) {
            numVal = value.numberValue().doubleValue();
        } else if (value.isTextual()) {
            try {
                numVal = Double.parseDouble(value.asText());
            } catch (NumberFormatException ignored) {
                errors.put(field.name, validationError("Expected numeric value."));
                return null;
            }
        } else {
            errors.put(field.name, validationError("Expected numeric value."));
            return null;
        }

        if (field.options != null) {
            if (field.options.containsKey("min")) {
                double min = field.options.get("min").asDouble();
                if (numVal < min) {
                    errors.put(field.name, validationError("Cannot be less than " + min + "."));
                }
            }
            if (field.options.containsKey("max")) {
                double max = field.options.get("max").asDouble();
                if (numVal > max) {
                    errors.put(field.name, validationError("Cannot be more than " + max + "."));
                }
            }
        }
        return value.isNumber() ? value.numberValue() : numVal;
    }

    private static Object normalizeSelect(ObjectMapper mapper, FieldSchema field, JsonNode value, Map<String, Object> errors) {
        Object converted = mapper.convertValue(value, Object.class);
        JsonNode values = field.options == null ? null : field.options.get("values");
        if (values != null && values.isArray()) {
            Set<String> allowed = new LinkedHashSet<>();
            values.forEach(item -> allowed.add(item.asText()));
            if (value.isArray()) {
                for (JsonNode item : value) {
                    if (!allowed.contains(item.asText())) {
                        errors.put(field.name, validationError("Value is not in the allowed list."));
                    }
                }
                int maxSelect = field.options.containsKey("maxSelect") ? field.options.get("maxSelect").asInt(1) : 1;
                if (value.size() > maxSelect && maxSelect > 0) {
                    errors.put(field.name, validationError("Too many values selected."));
                }
            } else if (!allowed.contains(value.asText())) {
                errors.put(field.name, validationError("Value is not in the allowed list."));
            }
        }
        return converted;
    }

    private static Object normalizeUrl(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        String urlText = value.asText().trim();
        try {
            URI uri = new URI(urlText);
            if (uri.getScheme() == null || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
                errors.put(field.name, validationError("Must be a valid HTTP/HTTPS URL."));
                return urlText;
            }
            if (field.options != null) {
                JsonNode allowedHosts = field.options.containsKey("onlyHosts") ? field.options.get("onlyHosts") : field.options.get("onlyDomains");
                if (allowedHosts != null && allowedHosts.isArray() && !allowedHosts.isEmpty()) {
                    boolean matched = false;
                    String host = uri.getHost();
                    if (host != null) {
                        for (JsonNode hostNode : allowedHosts) {
                            if (host.equalsIgnoreCase(hostNode.asText())) {
                                matched = true;
                                break;
                            }
                        }
                    }
                    if (!matched) {
                        errors.put(field.name, validationError("URL host is not allowed."));
                    }
                }
                JsonNode exceptHosts = field.options.containsKey("exceptHosts") ? field.options.get("exceptHosts") : field.options.get("exceptDomains");
                if (exceptHosts != null && exceptHosts.isArray()) {
                    String host = uri.getHost();
                    if (host != null) {
                        for (JsonNode hostNode : exceptHosts) {
                            if (host.equalsIgnoreCase(hostNode.asText())) {
                                errors.put(field.name, validationError("URL host is not allowed."));
                                break;
                            }
                        }
                    }
                }
            }
        } catch (URISyntaxException e) {
            errors.put(field.name, validationError("Must be a valid URL."));
        }
        return urlText;
    }

    private static Object normalizeText(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        String text = value.asText();
        if (field.options != null) {
            if (field.options.containsKey("min")) {
                int min = field.options.get("min").asInt();
                if (text.length() < min) {
                    errors.put(field.name, validationError("Cannot be less than " + min + " characters."));
                }
            }
            if (field.options.containsKey("max")) {
                int max = field.options.get("max").asInt();
                if (text.length() > max) {
                    errors.put(field.name, validationError("Cannot be more than " + max + " characters."));
                }
            }
            if (field.options.containsKey("pattern") && !field.options.get("pattern").asText().isEmpty()) {
                String pattern = field.options.get("pattern").asText();
                try {
                    if (!Pattern.compile(pattern).matcher(text).matches()) {
                        errors.put(field.name, validationError("Invalid format."));
                    }
                } catch (Exception e) {
                    // Ignore invalid pattern
                }
            }
        }
        return text;
    }

    private static Object normalizeDate(FieldSchema field, JsonNode value, Map<String, Object> errors) {
        String dateText = value.asText().trim();
        try {
            String isoStr = dateText.replace(" ", "T");
            if (!isoStr.endsWith("Z") && !isoStr.contains("+") && isoStr.indexOf("-", 10) == -1) {
                isoStr += "Z";
            }
            Instant.parse(isoStr);

            if (field.options != null) {
                if (field.options.containsKey("min") && !field.options.get("min").asText().isEmpty()) {
                    String minDate = field.options.get("min").asText();
                    if (dateText.compareTo(minDate) < 0) {
                        errors.put(field.name, validationError("Cannot be before " + minDate + "."));
                    }
                }
                if (field.options.containsKey("max") && !field.options.get("max").asText().isEmpty()) {
                    String maxDate = field.options.get("max").asText();
                    if (dateText.compareTo(maxDate) > 0) {
                        errors.put(field.name, validationError("Cannot be after " + maxDate + "."));
                    }
                }
            }
        } catch (Exception e) {
            errors.put(field.name, validationError("Invalid date format."));
        }
        return dateText;
    }

    private static Object normalizeRelation(ObjectMapper mapper, FieldSchema field, JsonNode value, Map<String, Object> errors, BiPredicate<String, String> recordExists) {
        Object converted = mapper.convertValue(value, Object.class);
        if (field.options != null && field.options.containsKey("collectionId")) {
            String targetCollection = field.options.get("collectionId").asText();
            if (targetCollection != null && !targetCollection.isBlank()) {
                if (value.isArray()) {
                    for (JsonNode item : value) {
                        if (!recordExists.test(targetCollection, item.asText())) {
                            errors.put(field.name, validationError("Failed to resolve relation " + item.asText()));
                        }
                    }
                    int maxSelect = field.options.containsKey("maxSelect") ? field.options.get("maxSelect").asInt(1) : 1;
                    if (value.size() > maxSelect && maxSelect > 0) {
                        errors.put(field.name, validationError("Too many values selected."));
                    }
                } else {
                    if (!recordExists.test(targetCollection, value.asText())) {
                        errors.put(field.name, validationError("Failed to resolve relation " + value.asText()));
                    }
                }
            }
        }
        return converted;
    }
}
