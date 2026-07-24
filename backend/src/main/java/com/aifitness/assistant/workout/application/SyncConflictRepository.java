package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.SyncConflict;
import java.util.List;
import java.util.UUID;

public interface SyncConflictRepository {
    SyncConflict save(SyncConflict conflict);
    List<SyncConflict> listOpen(UUID userId);
    SyncConflict resolve(UUID userId, UUID conflictId, SyncConflict.Resolution resolution, long expectedVersion);
}
