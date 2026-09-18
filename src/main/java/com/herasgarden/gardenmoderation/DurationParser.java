package com.herasgarden.gardenmoderation;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern TOKEN = Pattern.compile("(?i)(\\d+)(s|m|h|d|w)");

    private DurationParser() {}

    public static Long parseMillis(String input, boolean allowPermanent) {
        if (input == null || input.isBlank()) return null;
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (allowPermanent && (value.equals("permanent") || value.equals("perm") || value.equals("forever"))) {
            return null;
        }

        Matcher matcher = TOKEN.matcher(value);
        long total = 0L;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) return -1L;
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException exception) {
                return -1L;
            }
            long unit = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> -1L;
            };
            try {
                total = Math.addExact(total, Math.multiplyExact(amount, unit));
            } catch (ArithmeticException exception) {
                return -1L;
            }
            end = matcher.end();
        }
        if (end != value.length() || total < 1_000L) return -1L;
        return total;
    }

    public static String describe(Long millis) {
        if (millis == null) return "permanent";
        long seconds = Math.max(1L, millis / 1000L);
        if (seconds % 604800L == 0) return (seconds / 604800L) + "w";
        if (seconds % 86400L == 0) return (seconds / 86400L) + "d";
        if (seconds % 3600L == 0) return (seconds / 3600L) + "h";
        if (seconds % 60L == 0) return (seconds / 60L) + "m";
        return seconds + "s";
    }
}
