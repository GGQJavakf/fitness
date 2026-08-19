package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutSetVoid;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryWorkoutSetRepository implements WorkoutSetRepository {
    private final WorkoutSessionRepository sessions;
    private final Map<SetKey, SaveResult> sets = new HashMap<>();
    private final Map<UUID, WorkoutSetVoid> voidsBySetId = new HashMap<>();
    private final Map<VoidKey, UUID> voidsByIdempotencyKey = new HashMap<>();
    private final Map<UUID, SetRevision> revisionsByConflictId = new HashMap<>();

    public InMemoryWorkoutSetRepository(WorkoutSessionRepository sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    }

    @Override
    public synchronized VoidResult appendVoid(
            UUID userId,
            UUID sessionId,
            UUID setId,
            String idempotencyKey,
            String payloadDigest,
            long expectedSessionVersion,
            UUID voidId,
            Instant voidedAt) {
        VoidKey key = new VoidKey(userId, idempotencyKey);
        UUID existingSetId = voidsByIdempotencyKey.get(key);
        if (existingSetId != null) {
            WorkoutSetVoid existing = voidsBySetId.get(existingSetId);
            if (!existing.payloadDigest().equals(payloadDigest)) {
                throw new WorkoutSessionService.IdempotencyConflictException();
            }
            return new VoidResult(existing, existing.appliedSessionVersion(), true);
        }
        WorkoutSession session = sessions.findByIdAndUser(sessionId, userId)
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        WorkoutSet source = sets.values().stream().map(SaveResult::set)
                .filter(set -> set.id().equals(setId) && set.sessionId().equals(sessionId))
                .findFirst().orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        WorkoutSetVoid existing = voidsBySetId.get(source.id());
        if (existing != null) {
            return new VoidResult(existing, existing.appliedSessionVersion(), true);
        }
        if (session.version() != expectedSessionVersion) {
            throw new WorkoutSessionService.VersionConflictException(session.version());
        }
        if (session.status().terminal()) {
            throw new WorkoutSetService.SessionNotAcceptingSetsException();
        }
        if (session.status() != com.aifitness.assistant.workout.domain.WorkoutStatus.IN_PROGRESS
                && session.status() != com.aifitness.assistant.workout.domain.WorkoutStatus.PAUSED) {
            throw new IllegalStateException("workout session does not accept set voids");
        }
        WorkoutSession updated = session.recordSet();
        WorkoutSetVoid voidFact = new WorkoutSetVoid(
                voidId, setId, sessionId, userId, idempotencyKey, payloadDigest,
                WorkoutSetVoid.Reason.USER_REQUESTED, updated.version(), voidedAt);
        sessions.update(updated, expectedSessionVersion);
        voidsBySetId.put(setId, voidFact);
        voidsByIdempotencyKey.put(key, setId);
        return new VoidResult(voidFact, updated.version(), false);
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
    public synchronized SaveResult correct(
            UUID userId,
            WorkoutSet candidate,
            long expectedSessionVersion,
            UUID conflictId,
            Instant correctedAt) {
        WorkoutSession session = sessions.findByIdAndUser(candidate.sessionId(), userId)
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        SetKey key = new SetKey(candidate.sessionExerciseId(), candidate.clientSetKey());
        SaveResult persisted = sets.get(key);
        if (persisted == null) throw new WorkoutSessionService.SessionNotFoundException();
        WorkoutSet existing = persisted.set();
        ensureCorrectionKeepsIdentity(existing, candidate);
        if (session.status().terminal()) {
            throw new WorkoutSetService.SessionNotAcceptingSetsException();
        }
        if (existing.payloadDigest().equals(candidate.payloadDigest())) {
            return new SaveResult(existing, session.version(), true);
        }
        if (session.version() != expectedSessionVersion) {
            throw new WorkoutSessionService.VersionConflictException(session.version());
        }
        WorkoutSession updatedSession = session.recordSet();
        sessions.update(updatedSession, expectedSessionVersion);
        revisionsByConflictId.putIfAbsent(
                conflictId, new SetRevision(existing, candidate, correctedAt));
        SaveResult corrected = new SaveResult(candidate, updatedSession.version(), false);
        sets.put(key, corrected);
        return corrected;
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
    public synchronized Optional<WorkoutSet> findById(UUID userId, UUID sessionId, UUID setId) {
        if (sessions.findByIdAndUser(sessionId, userId).isEmpty()) return Optional.empty();
        return sets.values().stream().map(SaveResult::set)
                .filter(set -> set.id().equals(setId) && set.sessionId().equals(sessionId))
                .findFirst();
    }

    @Override
    public synchronized Optional<WorkoutSetVoid> findVoid(UUID userId, UUID sessionId, UUID setId) {
        if (sessions.findByIdAndUser(sessionId, userId).isEmpty()) return Optional.empty();
        return Optional.ofNullable(voidsBySetId.get(setId))
                .filter(voidFact -> voidFact.sessionId().equals(sessionId));
    }

    @Override
    public synchronized List<WorkoutSet> findBySession(UUID userId, UUID sessionId) {
        if (sessions.findByIdAndUser(sessionId, userId).isEmpty()) {
            throw new WorkoutSessionService.SessionNotFoundException();
        }
        return sets.values().stream().map(SaveResult::set)
                .filter(set -> set.sessionId().equals(sessionId))
                .filter(set -> !voidsBySetId.containsKey(set.id()))
                .sorted(java.util.Comparator.comparingInt(WorkoutSet::setOrder))
                .toList();
    }

    public synchronized int count() {
        return sets.size();
    }

    public synchronized int revisionCount() {
        return revisionsByConflictId.size();
    }

    private static void ensureCorrectionKeepsIdentity(WorkoutSet existing, WorkoutSet candidate) {
        if (!existing.id().equals(candidate.id())
                || !existing.sessionId().equals(candidate.sessionId())
                || !existing.sessionExerciseId().equals(candidate.sessionExerciseId())
                || !existing.clientSetKey().equals(candidate.clientSetKey())
                || existing.clientOperationSeq() != candidate.clientOperationSeq()
                || existing.setType() != candidate.setType()
                || existing.setOrder() != candidate.setOrder()
                || !existing.target().equals(candidate.target())
                || candidate.serverRevision() != existing.serverRevision() + 1) {
            throw new IllegalArgumentException("workout set correction cannot change immutable identity fields");
        }
    }

    private record SetKey(UUID sessionExerciseId, String clientSetKey) {}
    private record VoidKey(UUID userId, String idempotencyKey) {}
    private record SetRevision(WorkoutSet before, WorkoutSet after, Instant correctedAt) {}
}
