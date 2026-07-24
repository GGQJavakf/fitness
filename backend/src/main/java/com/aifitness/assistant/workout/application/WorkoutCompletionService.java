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

    public WorkoutCompletionService(WorkoutSessionRepository sessions, WorkoutSetRepository sets, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.sets = Objects.requireNonNull(sets);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result complete(AuthenticatedUserId user, UUID sessionId, long expectedVersion, CompletionType type) {
        WorkoutSession current = sessions.findByIdAndUser(sessionId, user.value())
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        List<WorkoutSet> facts = sets.findBySession(user.value(), sessionId);
        List<WorkoutSet> completedFacts = WorkoutFactSummary.completedPrescribedWorkSets(current, facts);
        long completed = completedFacts.size();
        int required = current.exercises().stream().mapToInt(exercise -> exercise.prescription().workSets()).sum();
        boolean complete = completed == required && !WorkoutFactSummary.hasFailedOrSkippedWorkSet(facts);
        if (current.status().terminal()) {
            if ((type == CompletionType.FULL && current.status() == WorkoutStatus.COMPLETED)
                    || (type == CompletionType.EARLY_END && current.status() == WorkoutStatus.ABORTED)) {
                return result(current, completed, current.status() == WorkoutStatus.COMPLETED, facts);
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
        WorkoutSession terminal = sessions.complete(
                completing.transitionTo(
                        type == CompletionType.FULL ? WorkoutStatus.COMPLETED : WorkoutStatus.ABORTED,
                        clock.instant()),
                expectedVersion);
        return result(terminal, completed, type == CompletionType.FULL, facts);
    }

    private static Result result(WorkoutSession session, long completed, boolean complete, List<WorkoutSet> facts) {
        boolean eligible = complete && facts.stream().noneMatch(set -> set.anomalyStatus().isPresent());
        return new Result(session, Math.toIntExact(completed), complete, eligible);
    }

    public enum CompletionType { FULL, EARLY_END }
    public static final class IncompleteWorkoutException extends RuntimeException {}
    public record Result(WorkoutSession session, int completedWorkSets, boolean complete,
                         boolean automaticProgressionEligible) {}
}
