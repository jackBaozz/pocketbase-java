package io.github.jackbaozz.pocketbase.server.internal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable request values exposed to PocketBase rule expressions. */
public record RuleRequestContext(Map<String, String> query, Map<String, String> headers, String context) {
    public static final String DEFAULT = "default";
    public static final String EXPAND = "expand";
    public static final String REALTIME = "realtime";
    public static final String PROTECTED_FILE = "protectedFile";
    public static final String BATCH = "batch";
    public static final String OAUTH2 = "oauth2";
    public static final String OTP = "otp";
    public static final String PASSWORD = "password";

    public RuleRequestContext(Map<String, String> query, Map<String, String> headers) {
        this(query, headers, DEFAULT);
    }

    public RuleRequestContext {
        query = query == null ? Map.of() : Map.copyOf(query);
        Map<String, String> normalizedHeaders = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key == null) {
                    return;
                }
                normalizedHeaders.put(key, value == null ? "" : value);
                normalizedHeaders.put(key.toLowerCase(Locale.ROOT), value == null ? "" : value);
            });
        }
        headers = Map.copyOf(normalizedHeaders);
        context = context == null || context.isBlank() ? DEFAULT : context;
    }

    public static RuleRequestContext empty() {
        return new RuleRequestContext(Map.of(), Map.of(), DEFAULT);
    }

    public static RuleRequestContext of(Map<String, String> query, Map<String, String> headers) {
        return new RuleRequestContext(query, headers, DEFAULT);
    }

    public static RuleRequestContext of(
            Map<String, String> query,
            Map<String, String> headers,
            String context
    ) {
        return new RuleRequestContext(query, headers, context);
    }

    public RuleRequestContext withContext(String context) {
        return new RuleRequestContext(query, headers, context);
    }
}
