package com.aifitness.assistant.workout.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reads effective, completed exercise facts for one authenticated user. */
@FunctionalInterface
public interface WorkoutRecoveryFactQuery {
    List<CompletedExerciseFact> findCompletedExerciseFacts(UUID userId, Instant completedAfter);

    record CompletedExerciseFact(
            UUID sessionId, Instant completedAt, String exerciseCode, String contentVersion) {
        public CompletedExerciseFact {
            Objects.requireNonNull(sessionId, "session id must not be null");
            Objects.requireNonNull(completedAt, "completed at must not be null");
            if (exerciseCode == null || exerciseCode.isBlank()) {
                throw new IllegalArgumentException("exercise code must not be blank");
            }
            exerciseCode = exerciseCode.trim();
            if (contentVersion == null || contentVersion.isBlank()) {
                throw new IllegalArgumentException("content version must not be blank");
            }
            contentVersion = contentVersion.trim();
        }
    }
}
