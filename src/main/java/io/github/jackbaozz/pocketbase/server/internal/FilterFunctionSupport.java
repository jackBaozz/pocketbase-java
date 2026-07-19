package io.github.jackbaozz.pocketbase.server.internal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** In-memory implementation of PocketBase's registered filter token functions. */
public final class FilterFunctionSupport {
    private static final double EARTH_RADIUS_KM = 6371D;
    private static final double JULIAN_UNIX_EPOCH = 2440587.5D;
    private static final Pattern AMOUNT_MODIFIER = Pattern.compile(
            "^([+-])(\\d+(?:\\.\\d+)?)\\s+(seconds?|minutes?|hours?|days?|months?|years?)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLOCK_MODIFIER = Pattern.compile(
            "^([+-])(\\d{1,3}):(\\d{2})(?::(\\d{2}(?:\\.\\d+)?))?$"
    );
    private static final Pattern WEEKDAY_MODIFIER = Pattern.compile("^weekday\\s+([0-6])$", Pattern.CASE_INSENSITIVE);

    private FilterFunctionSupport() {
    }

    public static Object evaluate(
            String name,
            List<Argument> arguments,
            Function<String, Object> identifierResolver
    ) {
        return switch (name) {
            case "geoDistance" -> geoDistance(arguments, identifierResolver);
            case "strftime" -> strftime(arguments, identifierResolver);
            default -> throw new IllegalArgumentException("Unknown filter function `" + name + "`.");
        };
    }

    private static Object geoDistance(List<Argument> arguments, Function<String, Object> resolver) {
        if (arguments.size() != 4) {
            throw new IllegalArgumentException("[geoDistance] expected 4 arguments, got " + arguments.size());
        }
        List<Object> resolved = new ArrayList<>(4);
        boolean multiple = false;
        int maxValues = 1;
        for (int i = 0; i < arguments.size(); i++) {
            Argument argument = arguments.get(i);
            if (!List.of(
                    ArgumentType.IDENTIFIER,
                    ArgumentType.NUMBER,
                    ArgumentType.NULL,
                    ArgumentType.BOOLEAN
            ).contains(argument.type())) {
                throw new IllegalArgumentException("[geoDistance] argument " + i + " must be an identifier or number");
            }
            Object value = argument.resolve(resolver);
            resolved.add(value);
            List<Object> values = matchValues(value);
            if (values != null) {
                multiple = true;
                maxValues = Math.max(maxValues, values.size());
            }
        }
        if (!multiple) {
            return haversine(resolved.get(0), resolved.get(1), resolved.get(2), resolved.get(3));
        }
        List<Object> distances = new ArrayList<>(maxValues);
        for (int i = 0; i < maxValues; i++) {
            distances.add(haversine(
                    valueAt(resolved.get(0), i),
                    valueAt(resolved.get(1), i),
                    valueAt(resolved.get(2), i),
                    valueAt(resolved.get(3), i)
            ));
        }
        return RuleEvaluator.anyMatch(distances);
    }

    private static Object strftime(List<Argument> arguments, Function<String, Object> resolver) {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("[strftime] expected at least 1 arguments, got 0");
        }
        if (arguments.size() > 10) {
            throw new IllegalArgumentException(
                    "[strftime] too many arguments (max allowed 10, got " + arguments.size() + ")"
            );
        }
        if (arguments.get(0).type() != ArgumentType.TEXT) {
            throw new IllegalArgumentException("[strftime] expects the first argument to be a format string");
        }
        if (arguments.size() > 1 && !List.of(
                ArgumentType.TEXT,
                ArgumentType.IDENTIFIER,
                ArgumentType.NUMBER,
                ArgumentType.NULL,
                ArgumentType.BOOLEAN
        ).contains(arguments.get(1).type())) {
            throw new IllegalArgumentException("[strftime] expects the second argument to be of a valid time-value type");
        }
        List<String> modifiers = new ArrayList<>();
        for (int i = 2; i < arguments.size(); i++) {
            if (arguments.get(i).type() != ArgumentType.TEXT) {
                throw new IllegalArgumentException(
                        "[strftime] invalid modifier argument " + (i - 2) + " - can be only string"
                );
            }
            modifiers.add(String.valueOf(arguments.get(i).value()));
        }

