package com.aifitness.assistant.common.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class SensitiveDataSanitizer {

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern METHOD = Pattern.compile("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern EVENT_OR_RESULT = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final int MAX_ROUTE_LENGTH = 128;
    private static final Set<String> TRUSTED_ROUTE_TEMPLATES = Set.of("/api/v1/profile/{id}");

    private SensitiveDataSanitizer() {
    }

    public static Map<String, Object> sanitize(Map<String, ?> diagnosticData) {
        if (diagnosticData == null || diagnosticData.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> safeData = new LinkedHashMap<>();
        diagnosticData.forEach((key, value) -> addIfAllowed(safeData, key, value));
        return Map.copyOf(safeData);
    }

    private static void addIfAllowed(Map<String, Object> safeData, String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        if (value instanceof String text && matchesAllowedText(key, text)) {
            safeData.put(key, text);
            return;
        }
        if (value instanceof Integer integer && key.equals("status") && integer >= 100 && integer <= 599) {
            safeData.put(key, integer);
            return;
        }
        if (value instanceof Long duration && key.equals("duration") && duration >= 0 && duration <= 600_000) {
            safeData.put(key, duration);
        }
    }

    private static boolean matchesAllowedText(String key, String text) {
        return switch (key) {
            case "requestId" -> REQUEST_ID.matcher(text).matches();
            case "method" -> METHOD.matcher(text).matches();
            case "route" -> text.length() <= MAX_ROUTE_LENGTH && TRUSTED_ROUTE_TEMPLATES.contains(text);
            case "errorCode" -> ERROR_CODE.matcher(text).matches();
            case "event", "result" -> EVENT_OR_RESULT.matcher(text).matches();
            default -> false;
        };
    }
}
