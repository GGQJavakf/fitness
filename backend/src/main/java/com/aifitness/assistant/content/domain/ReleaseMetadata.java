package com.aifitness.assistant.content.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ReleaseMetadata(
        String version,
        ReleaseStatus status,
        boolean enabled,
        Set<ContentEnvironment> environments,
        List<String> sourceReferences) {

    public ReleaseMetadata {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("content version is required");
        }
        Objects.requireNonNull(status, "release status must not be null");
        environments = Set.copyOf(Objects.requireNonNull(environments, "environments must not be null"));
        sourceReferences = List.copyOf(
                Objects.requireNonNull(sourceReferences, "source references must not be null"));
        if (sourceReferences.isEmpty() || sourceReferences.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("content source references are required");
        }
    }

    public boolean isEligibleFor(ContentEnvironment environment) {
        if (!enabled || !environments.contains(environment)) {
            return false;
        }
        if (environment == ContentEnvironment.PUBLIC) {
            return status == ReleaseStatus.PUBLIC_RELEASE_APPROVED;
        }
        return status == ReleaseStatus.AI_VALIDATED || status == ReleaseStatus.PUBLIC_RELEASE_APPROVED;
    }
}
