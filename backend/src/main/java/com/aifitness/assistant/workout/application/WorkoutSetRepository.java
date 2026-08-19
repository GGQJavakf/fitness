package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutSetVoid;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface WorkoutSetRepository {
    SaveResult save(UUID userId, WorkoutSet candidate, long expectedSessionVersion);

    SaveResult correct(
            UUID userId,
            WorkoutSet candidate,
            long expectedSessionVersion,
            UUID conflictId,
            Instant correctedAt);

    VoidResult appendVoid(
            UUID userId,
            UUID sessionId,
            UUID setId,
            String idempotencyKey,
            String payloadDigest,
            long expectedSessionVersion,
            UUID voidId,
            Instant voidedAt);

    Optional<WorkoutSet> find(UUID userId, UUID sessionId, UUID sessionExerciseId, String clientSetKey);

    /** Returns the immutable source fact, including one that has been logically voided. */
    Optional<WorkoutSet> findById(UUID userId, UUID sessionId, UUID setId);

    Optional<WorkoutSetVoid> findVoid(UUID userId, UUID sessionId, UUID setId);

    /** Returns only effective facts; append-only void records are excluded. */
    List<WorkoutSet> findBySession(UUID userId, UUID sessionId);

    record SaveResult(WorkoutSet set, long sessionVersion, boolean duplicate) {}
    record VoidResult(WorkoutSetVoid voidFact, long sessionVersion, boolean duplicate) {}
}
