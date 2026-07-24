package com.aifitness.assistant.plan.domain;

import com.aifitness.assistant.common.domain.RuleReference;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TrainingPlanVersion(
        UUID id,
        UUID planId,
        int versionNumber,
        SourceType sourceType,
        PlanDraft plan,
        RuleReference ruleReference,
        Set<String> confirmedWarningCodes,
        Instant createdAt) {

    public TrainingPlanVersion {
        Objects.requireNonNull(id, "version id must not be null");
        Objects.requireNonNull(planId, "plan id must not be null");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(ruleReference, "ruleReference must not be null");
        confirmedWarningCodes = Set.copyOf(confirmedWarningCodes == null ? Set.of() : confirmedWarningCodes);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public enum SourceType {
        INITIAL,
        USER_EDIT,
        REBALANCE,
        PROGRESSION
    }
}
