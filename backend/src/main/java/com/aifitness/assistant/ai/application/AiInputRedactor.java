package com.aifitness.assistant.ai.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a provider request from an explicit data-minimization allowlist. */
public final class AiInputRedactor {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "interactionId",
            "experienceLevel",
            "goal",
            "weeklyFrequency",
            "sessionDurationMinutes",
            "equipmentCategories",
            "preferences",
            "candidateId",
            "candidates",
            "candidateSummaries",
            "dayCode",
            "exercises",
            "workSets",
            "repMin",
            "repMax",
            "restSeconds",
            "targetWeightKg",
            "weightStatus",
            "ruleIssues",
            "workoutSessionId",
            "workoutFacts",
            "completedWorkSets",
            "completedVolumeKg",
            "completedReps",
            "usesExternalLoad",
            "exerciseCode",
            "movementPattern",
            "difficulty",
            "equipment",
            "primaryMuscles",
            "completionType",
            "decision",
            "reasonCode",
            "reasonCodes",
            "safetyFlags",
            "status");

    public Map<String, Object> redact(Map<String, ?> source) {
        if (source == null) {
            return Map.of();
        }
        return sanitizeMap(source);
    }

    private Map<String, Object> sanitizeMap(Map<String, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (ALLOWED_FIELDS.contains(key)) {
                Object sanitized = sanitizeValue(value);
                if (sanitized != null) {
                    result.put(key, sanitized);
                }
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private Object sanitizeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> stringKeyed = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key instanceof String stringKey) {
                    stringKeyed.put(stringKey, item);
                }
            });
            return sanitizeMap(stringKeyed);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : iterable) {
                Object sanitized = sanitizeValue(item);
                if (sanitized != null) {
                    result.add(sanitized);
                }
            }
            return Collections.unmodifiableList(result);
        }
        return null;
    }
}
