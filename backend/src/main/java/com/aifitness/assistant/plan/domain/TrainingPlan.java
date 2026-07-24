package com.aifitness.assistant.plan.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TrainingPlan(
        UUID id,
        UUID userId,
        List<TrainingPlanVersion> versions,
        int activeVersionNumber) {

    public TrainingPlan {
        Objects.requireNonNull(id, "plan id must not be null");
        Objects.requireNonNull(userId, "user id must not be null");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions must not be null"));
        if (versions.isEmpty() || versions.stream().noneMatch(v -> v.versionNumber() == activeVersionNumber)) {
            throw new IllegalArgumentException("active version must exist");
        }
        if (versions.stream().anyMatch(version -> !version.planId().equals(id))) {
            throw new IllegalArgumentException("all versions must belong to the plan");
        }
    }

    public TrainingPlanVersion activeVersion() {
        return versions.stream()
                .filter(version -> version.versionNumber() == activeVersionNumber)
                .findFirst()
                .orElseThrow();
    }

    public TrainingPlanVersion version(int versionNumber) {
        return versions.stream()
                .filter(version -> version.versionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("plan version does not exist"));
    }

    public TrainingPlan append(TrainingPlanVersion version) {
        if (version.versionNumber() != activeVersionNumber + 1) {
            throw new IllegalArgumentException("new version must follow active version");
        }
        var updated = new java.util.ArrayList<>(versions);
        updated.add(version);
        return new TrainingPlan(id, userId, updated, version.versionNumber());
    }
}
