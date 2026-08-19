package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.Comparator;
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
    public synchronized StartState findStartStateForUpdate(UUID userId, String clientSessionKey) {
        Optional<WorkoutSession> exact = findByUserAndClientKey(userId, clientSessionKey);
        Optional<WorkoutSession> active = sessions.values().stream()
                .filter(session -> session.userId().equals(userId) && !session.status().terminal())
                .sorted(Comparator.comparing(WorkoutSession::startedAt).thenComparing(WorkoutSession::id))
                .findFirst();
        return new StartState(exact, active);
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

    @Override
    public synchronized WorkoutSession complete(WorkoutSession terminalSession, long expectedVersion) {
        if (!terminalSession.status().terminal() || terminalSession.version() != expectedVersion + 2) {
            throw new IllegalArgumentException("atomic completion must contain both validated transitions");
        }
        return update(terminalSession, expectedVersion);
    }

    @Override
    public synchronized WorkoutSession replaceExercise(
            UUID userId, UUID sessionId, UUID snapshotId, long expectedVersion,
            WorkoutExerciseSnapshot replacement) {
        WorkoutSession current = findByIdAndUser(sessionId, userId)
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        if (current.version() != expectedVersion) {
            throw new WorkoutSessionService.VersionConflictException(current.version());
        }
        if (current.status() != com.aifitness.assistant.workout.domain.WorkoutStatus.IN_PROGRESS
                && current.status() != com.aifitness.assistant.workout.domain.WorkoutStatus.PAUSED) {
            throw new IllegalStateException("workout session does not accept exercise replacement");
        }
        boolean found = current.exercises().stream().anyMatch(exercise -> exercise.id().equals(snapshotId));
        if (!found || !replacement.id().equals(snapshotId) || !replacement.sessionId().equals(sessionId)) {
            throw new WorkoutSessionService.SessionNotFoundException();
        }
        WorkoutSession updated = new WorkoutSession(
                current.id(), current.userId(), current.planId(), current.planVersionId(),
                current.planVersionNumber(), current.trainingDayId(), current.trainingDayCode(),
                current.clientSessionKey(), current.status(), current.startedAt(), current.completedAt(),
                current.version() + 1, current.exercises().stream()
                        .map(exercise -> exercise.id().equals(snapshotId) ? replacement : exercise).toList(),
                current.warmupPrescription());
        sessions.put(sessionId, updated);
        return updated;
    }

    @Override
    public synchronized List<WorkoutSession> findHistory(
            UUID userId, Optional<Instant> beforeStartedAt, Optional<UUID> beforeId, int limit) {
        if (limit < 1) throw new IllegalArgumentException("history limit must be positive");
        Comparator<WorkoutSession> order = Comparator.comparing(WorkoutSession::startedAt).reversed()
                .thenComparing(session -> session.id().toString(), Comparator.reverseOrder());
        return sessions.values().stream()
                .filter(session -> session.userId().equals(userId) && session.status().terminal())
                .filter(session -> beforeStartedAt.map(before -> session.startedAt().isBefore(before)
                        || session.startedAt().equals(before)
                        && beforeId.map(id -> session.id().toString().compareTo(id.toString()) < 0).orElse(false))
                        .orElse(true))
                .sorted(order)
                .limit(limit)
                .toList();
    }

    private record UserKey(UUID userId, String clientSessionKey) {}
}
