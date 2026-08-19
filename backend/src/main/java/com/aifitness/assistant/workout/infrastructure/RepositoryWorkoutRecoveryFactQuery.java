package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutRecoveryFactQuery;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Repository-backed query used by the local in-memory runtime. */
public final class RepositoryWorkoutRecoveryFactQuery implements WorkoutRecoveryFactQuery {
    private final WorkoutSessionRepository sessions;
    private final WorkoutSetRepository sets;

    public RepositoryWorkoutRecoveryFactQuery(
            WorkoutSessionRepository sessions, WorkoutSetRepository sets) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.sets = Objects.requireNonNull(sets, "sets must not be null");
    }

    @Override
    public List<CompletedExerciseFact> findCompletedExerciseFacts(
            UUID userId, Instant completedAfter) {
        Objects.requireNonNull(userId, "user id must not be null");
        Objects.requireNonNull(completedAfter, "completed after must not be null");
        return sessions.findHistory(userId, Optional.empty(), Optional.empty(), Integer.MAX_VALUE).stream()
                .filter(session -> session.status() == WorkoutStatus.COMPLETED)
                .filter(session -> !session.completedAt().orElseThrow().isBefore(completedAfter))
                .flatMap(session -> {
                    Map<UUID, WorkoutExerciseSnapshot> snapshots = session.exercises().stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    WorkoutExerciseSnapshot::id, Function.identity()));
                    return sets.findBySession(userId, session.id()).stream()
                            .filter(set -> set.completionStatus() == WorkoutSet.CompletionStatus.COMPLETED)
                            .filter(set -> set.setType() == WorkoutSet.SetType.WORK)
                            .map(WorkoutSet::sessionExerciseId)
                            .distinct()
                            .map(snapshots::get)
                            .filter(Objects::nonNull)
                            .map(snapshot -> new CompletedExerciseFact(
                                    session.id(), session.completedAt().orElseThrow(), snapshot.exerciseCode(),
                                    snapshot.contentVersion()));
                })
                .sorted(Comparator.comparing(CompletedExerciseFact::completedAt).reversed()
                        .thenComparing(CompletedExerciseFact::exerciseCode))
                .toList();
    }
}
