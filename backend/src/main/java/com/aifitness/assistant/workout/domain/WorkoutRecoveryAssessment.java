package com.aifitness.assistant.workout.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic recovery-window result derived only from completed workout facts. */
public record WorkoutRecoveryAssessment(
        String policyVersion,
        Instant checkedAt,
        int minimumRecoveryHours,
        Decision decision,
        List<AffectedMuscle> affectedMuscles) {

    public WorkoutRecoveryAssessment {
        policyVersion = requiredText(policyVersion, "policy version");
        Objects.requireNonNull(checkedAt, "checked at must not be null");
        if (minimumRecoveryHours <= 0) {
            throw new IllegalArgumentException("minimum recovery hours must be positive");
        }
        Objects.requireNonNull(decision, "decision must not be null");
        affectedMuscles = List.copyOf(Objects.requireNonNull(
                affectedMuscles, "affected muscles must not be null"));
        if (decision == Decision.READY && !affectedMuscles.isEmpty()
                || decision == Decision.CONFIRMATION_REQUIRED && affectedMuscles.isEmpty()) {
            throw new IllegalArgumentException("recovery decision and affected muscles do not agree");
        }
    }

    public static WorkoutRecoveryAssessment evaluate(
            String policyVersion,
            int minimumRecoveryHours,
            Instant checkedAt,
            Set<String> targetPrimaryMuscles,
            List<CompletedMuscleFact> completedFacts) {
        String version = requiredText(policyVersion, "policy version");
        Objects.requireNonNull(checkedAt, "checked at must not be null");
        if (minimumRecoveryHours <= 0) {
            throw new IllegalArgumentException("minimum recovery hours must be positive");
        }
        Set<String> targets = Objects.requireNonNull(
                        targetPrimaryMuscles, "target primary muscles must not be null")
                .stream()
                .map(WorkoutRecoveryAssessment::normalizeMuscle)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, Instant> latestByMuscle = new HashMap<>();
        for (CompletedMuscleFact fact : Objects.requireNonNull(
                completedFacts, "completed facts must not be null")) {
            Objects.requireNonNull(fact, "completed fact must not be null");
            for (String muscle : fact.primaryMuscles()) {
                String normalized = normalizeMuscle(muscle);
                if (targets.contains(normalized)) {
                    latestByMuscle.merge(normalized, fact.completedAt(), (left, right) ->
                            left.isAfter(right) ? left : right);
                }
            }
        }

        List<AffectedMuscle> affected = new ArrayList<>();
        latestByMuscle.forEach((muscle, completedAt) -> {
            long elapsedHours = Math.max(0L, Duration.between(completedAt, checkedAt).toHours());
            if (elapsedHours < minimumRecoveryHours) {
                affected.add(new AffectedMuscle(
                        muscle, elapsedHours, minimumRecoveryHours, completedAt));
            }
        });
        affected.sort(Comparator.comparing(AffectedMuscle::muscleGroup));
        Decision decision = affected.isEmpty() ? Decision.READY : Decision.CONFIRMATION_REQUIRED;
        return new WorkoutRecoveryAssessment(
                version, checkedAt, minimumRecoveryHours, decision, affected);
    }

    public enum Decision {
        READY,
        CONFIRMATION_REQUIRED
    }

    public record AffectedMuscle(
            String muscleGroup,
            long elapsedHours,
            int minimumRecoveryHours,
            Instant lastCompletedAt) {
        public AffectedMuscle {
            muscleGroup = normalizeMuscle(muscleGroup);
            if (elapsedHours < 0 || minimumRecoveryHours <= 0) {
                throw new IllegalArgumentException("recovery durations are invalid");
            }
            Objects.requireNonNull(lastCompletedAt, "last completed at must not be null");
        }
    }

    public record CompletedMuscleFact(Instant completedAt, Set<String> primaryMuscles) {
        public CompletedMuscleFact {
            Objects.requireNonNull(completedAt, "completed at must not be null");
            primaryMuscles = Objects.requireNonNull(primaryMuscles, "primary muscles must not be null")
                    .stream()
                    .map(WorkoutRecoveryAssessment::normalizeMuscle)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static String normalizeMuscle(String value) {
        return requiredText(value, "muscle group").toUpperCase(Locale.ROOT);
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
