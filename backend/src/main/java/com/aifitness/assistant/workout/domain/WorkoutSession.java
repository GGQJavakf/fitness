package com.aifitness.assistant.workout.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record WorkoutSession(
        UUID id,
        UUID userId,
        UUID planId,
        UUID planVersionId,
        int planVersionNumber,
        UUID trainingDayId,
        String trainingDayCode,
        String clientSessionKey,
        WorkoutStatus status,
        Instant startedAt,
        Optional<Instant> completedAt,
        long version,
        List<WorkoutExerciseSnapshot> exercises,
        Optional<WorkoutWarmupPrescription> warmupPrescription) {

    public WorkoutSession {
        Objects.requireNonNull(id, "session id must not be null");
        Objects.requireNonNull(userId, "user id must not be null");
        Objects.requireNonNull(planId, "plan id must not be null");
        Objects.requireNonNull(planVersionId, "plan version id must not be null");
        Objects.requireNonNull(trainingDayId, "training day id must not be null");
        if (planVersionNumber < 1 || version < 0) {
            throw new IllegalArgumentException("session versions are invalid");
        }
        trainingDayCode = required(trainingDayCode, "training day code");
        clientSessionKey = required(clientSessionKey, "client session key");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (status.terminal() != completedAt.isPresent()) {
            throw new IllegalArgumentException("terminal status and completion time must agree");
        }
        exercises = List.copyOf(Objects.requireNonNull(exercises, "exercises must not be null"));
        if (exercises.isEmpty() || exercises.stream().anyMatch(item -> !item.sessionId().equals(id))) {
            throw new IllegalArgumentException("session must contain owned exercise snapshots");
        }
        warmupPrescription = Objects.requireNonNull(
                warmupPrescription, "warmup prescription must not be null");
    }

    public WorkoutSession(
            UUID id,
            UUID userId,
            UUID planId,
            UUID planVersionId,
            int planVersionNumber,
            UUID trainingDayId,
            String trainingDayCode,
            String clientSessionKey,
            WorkoutStatus status,
            Instant startedAt,
            Optional<Instant> completedAt,
            long version,
            List<WorkoutExerciseSnapshot> exercises) {
        this(
                id,
                userId,
                planId,
                planVersionId,
                planVersionNumber,
                trainingDayId,
                trainingDayCode,
                clientSessionKey,
                status,
                startedAt,
                completedAt,
                version,
                exercises,
                Optional.empty());
    }

    public WorkoutSession transitionTo(WorkoutStatus target, Instant changedAt) {
        Objects.requireNonNull(target, "target status must not be null");
        Objects.requireNonNull(changedAt, "changedAt must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("workout session cannot transition from " + status + " to " + target);
        }
        Optional<Instant> completion = target.terminal() ? Optional.of(changedAt) : Optional.empty();
        return new WorkoutSession(
                id, userId, planId, planVersionId, planVersionNumber, trainingDayId, trainingDayCode,
                clientSessionKey, target, startedAt, completion, version + 1, exercises, warmupPrescription);
    }

    public boolean hasSameSource(UUID expectedPlanId, int expectedVersion, String expectedDayCode) {
        return planId.equals(expectedPlanId)
                && planVersionNumber == expectedVersion
                && trainingDayCode.equals(expectedDayCode);
    }

    public WorkoutSession recordSet() {
        if (status != WorkoutStatus.IN_PROGRESS && status != WorkoutStatus.PAUSED) {
            throw new IllegalStateException("workout session does not accept set entries in " + status);
        }
        return new WorkoutSession(
                id, userId, planId, planVersionId, planVersionNumber, trainingDayId, trainingDayCode,
                clientSessionKey, status, startedAt, completedAt, version + 1, exercises, warmupPrescription);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
