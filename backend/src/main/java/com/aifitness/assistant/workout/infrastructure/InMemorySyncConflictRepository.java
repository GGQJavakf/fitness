package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.SyncConflictRepository;
import com.aifitness.assistant.workout.domain.SyncConflict;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import com.aifitness.assistant.workout.application.WorkoutSessionService;

public final class InMemorySyncConflictRepository implements SyncConflictRepository {
    private final Map<UUID, SyncConflict> conflicts = new LinkedHashMap<>();
    private final Clock clock;

    public InMemorySyncConflictRepository() {
        this(Clock.systemUTC());
    }

    public InMemorySyncConflictRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized SyncConflict save(SyncConflict conflict) {
        Optional<SyncConflict> existing = conflicts.values().stream()
                .filter(item -> SyncConflictRepository.sameOpenIdentity(item, conflict))
                .findFirst();
        if (existing.isPresent()) return existing.orElseThrow();
        conflicts.putIfAbsent(conflict.id(), conflict);
        return conflicts.get(conflict.id());
    }

    @Override
    public synchronized List<SyncConflict> listOpen(UUID userId) {
        List<SyncConflict> result = new ArrayList<>();
        conflicts.values().stream()
                .filter(item -> item.userId().equals(userId) && item.status() == SyncConflict.Status.OPEN)
                .forEach(result::add);
        return List.copyOf(result);
    }

    @Override
    public synchronized <T> ResolutionTransaction<T> resolve(
            UUID userId,
            UUID conflictId,
            SyncConflict.Resolution resolution,
            long expectedVersion,
            ResolutionAction<T> action) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        Objects.requireNonNull(action, "resolution action must not be null");
        SyncConflict current = conflicts.get(conflictId);
        if (current == null || !current.userId().equals(userId)) {
            throw new WorkoutSessionService.SessionNotFoundException();
        }
        if (current.status() == SyncConflict.Status.RESOLVED) {
            if (current.resolution().orElseThrow() != resolution || current.version() != expectedVersion + 1) {
                throw new WorkoutSessionService.VersionConflictException(current.version());
            }
            ResolutionActionResult<T> replay = action.execute(current, true);
            return new ResolutionTransaction<>(current, replay.value(), true);
        }
        if (current.version() != expectedVersion) {
            throw new WorkoutSessionService.VersionConflictException(current.version());
        }
        ResolutionActionResult<T> decision = action.execute(current, false);
        SyncConflict withFinalEvidence = new SyncConflict(
                current.id(), current.userId(), current.entityType(), current.entityKey(),
                current.localEvidence(), decision.serverEvidence(), current.status(), current.resolution(),
                current.version(), current.createdAt(), current.resolvedAt());
        SyncConflict resolved = withFinalEvidence.resolve(resolution, expectedVersion, clock.instant());
        conflicts.put(conflictId, resolved);
        return new ResolutionTransaction<>(resolved, decision.value(), false);
    }
}
