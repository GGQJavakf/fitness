package com.aifitness.assistant.privacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrivacyExportRepository {

    ExportArtifact save(ExportArtifact artifact);

    Optional<ExportArtifact> findById(UUID id);

    record ExportArtifact(
            UUID id,
            UUID userId,
            String status,
            Instant generatedAt,
            Instant expiresAt,
            List<PrivacyDataPort.ResourceExport> resources,
            List<String> scope,
            List<String> excludedRetentionCategories) {
        public ExportArtifact {
            resources = List.copyOf(resources);
            scope = List.copyOf(scope);
            excludedRetentionCategories = List.copyOf(excludedRetentionCategories);
        }
    }
}
