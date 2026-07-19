package io.github.jackbaozz.pocketbase.server.internal;

import com.sun.net.httpserver.HttpExchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory fixed-window limiter matching PocketBase's rate limit settings semantics.
 */
public final class HttpRateLimiter {
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            "^(\\w+ /[\\w/-]*|/[\\w/-]*|\\w+:\\w+|\\*:\\w+|\\w+)$"
    );
    private static final List<String> AUDIENCES = List.of("", "@guest", "@auth");

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile String settingsFingerprint = "";

    public void check(
            HttpExchange exchange,
            Map<String, Object> settings,
            RequestPrincipal principal,
            List<String> labels,
            String limiterId
    ) {
        Map<String, Object> rateLimits = section(settings, "rateLimits");
        if (!truthy(rateLimits.get("enabled")) || principal != null && principal.superuser()) {
            return;
        }

        String clientIp = HttpIpPolicy.realIp(exchange, section(settings, "trustedProxy"));
        if (clientIp.isBlank() || HttpIpPolicy.ipInList(clientIp, list(rateLimits.get("excludedIPs")))) {
            return;
        }

        resetWhenSettingsChange(rateLimits, section(settings, "trustedProxy"));
        Rule rule = findRule(rateLimits, labels, principal != null);
        if (rule == null) {
            return;
        }

        String key = limiterId + "|" + rule.label() + "|" + rule.audience() + "|" + clientIp;
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.maxRequests != rule.maxRequests || existing.duration != rule.duration) {
                return new Window(rule.maxRequests, rule.duration);
            }
            return existing;
        });
        if (!window.consume()) {
            exchange.getResponseHeaders().set("Retry-After", String.valueOf(Math.max(1, rule.duration())));
            throw new ApiException(429, "Too many requests.");
        }
        cleanupExpiredWindows();
    }

    public static void validateSettings(Map<String, Object> settings) {
        Map<String, Object> rateLimits = section(settings, "rateLimits");
        List<Object> rawRules = validatedList(rateLimits.get("rules"), "rateLimits", "Rate limit rules must be an array.");
        if (truthy(rateLimits.get("enabled")) && rawRules.isEmpty()) {
            throw invalid("At least one rate limit rule is required when rate limiting is enabled.");
        }

        List<String> keys = new ArrayList<>();
        for (Object raw : rawRules) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw invalid("Each rate limit rule must be an object.");
            }
            String label = text(map.get("label"));
            String audience = text(map.get("audience"));
            int duration = integer(map.get("duration"), 0);
            int maxRequests = integer(map.get("maxRequests"), 0);
            if (!LABEL_PATTERN.matcher(label).matches()) {
                throw invalid("Invalid rate limit rule label: " + label);
            }
            if (!AUDIENCES.contains(audience)) {
                throw invalid("Invalid rate limit rule audience: " + audience);
            }
            if (duration < 1 || maxRequests < 1) {
                throw invalid("Rate limit duration and maxRequests must be at least 1.");
            }
            String key = label + "@@" + audience;
            if (keys.stream().anyMatch(existing -> existing.startsWith(key) || key.startsWith(existing))) {
                throw invalid("Conflicting rate limit rule: " + label);
            }
            keys.add(key);
        }

        for (Object value : validatedList(
                rateLimits.get("excludedIPs"),
                "rateLimits",
                "Excluded IPs must be an array."
        )) {
            String ip = text(value);
            if (!(value instanceof String) || !HttpIpPolicy.validIpOrSubnet(ip)) {
                throw invalid("Invalid excluded IP or subnet: " + ip);
            }
        }

        for (Object value : validatedList(
                settings == null ? null : settings.get("superuserIPs"),
                "superuserIPs",
                "Superuser IPs must be an array."
        )) {
            String ip = text(value);
            if (!(value instanceof String) || !HttpIpPolicy.validIpOrSubnet(ip)) {
                throw invalid("superuserIPs", "Invalid superuser IP or subnet: " + ip);
            }
        }

        Map<String, Object> trustedProxy = section(settings, "trustedProxy");
        for (Object value : validatedList(
                trustedProxy.get("headers"),
                "trustedProxy",
                "Trusted proxy headers must be an array."
        )) {
            if (!(value instanceof String)) {
                throw invalid("trustedProxy", "Trusted proxy header names must be strings.");
            }
        }
    }

    private Rule findRule(Map<String, Object> rateLimits, List<String> labels, boolean authenticated) {
        List<Rule> rules = new ArrayList<>();
        for (Object raw : list(rateLimits.get("rules"))) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            Rule rule = new Rule(
                    text(map.get("label")),
                    text(map.get("audience")),
                    integer(map.get("duration"), 0),
                    integer(map.get("maxRequests"), 0)
            );
            if (rule.duration() > 0 && rule.maxRequests() > 0) {
                rules.add(rule);
            }
        }

        List<String> allowedAudiences = authenticated ? List.of("", "@auth") : List.of("", "@guest");
        List<Rule> prefixes = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            for (Rule rule : rules) {
                if (label.equals(rule.label()) && allowedAudiences.contains(rule.audience())) {
                    return rule;
                }
                if (i == 0 && rule.label().endsWith("/")) {
                    prefixes.add(rule);
                }
            }
            for (Rule rule : prefixes) {
                if ((label + "/").startsWith(rule.label()) && allowedAudiences.contains(rule.audience())) {
                    return rule;
                }
            }
        }
        return null;
    }

    private void resetWhenSettingsChange(Map<String, Object> rateLimits, Map<String, Object> trustedProxy) {
        String fingerprint = String.valueOf(rateLimits) + '|' + trustedProxy;
        if (Objects.equals(settingsFingerprint, fingerprint)) {
            return;
        }
        synchronized (this) {
            if (!Objects.equals(settingsFingerprint, fingerprint)) {
                windows.clear();
                settingsFingerprint = fingerprint;
            }
        }
    }

    private void cleanupExpiredWindows() {
        if (windows.size() < 1024) {
            return;
        }
        long now = System.currentTimeMillis() / 1000L;
        windows.entrySet().removeIf(entry -> entry.getValue().expired(now, 1800));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> settings, String name) {
        if (settings != null && settings.get(name) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> items ? (List<Object>) items : List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> validatedList(Object value, String field, String message) {
        if (!(value instanceof List<?> items)) {
            throw invalid(field, message);
        }
        return (List<Object>) items;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = text(value).toLowerCase();
        return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized);
    }

    private static ApiException invalid(String message) {
        return invalid("rateLimits", message);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(400, "Failed to update settings.",
                ApiErrors.invalidField(field, message));
    }

    private record Rule(String label, String audience, int duration, int maxRequests) {
    }

    private static final class Window {
        private final int maxRequests;
        private final int duration;
        private int available;
        private long started;

        private Window(int maxRequests, int duration) {
            this.maxRequests = maxRequests;
            this.duration = duration;
        }

        private synchronized boolean consume() {
            long now = System.currentTimeMillis() / 1000L;
            if (now - started >= duration) {
                available = maxRequests;
                started = now;
            }
            if (available <= 0) {
                return false;
            }
            available--;
            return true;
        }

        private synchronized boolean expired(long now, long minimumIdle) {
            return now - (started + duration) > minimumIdle;
        }
    }
}
