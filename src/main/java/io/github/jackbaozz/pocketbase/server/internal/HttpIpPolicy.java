package io.github.jackbaozz.pocketbase.server.internal;

import com.sun.net.httpserver.HttpExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

final class HttpIpPolicy {
    private HttpIpPolicy() {
    }

    static boolean superuserAllowed(
            HttpExchange exchange,
            Map<String, Object> settings,
            RequestPrincipal principal
    ) {
        return principal == null || !principal.superuser() || superuserIpAllowed(exchange, settings);
    }

    static boolean superuserIpAllowed(HttpExchange exchange, Map<String, Object> settings) {
        List<Object> allowed = list(settings == null ? null : settings.get("superuserIPs"));
        return allowed.isEmpty() || ipInList(realIp(exchange, section(settings, "trustedProxy")), allowed);
    }

    static String realIp(HttpExchange exchange, Map<String, Object> trustedProxy) {
        boolean useLeftmost = truthy(trustedProxy.get("useLeftmostIP"));
        for (Object headerValue : list(trustedProxy.get("headers"))) {
            String header = text(headerValue);
            if (header.isBlank()) {
                continue;
            }
            List<String> values = exchange.getRequestHeaders().get(header);
            if (values == null || values.isEmpty()) {
                continue;
            }
            String value = values.get(values.size() - 1);
            String[] parts = value.split(",");
            if (useLeftmost) {
                for (String part : parts) {
                    String normalized = normalizeIp(part);
                    if (!normalized.isBlank()) {
                        return normalized;
                    }
                }
            } else {
                for (int i = parts.length - 1; i >= 0; i--) {
                    String normalized = normalizeIp(parts[i]);
                    if (!normalized.isBlank()) {
                        return normalized;
                    }
                }
            }
        }
        InetSocketAddress address = exchange.getRemoteAddress();
        if (address == null) {
            return "";
        }
        if (address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return normalizeIp(address.getHostString());
    }

    static boolean ipInList(String ip, List<Object> values) {
        for (Object value : values) {
            if (ipMatches(ip, text(value))) {
                return true;
            }
        }
        return false;
    }

    static boolean validIpOrSubnet(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String[] parts = value.split("/", 2);
        try {
            byte[] address = parseIpLiteral(parts[0]);
            if (address == null) {
                return false;
            }
            if (parts.length == 1) {
                return true;
            }
            int prefix = Integer.parseInt(parts[1]);
            return prefix >= 0 && prefix <= address.length * 8;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean ipMatches(String clientIp, String configured) {
        String[] parts = configured.split("/", 2);
        try {
            byte[] client = parseIpLiteral(clientIp);
            byte[] expected = parseIpLiteral(parts[0]);
            if (client == null || expected == null || client.length != expected.length) {
                return false;
            }
            int prefix = parts.length == 1 ? client.length * 8 : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > client.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (client[i] != expected[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (client[fullBytes] & mask) == (expected[fullBytes] & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeIp(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("[") && value.contains("]")) {
            value = value.substring(1, value.indexOf(']'));
        } else if (value.indexOf(':') == value.lastIndexOf(':') && value.contains(":")) {
            String port = value.substring(value.lastIndexOf(':') + 1);
            if (port.chars().allMatch(Character::isDigit)) {
                value = value.substring(0, value.lastIndexOf(':'));
            }
        }
        try {
            byte[] address = parseIpLiteral(value);
            return address == null ? "" : formatIp(address);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] parseIpLiteral(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank() || candidate.contains("%")) {
            return null;
        }
        if (!candidate.contains(":")) {
            String[] groups = candidate.split("\\.", -1);
            if (groups.length != 4) {
                return null;
            }
            byte[] address = new byte[4];
            for (int i = 0; i < groups.length; i++) {
                if (groups[i].isBlank() || !groups[i].chars().allMatch(Character::isDigit)) {
                    return null;
                }
                if (groups[i].length() > 1 && groups[i].startsWith("0")) {
                    return null;
                }
                int octet;
                try {
                    octet = Integer.parseInt(groups[i]);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (octet < 0 || octet > 255) {
                    return null;
                }
                address[i] = (byte) octet;
            }
            return address;
        }
        if (!candidate.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            byte[] address = InetAddress.getByName(candidate).getAddress();
            if (address.length == 16) {
                return address;
            }
            if (candidate.toLowerCase().contains("::ffff:") && address.length == 4) {
                byte[] mapped = new byte[16];
                mapped[10] = (byte) 0xff;
                mapped[11] = (byte) 0xff;
                System.arraycopy(address, 0, mapped, 12, address.length);
                return mapped;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatIp(byte[] address) {
        if (address.length == 4) {
            return (address[0] & 0xff) + "." + (address[1] & 0xff) + "."
                    + (address[2] & 0xff) + "." + (address[3] & 0xff);
        }
        if (address.length != 16) {
            return "";
        }
        StringBuilder result = new StringBuilder(39);
        for (int i = 0; i < address.length; i += 2) {
            if (i > 0) {
                result.append(':');
            }
            result.append(String.format("%02x%02x", address[i] & 0xff, address[i + 1] & 0xff));
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> section(Map<String, Object> settings, String name) {
        if (settings != null && settings.get(name) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List<?> items ? (List<Object>) items : List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = text(value).toLowerCase();
        return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized);
    }
}
