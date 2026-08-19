package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutCompletionOutbox;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class InMemoryWorkoutCompletionOutbox implements WorkoutCompletionOutbox {
    private final Map<UUID, StoredEvent> events = new LinkedHashMap<>();

    @Override
    public synchronized void appendIfAbsent(CompletionEvent event) {
        events.putIfAbsent(event.id(), new StoredEvent(event, Status.PENDING, null, null, 0, null));
    }

    @Override
    public synchronized Optional<ClaimedEvent> claimNext(Instant now, Instant claimedUntil) {
        return events.values().stream()
                .filter(event -> event.status() == Status.PENDING
                        && (event.nextAttemptAt() == null || !event.nextAttemptAt().isAfter(now))
                        || event.status() == Status.PROCESSING
                        && event.claimedUntil() != null && !event.claimedUntil().isAfter(now))
                .min(Comparator.comparing(value -> value.event().createdAt()))
                .map(event -> {
                    UUID token = UUID.randomUUID();
                    StoredEvent claimed = new StoredEvent(event.event(), Status.PROCESSING, claimedUntil, token,
                            event.attemptCount() + 1, null);
                    events.put(event.event().id(), claimed);
                    return new ClaimedEvent(event.event().id(), event.event().userId(), event.event().sessionId(),
                            token, claimed.attemptCount());
                });
    }

    @Override
    public synchronized void markProcessed(UUID eventId, UUID claimToken, Instant processedAt) {
        StoredEvent current = claimed(eventId, claimToken);
        events.put(eventId, new StoredEvent(current.event(), Status.PROCESSED, null, null,
                current.attemptCount(), null));
    }

    @Override
    public synchronized void release(
            UUID eventId, UUID claimToken, Instant nextAttemptAt, String redactedError) {
        StoredEvent current = claimed(eventId, claimToken);
        events.put(eventId, new StoredEvent(current.event(), Status.PENDING, null, null,
                current.attemptCount(), nextAttemptAt));
    }

    @Override
    public synchronized <T> T inTransaction(Supplier<T> operation) {
        Map<UUID, StoredEvent> snapshot = new LinkedHashMap<>(events);
        try {
            return operation.get();
        } catch (RuntimeException exception) {
            events.clear();
            events.putAll(snapshot);
            throw exception;
        }
    }

    public synchronized int eventCount() { return events.size(); }
    public synchronized long processedCount() {
        return events.values().stream().filter(event -> event.status() == Status.PROCESSED).count();
    }

    private StoredEvent claimed(UUID eventId, UUID claimToken) {
        StoredEvent current = events.get(eventId);
        if (current == null || current.status() != Status.PROCESSING
                || !claimToken.equals(current.claimToken())) {
            throw new IllegalStateException("completion outbox claim is stale");
        }
        return current;
    }

    private enum Status { PENDING, PROCESSING, PROCESSED }
    private record StoredEvent(
            CompletionEvent event, Status status, Instant claimedUntil, UUID claimToken,
            int attemptCount, Instant nextAttemptAt) {}
}
