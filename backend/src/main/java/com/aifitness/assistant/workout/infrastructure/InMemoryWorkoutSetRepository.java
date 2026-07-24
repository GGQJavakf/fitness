package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryWorkoutSetRepository implements WorkoutSetRepository {
    private final WorkoutSessionRepository sessions;
    private final Map<SetKey, SaveResult> sets = new HashMap<>();

    public InMemoryWorkoutSetRepository(WorkoutSessionRepository sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    }

    @Override
    public synchronized SaveResult save(UUID userId, WorkoutSet candidate, long expectedSessionVersion) {
        WorkoutSession session = sessions.findByIdAndUser(candidate.sessionId(), userId)
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        if (session.exercises().stream().noneMatch(item -> item.id().equals(candidate.sessionExerciseId()))) {
            throw new WorkoutSessionService.SessionNotFoundException();
        }
        SetKey key = new SetKey(candidate.sessionExerciseId(), candidate.clientSetKey());
        SaveResult existing = sets.get(key);
        if (existing != null) {
            if (!existing.set().payloadDigest().equals(candidate.payloadDigest())) {
                throw new WorkoutSessionService.IdempotencyConflictException();
            }
            return new SaveResult(existing.set(), existing.sessionVersion(), true);
        }
        if (session.version() != expectedSessionVersion) {
            throw new WorkoutSessionService.VersionConflictException(session.version());
        }
        if (session.status().terminal()) {
            throw new WorkoutSetService.SessionNotAcceptingSetsException();
        }
        WorkoutSession updated = session.recordSet();
        sessions.update(updated, expectedSessionVersion);
        SaveResult saved = new SaveResult(candidate, updated.version(), false);
        sets.put(key, saved);
        return saved;
    }

    @Override
    public synchronized Optional<WorkoutSet> find(
            UUID userId, UUID sessionId, UUID sessionExerciseId, String clientSetKey) {
        return sessions.findByIdAndUser(sessionId, userId)
                .filter(session -> session.exercises().stream().anyMatch(item -> item.id().equals(sessionExerciseId)))
                .map(ignored -> sets.get(new SetKey(sessionExerciseId, clientSetKey)))
                .map(SaveResult::set);
    }

    @Override
    public synchronized List<WorkoutSet> findBySession(UUID userId, UUID sessionId) {
        if (sessions.findByIdAndUser(sessionId, userId).isEmpty()) {
            throw new WorkoutSessionService.SessionNotFoundException();
        }
        return sets.values().stream().map(SaveResult::set)
                .filter(set -> set.sessionId().equals(sessionId))
                .sorted(java.util.Comparator.comparingInt(WorkoutSet::setOrder))
                .toList();
    }

    public synchronized int count() {
        return sets.size();
    }

    private record SetKey(UUID sessionExerciseId, String clientSetKey) {}
}
