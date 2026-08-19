package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.domain.WorkoutWarmupPrescription;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class WorkoutSessionService {
    private final WorkoutSessionRepository sessions;
    private final PlanWorkoutSnapshotQuery plans;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final Optional<WorkoutWarmupPrescriptionService> warmups;

    public WorkoutSessionService(
            WorkoutSessionRepository sessions,
            PlanWorkoutSnapshotQuery plans,
            Clock clock,
            Supplier<UUID> ids) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.warmups = Optional.empty();
    }

    public WorkoutSessionService(
            WorkoutSessionRepository sessions,
            PlanWorkoutSnapshotQuery plans,
            Clock clock,
            Supplier<UUID> ids,
            WorkoutWarmupPrescriptionService warmups) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.warmups = Optional.of(Objects.requireNonNull(warmups, "warmups must not be null"));
    }

    public WorkoutSession start(AuthenticatedUserId user, StartCommand command) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(command, "command must not be null");
        return sessions.findByUserAndClientKey(user.value(), command.clientSessionKey())
                .map(existing -> duplicate(existing, command))
                .orElseGet(() -> create(user, command));
    }

    public WorkoutSession get(AuthenticatedUserId user, UUID sessionId) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(sessionId, "session id must not be null");
        return sessions.findByIdAndUser(sessionId, user.value()).orElseThrow(SessionNotFoundException::new);
    }

    public WorkoutSession transition(
            AuthenticatedUserId user, UUID sessionId, WorkoutStatus target, long expectedVersion) {
        if (target == WorkoutStatus.COMPLETING || target == WorkoutStatus.COMPLETED) {
            throw new IllegalArgumentException("completion must use the authoritative completion service");
        }
        WorkoutSession current = get(user, sessionId);
        if (current.version() != expectedVersion) {
            throw new VersionConflictException(current.version());
        }
        WorkoutSession changed = current.transitionTo(target, clock.instant());
        return sessions.update(changed, expectedVersion);
    }

    private WorkoutSession create(AuthenticatedUserId user, StartCommand command) {
        PlanWorkoutSnapshotQuery.PlanDaySource source = plans.load(
                user.value(), command.planId(), command.planVersionNumber(), command.trainingDayCode());
        if (!source.planId().equals(command.planId())
                || source.versionNumber() != command.planVersionNumber()
                || !source.trainingDayCode().equals(command.trainingDayCode())) {
            throw new IllegalStateException("plan snapshot source does not match the requested reference");
        }
        UUID sessionId = ids.get();
        var exercises = source.exercises().stream().map(item -> new WorkoutExerciseSnapshot(
                ids.get(), sessionId, item.sourcePlanExerciseId(), item.order(), item.exerciseCode(),
                item.exerciseName(), item.contentVersion(), item.equipment(),
                new WorkoutExerciseSnapshot.Prescription(
                        item.workSets(), item.repMin(), item.repMax(), item.restSeconds(),
                        item.weightStatus(), item.targetWeightKg(), item.unit()),
                WorkoutExerciseSnapshot.Status.PENDING)).toList();
        Optional<WorkoutWarmupPrescription> warmupPrescription = warmups
                .map(service -> toSnapshot(service.prescribe(user, source.exercises()), exercises));
        WorkoutSession session = new WorkoutSession(
                sessionId, user.value(), source.planId(), source.planVersionId(), source.versionNumber(),
                source.trainingDayId(), source.trainingDayCode(), command.clientSessionKey(),
                WorkoutStatus.CREATED, clock.instant(), Optional.empty(), 0, exercises, warmupPrescription);
        return sessions.create(session);
    }

    private static WorkoutWarmupPrescription toSnapshot(
            WorkoutWarmupPrescriptionEngine.Prescription value,
            java.util.List<WorkoutExerciseSnapshot> exercises) {
        return new WorkoutWarmupPrescription(
                value.schemaVersion(),
                value.ruleVersion(),
                new WorkoutWarmupPrescription.GeneralWarmup(
                        value.generalWarmup().occurrences(), value.generalWarmup().durationSeconds()),
                value.rampWarmup().map(ramp -> {
                    WorkoutExerciseSnapshot exercise = exercises.stream()
                            .filter(item -> item.order() == ramp.exerciseOrder())
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("warmup exercise is missing from snapshot"));
                    return new WorkoutWarmupPrescription.RampWarmup(
                            exercise.id(),
                            ramp.exerciseOrder(),
                            WorkoutWarmupPrescription.RampStatus.valueOf(ramp.status().name()),
                            ramp.equipmentType(),
                            ramp.sets().stream()
                                    .map(set -> new WorkoutWarmupPrescription.RampSet(set.weight(), set.reps()))
                                    .toList(),
                            ramp.calibrationCode(),
                            ramp.calibrationMessage());
                }),
                value.countsTowardTrainingVolume(),
                value.countsTowardProgression());
    }

    private static WorkoutSession duplicate(WorkoutSession existing, StartCommand command) {
        if (!existing.hasSameSource(
                command.planId(), command.planVersionNumber(), command.trainingDayCode())) {
            throw new IdempotencyConflictException();
        }
        return existing;
    }

    public record StartCommand(
            String clientSessionKey,
            UUID planId,
            int planVersionNumber,
            String trainingDayCode) {
        public StartCommand {
            if (clientSessionKey == null || clientSessionKey.length() < 8 || clientSessionKey.length() > 128) {
                throw new IllegalArgumentException("clientSessionKey length must be between 8 and 128");
            }
            Objects.requireNonNull(planId, "planId must not be null");
            if (planVersionNumber < 1 || trainingDayCode == null || trainingDayCode.isBlank()) {
                throw new IllegalArgumentException("plan version and training day are required");
            }
        }
    }

    public static final class SessionNotFoundException extends RuntimeException {}
    public static final class IdempotencyConflictException extends RuntimeException {}

    public static final class VersionConflictException extends RuntimeException {
        private final long currentVersion;

        public VersionConflictException(long currentVersion) {
            this.currentVersion = currentVersion;
        }

        public long currentVersion() {
            return currentVersion;
        }
    }
}
