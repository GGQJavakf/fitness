package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyExportRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryPrivacyExportRepository implements PrivacyExportRepository {

    private final Map<UUID, ExportArtifact> artifacts = new HashMap<>();

    @Override
    public synchronized ExportArtifact save(ExportArtifact artifact) {
        artifacts.put(artifact.id(), artifact);
        return artifact;
    }

    @Override
    public synchronized Optional<ExportArtifact> findById(UUID id) {
        return Optional.ofNullable(artifacts.get(id));
    }
}
