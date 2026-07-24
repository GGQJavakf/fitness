package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
            return existing;
        }
        if (session.version() != expectedSessionVersion) {
            throw new WorkoutSessionService.VersionConflictException(session.version());
        }
        WorkoutSession updated = session.recordSet();
        sessions.update(updated, expectedSessionVersion);
        SaveResult saved = new SaveResult(candidate, updated.version());
        sets.put(key, saved);
        return saved;
    }

    public synchronized int count() {
        return sets.size();
    }

    private record SetKey(UUID sessionExerciseId, String clientSetKey) {}
}
