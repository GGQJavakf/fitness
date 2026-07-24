package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyExportRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryPrivacyExportRepository implements PrivacyExportRepository {

    private final Clock clock;
    private final Map<UUID, ExportArtifact> artifacts = new HashMap<>();

    public InMemoryPrivacyExportRepository(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public synchronized ExportArtifact save(ExportArtifact artifact) {
        artifacts.put(artifact.id(), artifact);
        removeExpired(clock.instant());
        return artifact;
    }

    @Override
    public synchronized Optional<ExportArtifact> findById(UUID id) {
        removeExpired(clock.instant());
        return Optional.ofNullable(artifacts.get(id));
    }

    private void removeExpired(Instant now) {
        artifacts.values().removeIf(artifact -> !now.isBefore(artifact.expiresAt()));
    }
}
