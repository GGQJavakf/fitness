package com.aifitness.assistant.workout.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Durable handoff between workout completion and idempotent progression processing. */
public interface WorkoutCompletionOutbox {
    void appendIfAbsent(CompletionEvent event);

    Optional<ClaimedEvent> claimNext(Instant now, Instant claimedUntil);

    void markProcessed(UUID eventId, UUID claimToken, Instant processedAt);

    void release(UUID eventId, UUID claimToken, Instant nextAttemptAt, String redactedError);

    <T> T inTransaction(Supplier<T> operation);

    static WorkoutCompletionOutbox discarding() {
        return new WorkoutCompletionOutbox() {
            @Override public void appendIfAbsent(CompletionEvent event) {}
            @Override public Optional<ClaimedEvent> claimNext(Instant now, Instant claimedUntil) {
                return Optional.empty();
            }
            @Override public void markProcessed(UUID eventId, UUID claimToken, Instant processedAt) {}
            @Override public void release(
                    UUID eventId, UUID claimToken, Instant nextAttemptAt, String redactedError) {}
            @Override public <T> T inTransaction(Supplier<T> operation) { return operation.get(); }
        };
    }

    record CompletionEvent(UUID id, UUID userId, UUID sessionId, Instant createdAt) {
        public CompletionEvent {
            if (id == null || userId == null || sessionId == null || createdAt == null) {
                throw new IllegalArgumentException("completion event fields must not be null");
            }
        }
    }

    record ClaimedEvent(UUID id, UUID userId, UUID sessionId, UUID claimToken, int attemptCount) {}
}
