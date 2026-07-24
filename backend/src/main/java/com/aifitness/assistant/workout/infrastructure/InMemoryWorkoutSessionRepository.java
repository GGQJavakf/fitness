package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryWorkoutSessionRepository implements WorkoutSessionRepository {
    private final Map<UUID, WorkoutSession> sessions = new HashMap<>();
    private final Map<UserKey, UUID> keys = new HashMap<>();

    @Override
    public synchronized Optional<WorkoutSession> findByIdAndUser(UUID sessionId, UUID userId) {
        return Optional.ofNullable(sessions.get(sessionId)).filter(session -> session.userId().equals(userId));
    }

    @Override
    public synchronized Optional<WorkoutSession> findByUserAndClientKey(UUID userId, String clientSessionKey) {
        return Optional.ofNullable(keys.get(new UserKey(userId, clientSessionKey))).map(sessions::get);
    }

    @Override
    public synchronized WorkoutSession create(WorkoutSession session) {
        UserKey key = new UserKey(session.userId(), session.clientSessionKey());
        UUID existingId = keys.putIfAbsent(key, session.id());
        if (existingId != null) {
            WorkoutSession existing = sessions.get(existingId);
            if (!existing.hasSameSource(
                    session.planId(), session.planVersionNumber(), session.trainingDayCode())) {
                throw new WorkoutSessionService.IdempotencyConflictException();
            }
            return existing;
        }
        sessions.put(session.id(), session);
        return session;
    }

    @Override
    public synchronized WorkoutSession update(WorkoutSession session, long expectedVersion) {
        WorkoutSession current = sessions.get(session.id());
        if (current == null || !current.userId().equals(session.userId())) {
            throw new WorkoutSessionService.SessionNotFoundException();
        }
        if (current.version() != expectedVersion) {
            throw new WorkoutSessionService.VersionConflictException(current.version());
        }
        sessions.put(session.id(), session);
        return session;
    }

    private record UserKey(UUID userId, String clientSessionKey) {}
}
