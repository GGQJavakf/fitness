package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.SyncConflict;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.UUID;

public interface SyncConflictRepository {
    SyncConflict save(SyncConflict conflict);
    List<SyncConflict> listOpen(UUID userId);

    static boolean sameOpenIdentity(SyncConflict left, SyncConflict right) {
        return left.status() == SyncConflict.Status.OPEN
                && right.status() == SyncConflict.Status.OPEN
                && left.userId().equals(right.userId())
                && left.entityType().equals(right.entityType())
                && left.entityKey().equals(right.entityKey())
                && identityEvidence(left.localEvidence()).equals(identityEvidence(right.localEvidence()));
    }

    private static Map<String, String> identityEvidence(Map<String, String> evidence) {
        TreeMap<String, String> identity = new TreeMap<>(evidence);
        identity.remove("expectedSessionVersion");
        if ("false".equals(identity.get("completedAtWasProvided"))) {
            identity.put("completedAt", "SERVER_ASSIGNED");
        }
        return Map.copyOf(identity);
    }

    default SyncConflict resolve(
            UUID userId, UUID conflictId, SyncConflict.Resolution resolution, long expectedVersion) {
        return resolve(
                userId, conflictId, resolution, expectedVersion,
                (conflict, replayed) -> new ResolutionActionResult<>(null, conflict.serverEvidence()))
                .conflict();
    }

    <T> ResolutionTransaction<T> resolve(
            UUID userId,
            UUID conflictId,
            SyncConflict.Resolution resolution,
            long expectedVersion,
            ResolutionAction<T> action);

    @FunctionalInterface
    interface ResolutionAction<T> {
        ResolutionActionResult<T> execute(SyncConflict conflict, boolean replayed);
    }

    record ResolutionActionResult<T>(T value, java.util.Map<String, String> serverEvidence) {}
    record ResolutionTransaction<T>(SyncConflict conflict, T value, boolean replayed) {}
}
