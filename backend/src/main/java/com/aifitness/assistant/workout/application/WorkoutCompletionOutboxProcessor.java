package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Lease-based restart-safe consumer. Observer effects remain idempotent by source workout. */
public final class WorkoutCompletionOutboxProcessor {
    private final WorkoutCompletionOutbox outbox;
    private final WorkoutSessionRepository sessions;
    private final WorkoutSetRepository sets;
    private final List<WorkoutCompletionObserver> observers;
    private final Clock clock;
    private final Duration lease;
    private final Duration retryDelay;

    public WorkoutCompletionOutboxProcessor(
            WorkoutCompletionOutbox outbox,
            WorkoutSessionRepository sessions,
            WorkoutSetRepository sets,
            List<WorkoutCompletionObserver> observers,
            Clock clock,
            Duration lease,
            Duration retryDelay) {
        this.outbox = Objects.requireNonNull(outbox);
        this.sessions = Objects.requireNonNull(sessions);
        this.sets = Objects.requireNonNull(sets);
        this.observers = List.copyOf(Objects.requireNonNull(observers));
        this.clock = Objects.requireNonNull(clock);
        this.lease = Objects.requireNonNull(lease);
        this.retryDelay = Objects.requireNonNull(retryDelay);
        if (lease.isZero() || lease.isNegative() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("outbox timings must be positive");
        }
    }

    public boolean processNext() {
        var claimed = outbox.claimNext(clock.instant(), clock.instant().plus(lease));
        if (claimed.isEmpty()) return false;
        WorkoutCompletionOutbox.ClaimedEvent event = claimed.orElseThrow();
        try {
            outbox.inTransaction(() -> {
                WorkoutSession session = sessions.findByIdAndUser(event.sessionId(), event.userId())
                        .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
                if (session.status() != WorkoutStatus.COMPLETED) {
                    throw new IllegalStateException("completion outbox references a non-completed workout");
                }
                List<WorkoutSet> facts = List.copyOf(sets.findBySession(event.userId(), event.sessionId()));
                AuthenticatedUserId user = new AuthenticatedUserId(event.userId());
                observers.forEach(observer -> observer.onCompleted(user, session, facts));
                outbox.markProcessed(event.id(), event.claimToken(), clock.instant());
                return null;
            });
            return true;
        } catch (RuntimeException exception) {
            outbox.release(event.id(), event.claimToken(), clock.instant().plus(retryDelay),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
