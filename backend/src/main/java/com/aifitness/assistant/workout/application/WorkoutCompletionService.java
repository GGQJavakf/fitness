package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class WorkoutCompletionService {
    private final WorkoutSessionRepository sessions;
    private final WorkoutSetRepository sets;
    private final Clock clock;
    private final List<WorkoutCompletionObserver> observers;
    private final WorkoutCompletionOutbox outbox;

    public WorkoutCompletionService(WorkoutSessionRepository sessions, WorkoutSetRepository sets, Clock clock) {
        this(sessions, sets, clock, List.of(), WorkoutCompletionOutbox.discarding());
    }

    public WorkoutCompletionService(WorkoutSessionRepository sessions, WorkoutSetRepository sets, Clock clock,
                                    List<WorkoutCompletionObserver> observers) {
        this(sessions, sets, clock, observers, WorkoutCompletionOutbox.discarding());
    }

    public WorkoutCompletionService(WorkoutSessionRepository sessions, WorkoutSetRepository sets, Clock clock,
                                    WorkoutCompletionOutbox outbox) {
        this(sessions, sets, clock, List.of(), outbox);
    }

    private WorkoutCompletionService(
            WorkoutSessionRepository sessions, WorkoutSetRepository sets, Clock clock,
            List<WorkoutCompletionObserver> observers, WorkoutCompletionOutbox outbox) {
        this.sessions = Objects.requireNonNull(sessions);
        this.sets = Objects.requireNonNull(sets);
        this.clock = Objects.requireNonNull(clock);
        this.observers = List.copyOf(Objects.requireNonNull(observers));
        this.outbox = Objects.requireNonNull(outbox);
    }

    public Result complete(AuthenticatedUserId user, UUID sessionId, long expectedVersion, CompletionType type) {
        WorkoutSession current = sessions.findByIdAndUser(sessionId, user.value())
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        List<WorkoutSet> facts = sets.findBySession(user.value(), sessionId);
        List<WorkoutSet> completedFacts = WorkoutFactSummary.completedPrescribedWorkSets(current, facts);
        long completed = completedFacts.size();
        int required = current.exercises().stream().mapToInt(exercise -> exercise.prescription().workSets()).sum();
        // Failed attempts remain immutable evidence for progression. Full completion depends on
        // successful prescribed positions, not on whether earlier attempts failed.
        boolean complete = completed == required;
        if (current.status().terminal()) {
            if ((type == CompletionType.FULL && current.status() == WorkoutStatus.COMPLETED)
                    || (type == CompletionType.EARLY_END && current.status() == WorkoutStatus.ABORTED)) {
                Result result = result(current, completed, current.status() == WorkoutStatus.COMPLETED, facts);
                if (result.complete()) {
                    outbox.inTransaction(() -> {
                        appendCompletionEvent(user, current);
                        return null;
                    });
                    notifyCompleted(user, current, facts);
                }
                return result;
            }
            throw new WorkoutSessionService.IdempotencyConflictException();
        }
        if (current.version() != expectedVersion) {
            throw new WorkoutSessionService.VersionConflictException(current.version());
        }
        if (type == CompletionType.FULL && !complete) {
            throw new IncompleteWorkoutException();
        }
        WorkoutSession completing = current.transitionTo(WorkoutStatus.COMPLETING, clock.instant());
        WorkoutSession candidate = completing.transitionTo(
                type == CompletionType.FULL ? WorkoutStatus.COMPLETED : WorkoutStatus.ABORTED,
                clock.instant());
        WorkoutSession terminal = outbox.inTransaction(() -> {
            WorkoutSession persisted = sessions.complete(candidate, expectedVersion);
            if (persisted.status() == WorkoutStatus.COMPLETED) appendCompletionEvent(user, persisted);
            return persisted;
        });
        Result result = result(terminal, completed, type == CompletionType.FULL, facts);
        if (result.complete()) notifyCompleted(user, terminal, facts);
        return result;
    }

    private void appendCompletionEvent(AuthenticatedUserId user, WorkoutSession session) {
        UUID eventId = UUID.nameUUIDFromBytes(
                ("WORKOUT_COMPLETED|" + session.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        outbox.appendIfAbsent(new WorkoutCompletionOutbox.CompletionEvent(
                eventId, user.value(), session.id(), session.completedAt().orElseThrow()));
    }

    private void notifyCompleted(AuthenticatedUserId user, WorkoutSession session, List<WorkoutSet> facts) {
        observers.forEach(observer -> observer.onCompleted(user, session, List.copyOf(facts)));
    }

    private static Result result(WorkoutSession session, long completed, boolean complete, List<WorkoutSet> facts) {
        boolean eligible = complete && facts.stream().noneMatch(set ->
                set.anomalyStatus().isPresent() || set.safetyFlag().isPresent());
        return new Result(session, Math.toIntExact(completed), complete, eligible);
    }

    public enum CompletionType { FULL, EARLY_END }
    public static final class IncompleteWorkoutException extends RuntimeException {}
    public record Result(WorkoutSession session, int completedWorkSets, boolean complete,
                         boolean automaticProgressionEligible) {}
}
