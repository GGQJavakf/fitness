package com.aifitness.assistant.progression.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read model containing only server-selected effective work-set facts. */
@FunctionalInterface
public interface ExerciseTrendQuery {
    Trend load(AuthenticatedUserId user, String exerciseCode);

    record Trend(String exerciseCode, String unit, List<Point> points) {
        public Trend {
            exerciseCode = required(exerciseCode, "exercise code");
            if (!"KG".equals(unit)) throw new IllegalArgumentException("P0 trend only supports KG");
            points = List.copyOf(Objects.requireNonNull(points, "trend points must not be null"));
        }
    }

    record Point(
            UUID sessionId,
            Instant completedAt,
            BigDecimal topWeightKg,
            int totalReps,
            int workSetCount) {
        public Point {
            Objects.requireNonNull(sessionId, "session id must not be null");
            Objects.requireNonNull(completedAt, "completion time must not be null");
            Objects.requireNonNull(topWeightKg, "top weight must not be null");
            if (topWeightKg.signum() < 0 || totalReps < 0 || workSetCount < 1) {
                throw new IllegalArgumentException("trend values must be valid");
            }
            topWeightKg = topWeightKg.stripTrailingZeros();
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
