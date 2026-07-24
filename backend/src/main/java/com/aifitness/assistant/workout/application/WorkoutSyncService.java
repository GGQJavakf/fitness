package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.SyncConflict;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class WorkoutSyncService {
    private final WorkoutSetService setService;
    private final WorkoutSetRepository sets;
    private final SyncConflictRepository conflicts;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public WorkoutSyncService(
            WorkoutSetService setService, WorkoutSetRepository sets, SyncConflictRepository conflicts,
            Clock clock, Supplier<UUID> ids) {
        this.setService = Objects.requireNonNull(setService);
        this.sets = Objects.requireNonNull(sets);
        this.conflicts = Objects.requireNonNull(conflicts);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public List<ItemResult> apply(AuthenticatedUserId user, List<Operation> operations) {
        Objects.requireNonNull(user, "user must not be null");
        if (operations == null || operations.isEmpty() || operations.size() > 100) {
            throw new IllegalArgumentException("sync batch must contain between 1 and 100 operations");
        }
        List<ItemResult> results = new ArrayList<>(operations.size());
        for (Operation operation : operations) results.add(applyOne(user, operation));
        return List.copyOf(results);
    }

    private ItemResult applyOne(AuthenticatedUserId user, Operation operation) {
        if (operation.command().isEmpty()) {
            return new ItemResult(operation.clientOperationSeq(), ItemStatus.REJECTED, Optional.empty(),
                    Optional.of(operation.rejectionReason().orElse("INVALID_OPERATION")));
        }
        WorkoutSetService.Command command = operation.command().orElseThrow();
        try {
            var saved = setService.upsert(
                    user, operation.sessionId(), operation.clientKey(), operation.expectedSessionVersion(), command);
            return new ItemResult(operation.clientOperationSeq(),
                    saved.duplicate() ? ItemStatus.DUPLICATE : ItemStatus.APPLIED,
                    Optional.empty(), Optional.empty());
        } catch (WorkoutSessionService.IdempotencyConflictException exception) {
            WorkoutSet server = sets.find(user.value(), operation.sessionId(),
                            command.sessionExerciseId(), operation.clientKey())
                    .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
            SyncConflict conflict = conflicts.save(new SyncConflict(
                    ids.get(), user.value(), "WORKOUT_SET", operation.clientKey(),
                    evidence(command), evidence(server), SyncConflict.Status.OPEN, Optional.empty(), 0,
                    clock.instant(), Optional.empty()));
            return new ItemResult(operation.clientOperationSeq(), ItemStatus.CONFLICT,
                    Optional.of(conflict.id()), Optional.of("IDEMPOTENCY_KEY_REUSED"));
        } catch (WorkoutSetService.SessionNotAcceptingSetsException exception) {
            SyncConflict conflict = conflicts.save(new SyncConflict(
                    ids.get(), user.value(), "WORKOUT_SET", operation.clientKey(), evidence(command),
                    Map.of("reasonCode", "SESSION_TERMINAL"), SyncConflict.Status.OPEN, Optional.empty(), 0,
                    clock.instant(), Optional.empty()));
            return new ItemResult(operation.clientOperationSeq(), ItemStatus.CONFLICT,
                    Optional.of(conflict.id()), Optional.of("SESSION_TERMINAL"));
        } catch (RuntimeException exception) {
            return new ItemResult(operation.clientOperationSeq(), ItemStatus.REJECTED, Optional.empty(),
                    Optional.of(reason(exception)));
        }
    }

    public List<SyncConflict> listOpenConflicts(AuthenticatedUserId user) {
        Objects.requireNonNull(user, "user must not be null");
        return conflicts.listOpen(user.value());
    }

    public SyncConflict resolveConflict(
            AuthenticatedUserId user, UUID conflictId, SyncConflict.Resolution resolution, long expectedVersion) {
        Objects.requireNonNull(user, "user must not be null");
        return conflicts.resolve(user.value(), conflictId, resolution, expectedVersion);
    }

    private static Map<String, String> evidence(WorkoutSetService.Command command) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sessionExerciseId", command.sessionExerciseId().toString());
        values.put("setType", command.setType().name());
        values.put("setOrder", Integer.toString(command.setOrder()));
        values.put("targetWeightKg", command.target().weight().toPlainString());
        values.put("targetReps", command.target().reps().toString());
        values.put("actualWeightKg", command.actual().weight().toPlainString());
        values.put("actualReps", command.actual().reps().toString());
        values.put("remainingReps", command.remainingReps() == null ? "NONE" : command.remainingReps().toString());
        values.put("completionStatus", command.completionStatus().name());
        values.put("completedAt", command.completedAt().map(java.time.Instant::toString).orElse("NONE"));
        return values;
    }

    private static Map<String, String> evidence(WorkoutSet set) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sessionExerciseId", set.sessionExerciseId().toString());
        values.put("setType", set.setType().name());
        values.put("setOrder", Integer.toString(set.setOrder()));
        values.put("targetWeightKg", set.target().weight().toPlainString());
        values.put("targetReps", set.target().reps().toString());
        values.put("actualWeightKg", set.actual().weight().toPlainString());
        values.put("actualReps", set.actual().reps().toString());
        values.put("remainingReps", set.remainingReps() == null ? "NONE" : set.remainingReps().toString());
        values.put("completionStatus", set.completionStatus().name());
        values.put("completedAt", set.completedAt().map(java.time.Instant::toString).orElse("NONE"));
        values.put("payloadDigest", set.payloadDigest());
        return values;
    }

    private static String reason(RuntimeException exception) {
        if (exception instanceof WorkoutSessionService.VersionConflictException) return "VERSION_CONFLICT";
        if (exception instanceof WorkoutSessionService.SessionNotFoundException) return "RESOURCE_NOT_FOUND";
        if (exception instanceof WorkoutSetService.AnomalyConfirmationRequiredException) {
            return "ANOMALY_CONFIRMATION_REQUIRED";
        }
        return "VALIDATION_FAILED";
    }

    public record Operation(
            long clientOperationSeq, UUID sessionId, String clientKey, long expectedSessionVersion,
            Optional<WorkoutSetService.Command> command, Optional<String> rejectionReason) {
        public Operation {
            if (clientOperationSeq < 1) throw new IllegalArgumentException("operation sequence must be positive");
            command = Objects.requireNonNull(command);
            rejectionReason = Objects.requireNonNull(rejectionReason);
        }

        public static Operation upsert(
                long sequence, UUID sessionId, String clientKey, long expectedVersion,
                WorkoutSetService.Command command) {
            return new Operation(sequence, Objects.requireNonNull(sessionId), clientKey, expectedVersion,
                    Optional.of(command), Optional.empty());
        }

        public static Operation rejected(long sequence, String reason) {
            return new Operation(sequence, null, "rejected-operation", 0, Optional.empty(), Optional.of(reason));
        }
    }

    public record ItemResult(
            long clientOperationSeq, ItemStatus status, Optional<UUID> conflictId, Optional<String> reasonCode) {}
    public enum ItemStatus { APPLIED, DUPLICATE, CONFLICT, REJECTED }
}
