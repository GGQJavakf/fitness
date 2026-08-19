package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutHistoryRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** In-process counterpart of the JDBC history projection. */
public final class InMemoryWorkoutHistoryRepository implements WorkoutHistoryRepository {
    private final WorkoutSessionRepository sessions;
    private final WorkoutSetRepository sets;

    public InMemoryWorkoutHistoryRepository(
            WorkoutSessionRepository sessions, WorkoutSetRepository sets) {
        this.sessions = Objects.requireNonNull(sessions);
        this.sets = Objects.requireNonNull(sets);
    }

    @Override
    public List<Projection> findHistory(
            UUID userId, Optional<Instant> beforeStartedAt, Optional<UUID> beforeId, int limit) {
        return sessions.findHistory(userId, beforeStartedAt, beforeId, limit).stream().map(session -> {
            Set<Position> seen = new HashSet<>();
            List<WorkoutSet> completed = sets.findBySession(userId, session.id()).stream()
                    .filter(set -> set.setType() == WorkoutSet.SetType.WORK)
                    .filter(set -> set.completionStatus() == WorkoutSet.CompletionStatus.COMPLETED)
                    .filter(set -> session.exercises().stream().anyMatch(exercise ->
                            exercise.id().equals(set.sessionExerciseId())
                                    && set.setOrder() <= exercise.prescription().workSets()))
                    .filter(set -> seen.add(new Position(set.sessionExerciseId(), set.setOrder())))
                    .toList();
            BigDecimal volume = completed.stream()
                    .map(set -> set.actual().weight().multiply(BigDecimal.valueOf(set.actual().reps())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .stripTrailingZeros();
            int reps = completed.stream().mapToInt(set -> set.actual().reps()).sum();
            boolean externalLoad = completed.stream()
                    .anyMatch(set -> set.actual().weight().compareTo(BigDecimal.ZERO) > 0);
            return new Projection(
                    session.id(), session.trainingDayCode(), session.trainingDayCode(),
                    session.status(), session.startedAt(),
                    session.completedAt().orElseThrow(), completed.size(), volume, reps, externalLoad);
        }).toList();
    }

    private record Position(UUID exerciseId, int setOrder) {}
}
