package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.SyncConflictRepository;
import com.aifitness.assistant.workout.domain.SyncConflict;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public synchronized SyncConflict resolve(
            UUID userId, UUID conflictId, SyncConflict.Resolution resolution, long expectedVersion) {
        SyncConflict current = conflicts.get(conflictId);
        if (current == null || !current.userId().equals(userId)) {
            throw new WorkoutSessionService.SessionNotFoundException();
        }
        try {
            SyncConflict resolved = current.resolve(resolution, expectedVersion, clock.instant());
            conflicts.put(conflictId, resolved);
            return resolved;
        } catch (IllegalStateException exception) {
            throw new WorkoutSessionService.VersionConflictException(current.version());
        }
    }
}