        String format = String.valueOf(arguments.get(0).value());
        Object timeValue = arguments.size() == 1 ? "now" : arguments.get(1).resolve(resolver);
        if (timeValue instanceof RuleEvaluator.MultiMatchValues multiMatch) {
            return RuleEvaluator.multiMatch(multiMatch.values().stream()
                    .map(value -> formatTime(format, value, modifiers))
                    .toList());
        }
        if (timeValue instanceof RuleEvaluator.AnyMatchValues anyMatch) {
            return RuleEvaluator.anyMatch(anyMatch.values().stream()
                    .map(value -> formatTime(format, value, modifiers))
                    .toList());
        }
        return formatTime(format, timeValue, modifiers);
    }

    private static Double haversine(Object lonAValue, Object latAValue, Object lonBValue, Object latBValue) {
        Double lonA = number(lonAValue);
        Double latA = number(latAValue);
        Double lonB = number(lonBValue);
        Double latB = number(latBValue);
        if (lonA == null || latA == null || lonB == null || latB == null) {
            return null;
        }
        double latARadians = Math.toRadians(latA);
        double latBRadians = Math.toRadians(latB);
        double deltaLat = latBRadians - latARadians;
        double deltaLon = Math.toRadians(lonB - lonA);
        double sinLat = Math.sin(deltaLat / 2D);
        double sinLon = Math.sin(deltaLon / 2D);
        double a = sinLat * sinLat + Math.cos(latARadians) * Math.cos(latBRadians) * sinLon * sinLon;
        return EARTH_RADIUS_KM * 2D * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0D, 1D - a)));
    }

    private static String formatTime(String format, Object rawValue, List<String> modifiers) {
        TemporalValue temporal = parseTime(rawValue, modifiers);
        if (temporal == null) {
            return null;
        }
        ZonedDateTime value = temporal.value();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char current = format.charAt(i);
            if (current != '%') {
                result.append(current);
                continue;
            }
            if (++i >= format.length()) {
                return null;
            }
            char token = format.charAt(i);
            String replacement = formatToken(token, value, temporal.subseconds());
            if (replacement == null) {
                return null;
            }
            result.append(replacement);
        }
        return result.toString();
    }

    private static TemporalValue parseTime(Object rawValue, List<String> modifiers) {
        String mode = modifiers.stream()
                .map(value -> value.toLowerCase(Locale.ROOT).trim())
                .filter(value -> List.of("unixepoch", "julianday", "auto").contains(value))
                .findFirst()
                .orElse("");
        ZonedDateTime value = parseBaseTime(rawValue, mode);
        if (value == null) {
            return null;
        }
        boolean subseconds = false;
        for (int i = 0; i < modifiers.size(); i++) {
            String modifier = modifiers.get(i).trim().toLowerCase(Locale.ROOT);
            if (modifier.isBlank() || List.of("unixepoch", "julianday", "auto", "ceiling", "floor").contains(modifier)) {
                continue;
            }
            switch (modifier) {
                case "subsec", "subsecond" -> subseconds = true;
                case "localtime" -> value = value.withZoneSameInstant(ZoneId.systemDefault());
                case "utc" -> value = value.withZoneSameInstant(ZoneOffset.UTC);
                case "start of day" -> value = value.toLocalDate().atStartOfDay(value.getZone());
                case "start of month" -> value = value.withDayOfMonth(1).toLocalDate().atStartOfDay(value.getZone());
                case "start of year" -> value = value.withDayOfYear(1).toLocalDate().atStartOfDay(value.getZone());
                default -> {
                    Matcher weekday = WEEKDAY_MODIFIER.matcher(modifier);
                    if (weekday.matches()) {
                        value = moveToWeekday(value, Integer.parseInt(weekday.group(1)));
                        continue;
                    }
                    Matcher amount = AMOUNT_MODIFIER.matcher(modifier);
                    if (amount.matches()) {
                        boolean floor = i + 1 < modifiers.size()
                                && "floor".equalsIgnoreCase(modifiers.get(i + 1).trim());
                        value = applyAmount(value, amount, floor);
                        continue;
                    }
                    Matcher clock = CLOCK_MODIFIER.matcher(modifier);
                    if (clock.matches()) {
                        value = applyClock(value, clock);
                        continue;
                    }
                    return null;
                }
            }
        }
        return new TemporalValue(value, subseconds);
    }

    private static ZonedDateTime parseBaseTime(Object rawValue, String mode) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof Number number) {
            return numericTime(number.doubleValue(), mode);
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("now".equalsIgnoreCase(text)) {
            return Instant.now().atZone(ZoneOffset.UTC);
        }
        Double numeric = number(text);
        if (numeric != null) {
            return numericTime(numeric, mode);
        }
        String iso = text.contains(" ") ? text.replace(' ', 'T') : text;
        try {
            return Instant.parse(iso).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(iso).toZonedDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalTime.parse(text, DateTimeFormatter.ISO_LOCAL_TIME)
                    .atDate(LocalDate.of(2000, 1, 1))
                    .atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static ZonedDateTime numericTime(double value, String mode) {
        boolean unixEpoch = "unixepoch".equals(mode)
                || "auto".equals(mode) && (value < 0D || value > 5373484.499999D);
        if (unixEpoch) {
            long seconds = (long) Math.floor(value);
            long nanos = Math.round((value - seconds) * 1_000_000_000D);
            return Instant.ofEpochSecond(seconds, nanos).atZone(ZoneOffset.UTC);
        }
        if ("julianday".equals(mode) || "auto".equals(mode) || mode.isBlank()) {
            double epochSeconds = (value - JULIAN_UNIX_EPOCH) * 86400D;
            long seconds = (long) Math.floor(epochSeconds);
            long nanos = Math.round((epochSeconds - seconds) * 1_000_000_000D);
            try {
                return Instant.ofEpochSecond(seconds, nanos).atZone(ZoneOffset.UTC);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static ZonedDateTime applyAmount(ZonedDateTime value, Matcher matcher, boolean floor) {
        double amount = Double.parseDouble(matcher.group(2));
        if ("-".equals(matcher.group(1))) {
            amount = -amount;
        }
        String unit = matcher.group(3).toLowerCase(Locale.ROOT);
        if (unit.startsWith("year")) {
            return shiftMonths(value, amount * 12D, floor);
        }
        if (unit.startsWith("month")) {
            return shiftMonths(value, amount, floor);
        }
        double seconds = amount * switch (unit.charAt(0)) {
            case 'd' -> 86400D;
            case 'h' -> 3600D;
            case 'm' -> 60D;
            default -> 1D;
        };
        return value.plusNanos(Math.round(seconds * 1_000_000_000D));
    }

    private static ZonedDateTime shiftMonths(ZonedDateTime value, double amount, boolean floor) {
        long wholeMonths = amount < 0D ? (long) Math.ceil(amount) : (long) Math.floor(amount);
        double fraction = amount - wholeMonths;
        YearMonth target = YearMonth.from(value).plusMonths(wholeMonths);
        int originalDay = value.getDayOfMonth();
        int targetDay = Math.min(originalDay, target.lengthOfMonth());
        ZonedDateTime shifted = ZonedDateTime.of(
                target.atDay(targetDay),
                value.toLocalTime(),
                value.getZone()
        );
        if (!floor && originalDay > target.lengthOfMonth()) {
            shifted = shifted.plusDays(originalDay - target.lengthOfMonth());
        }
        if (fraction != 0D) {
            shifted = shifted.plusNanos(Math.round(fraction * 30.436875D * 86400D * 1_000_000_000D));
        }
        return shifted;
    }

    private static ZonedDateTime applyClock(ZonedDateTime value, Matcher matcher) {
        double seconds = Integer.parseInt(matcher.group(2)) * 3600D
                + Integer.parseInt(matcher.group(3)) * 60D
                + (matcher.group(4) == null ? 0D : Double.parseDouble(matcher.group(4)));
        if ("-".equals(matcher.group(1))) {
            seconds = -seconds;
        }
        return value.plusNanos(Math.round(seconds * 1_000_000_000D));
    }

    private static ZonedDateTime moveToWeekday(ZonedDateTime value, int sqliteWeekday) {
        int current = value.getDayOfWeek().getValue() % 7;
        int days = (sqliteWeekday - current + 7) % 7;
        return value.plusDays(days);
    }

    private static String formatToken(char token, ZonedDateTime value, boolean subseconds) {
        return switch (token) {
            case '%' -> "%";
            case 'Y' -> pad(value.getYear(), 4);
            case 'm' -> pad(value.getMonthValue(), 2);
            case 'd' -> pad(value.getDayOfMonth(), 2);
            case 'e' -> spacePad(value.getDayOfMonth(), 2);
            case 'H' -> pad(value.getHour(), 2);
            case 'k' -> spacePad(value.getHour(), 2);
            case 'I' -> pad(hour12(value.getHour()), 2);
            case 'l' -> spacePad(hour12(value.getHour()), 2);
            case 'M' -> pad(value.getMinute(), 2);
            case 'S' -> pad(value.getSecond(), 2);
            case 'f' -> pad(value.getSecond(), 2) + "." + pad(value.getNano() / 1_000_000, 3);
            case 'F' -> pad(value.getYear(), 4) + "-" + pad(value.getMonthValue(), 2) + "-" + pad(value.getDayOfMonth(), 2);
            case 'R' -> pad(value.getHour(), 2) + ":" + pad(value.getMinute(), 2);
            case 'T' -> pad(value.getHour(), 2) + ":" + pad(value.getMinute(), 2) + ":" + pad(value.getSecond(), 2);
            case 'j' -> pad(value.getDayOfYear(), 3);
            case 'w' -> String.valueOf(value.getDayOfWeek().getValue() % 7);
            case 'u' -> String.valueOf(value.getDayOfWeek().getValue());
            case 'U' -> pad(weekNumber(value.toLocalDate(), DayOfWeek.SUNDAY), 2);
            case 'W' -> pad(weekNumber(value.toLocalDate(), DayOfWeek.MONDAY), 2);
            case 'V' -> pad(value.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), 2);
            case 'G' -> pad(value.get(IsoFields.WEEK_BASED_YEAR), 4);
            case 'g' -> pad(Math.floorMod(value.get(IsoFields.WEEK_BASED_YEAR), 100), 2);
            case 'p' -> value.getHour() < 12 ? "AM" : "PM";
            case 'P' -> value.getHour() < 12 ? "am" : "pm";
            case 's' -> epochSeconds(value, subseconds);
            case 'J' -> julianDay(value);
            default -> null;
        };
    }

    private static int weekNumber(LocalDate date, DayOfWeek firstDay) {
        LocalDate first = LocalDate.of(date.getYear(), 1, 1);
        int firstOffset = Math.floorMod(firstDay.getValue() - first.getDayOfWeek().getValue(), 7);
        int zeroBasedDay = date.getDayOfYear() - 1;
        return zeroBasedDay < firstOffset ? 0 : 1 + (zeroBasedDay - firstOffset) / 7;
    }

    private static String epochSeconds(ZonedDateTime value, boolean subseconds) {
        Instant instant = value.toInstant();
        if (!subseconds || instant.getNano() == 0) {
            return String.valueOf(instant.getEpochSecond());
        }
        String fraction = pad(instant.getNano(), 9).replaceFirst("0+$", "");
        return instant.getEpochSecond() + "." + fraction;
    }

    private static String julianDay(ZonedDateTime value) {
        Instant instant = value.toInstant();
        double julian = JULIAN_UNIX_EPOCH
                + (instant.getEpochSecond() + instant.getNano() / 1_000_000_000D) / 86400D;
        return String.format(Locale.ROOT, "%.9f", julian).replaceFirst("0+$", "").replaceFirst("\\.$", "");
    }

    private static int hour12(int hour) {
        int normalized = hour % 12;
        return normalized == 0 ? 12 : normalized;
    }

    private static String pad(int value, int length) {
        return String.format(Locale.ROOT, "%0" + length + "d", value);
    }

    private static String spacePad(int value, int length) {
        return String.format(Locale.ROOT, "%" + length + "d", value);
    }

    private static Double number(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? 1D : 0D;
        }
        if (value instanceof Number number) {
            double result = number.doubleValue();
            return Double.isFinite(result) ? result : null;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                double result = Double.parseDouble(text);
                return Double.isFinite(result) ? result : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object valueAt(Object value, int index) {
        List<Object> values = matchValues(value);
        if (values == null) {
            return value;
        }
        return index < values.size() ? values.get(index) : null;
    }

    private static List<Object> matchValues(Object value) {
        if (value instanceof RuleEvaluator.MultiMatchValues multiMatch) {
            return multiMatch.values();
        }
        if (value instanceof RuleEvaluator.AnyMatchValues anyMatch) {
            return anyMatch.values();
        }
        return null;
    }

    public enum ArgumentType {
        TEXT,
        NUMBER,
        IDENTIFIER,
        NULL,
        BOOLEAN
    }

    public record Argument(ArgumentType type, Object value) {
        Object resolve(Function<String, Object> resolver) {
            return type == ArgumentType.IDENTIFIER ? resolver.apply(String.valueOf(value)) : value;
        }
    }

    private record TemporalValue(ZonedDateTime value, boolean subseconds) {
    }
}
