package com.aifitness.assistant.plan.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Versioned, selectable plans whose prescriptions are owned by reviewed system content. */
public record SystemPlanPresetCatalog(List<Preset> presets) {

    public SystemPlanPresetCatalog {
        presets = List.copyOf(Objects.requireNonNull(presets, "presets must not be null"));
        long distinctCodes = presets.stream().map(Preset::code).distinct().count();
        if (distinctCodes != presets.size()) {
            throw new IllegalArgumentException("preset codes must be unique");
        }
    }

    public static SystemPlanPresetCatalog empty() {
        return new SystemPlanPresetCatalog(List.of());
    }

    public Optional<Preset> find(String code) {
        return presets.stream().filter(preset -> preset.code().equals(code)).findFirst();
    }

    public record Preset(
            String code,
            String version,
            String name,
            String experience,
            String goal,
            int weeklyFrequency,
            int sessionMinutes,
            String location,
            PlanDraft plan,
            ContentStatus contentStatus,
            ProfessionalReviewStatus professionalReviewStatus,
            String reviewRecordId,
            String reviewedAt,
            AvailabilityStatus availabilityStatus,
            String unavailableReason,
            IntroductoryPhase introductoryPhase,
            List<Source> sources,
            List<Source> explanationSources) {

        public Preset(
                String code,
                String version,
                String name,
                String experience,
                String goal,
                int weeklyFrequency,
                int sessionMinutes,
                String location,
                PlanDraft plan,
                ContentStatus contentStatus,
                ProfessionalReviewStatus professionalReviewStatus,
                String reviewRecordId,
                String reviewedAt,
                List<Source> sources,
                List<Source> explanationSources) {
            this(
                    code, version, name, experience, goal, weeklyFrequency, sessionMinutes, location,
                    plan, contentStatus, professionalReviewStatus, reviewRecordId, reviewedAt,
                    AvailabilityStatus.AVAILABLE, null, null, sources, explanationSources);
        }

        public Preset {
            if (code == null || code.isBlank() || version == null || version.isBlank()
                    || name == null || name.isBlank() || experience == null || experience.isBlank()
                    || goal == null || goal.isBlank()
                    || location == null || location.isBlank()) {
                throw new IllegalArgumentException("preset identity is required");
            }
            if (weeklyFrequency < 2 || weeklyFrequency > 6 || sessionMinutes < 1) {
                throw new IllegalArgumentException("preset schedule is invalid");
            }
            Objects.requireNonNull(plan, "preset plan must not be null");
            Objects.requireNonNull(contentStatus, "preset content status must not be null");
            Objects.requireNonNull(
                    professionalReviewStatus, "preset professional review status must not be null");
            Objects.requireNonNull(availabilityStatus, "preset availability status must not be null");
            boolean hasUnavailableReason = unavailableReason != null && !unavailableReason.isBlank();
            if (availabilityStatus == AvailabilityStatus.AVAILABLE && unavailableReason != null) {
                throw new IllegalArgumentException("available preset must not declare an unavailable reason");
            }
            if (availabilityStatus == AvailabilityStatus.BLOCKED_CAPABILITY && !hasUnavailableReason) {
                throw new IllegalArgumentException("capability-blocked preset requires an unavailable reason");
            }
            sources = validatedSources(sources, "sourceIds", true);
            explanationSources = validatedSources(
                    explanationSources, "explanationSourceIds", false);
            Set<String> primaryIds = sources.stream().map(Source::id).collect(java.util.stream.Collectors.toSet());
            if (explanationSources.stream().map(Source::id).anyMatch(primaryIds::contains)) {
                throw new IllegalArgumentException(
                        "sourceIds and explanationSourceIds must not overlap");
            }
            boolean hasReviewRecordId = reviewRecordId != null && !reviewRecordId.isBlank();
            boolean hasReviewedAt = reviewedAt != null && !reviewedAt.isBlank();
            if (professionalReviewStatus == ProfessionalReviewStatus.APPROVED) {
                if (!hasReviewRecordId || !hasReviewedAt) {
                    throw new IllegalArgumentException(
                            "approved professional review requires reviewRecordId and reviewedAt");
                }
            } else if (reviewRecordId != null || reviewedAt != null) {
                throw new IllegalArgumentException(
                        "pending professional review must not declare reviewRecordId or reviewedAt");
            }
            if (contentStatus == ContentStatus.PUBLIC_RELEASE_APPROVED
                    && professionalReviewStatus != ProfessionalReviewStatus.APPROVED) {
                throw new IllegalArgumentException(
                        "public release approval requires approved professional review");
            }
            if (plan.days().size() != weeklyFrequency
                    || !code.equals(plan.presetCode())
                    || !version.equals(plan.presetVersion())) {
                throw new IllegalArgumentException("preset plan identity does not match catalog metadata");
            }
        }

        private static List<Source> validatedSources(
                List<Source> values,
                String field,
                boolean required) {
            List<Source> copy = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
            if (required && copy.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            if (copy.stream().map(Source::id).distinct().count() != copy.size()) {
                throw new IllegalArgumentException(field + " must not contain duplicate source ids");
            }
            return copy;
        }
    }

    public record Source(
            String id,
            String title,
            String url,
            String internalSource,
            String usageBoundary,
            SourceKind sourceKind) {
        public Source {
            if (id == null || id.isBlank() || title == null || title.isBlank()
                    || usageBoundary == null || usageBoundary.isBlank()) {
                throw new IllegalArgumentException("preset source identity and usage boundary are required");
            }
            Objects.requireNonNull(sourceKind, "preset source kind must not be null");
            boolean hasUrl = url != null && !url.isBlank();
            boolean hasInternalSource = internalSource != null && !internalSource.isBlank();
            if (hasUrl == hasInternalSource) {
                throw new IllegalArgumentException(
                        "preset source must declare exactly one of url or internalSource");
            }
            if (sourceKind == SourceKind.INTERNAL_USER_PLAN && !hasInternalSource) {
                throw new IllegalArgumentException("internal user plan source requires internalSource");
            }
            if (sourceKind != SourceKind.INTERNAL_USER_PLAN && !hasUrl) {
                throw new IllegalArgumentException("external preset source requires url");
            }
        }
    }

    public enum ContentStatus {
        AI_DRAFT,
        AI_VALIDATED,
        PUBLIC_RELEASE_APPROVED,
        RETIRED
    }

    public enum ProfessionalReviewStatus {
        PENDING,
        APPROVED
    }

    public enum AvailabilityStatus {
        AVAILABLE,
        BLOCKED_CAPABILITY
    }

    public record IntroductoryPhase(
            int weeks,
            int workSets,
            int targetRirMin,
            int targetRirMax,
            String transitionCondition) {
        public IntroductoryPhase {
            if (weeks < 1 || weeks > 8 || workSets < 1 || workSets > 6
                    || targetRirMin < 0 || targetRirMax < targetRirMin || targetRirMax > 10
                    || transitionCondition == null || transitionCondition.isBlank()) {
                throw new IllegalArgumentException("preset introductory phase is invalid");
            }
        }
    }

    public enum SourceKind {
        PEER_REVIEWED_POSITION_STAND,
        PEER_REVIEWED_CONSENSUS_STATEMENT,
        PROFESSIONAL_ORGANIZATION_SUMMARY,
        GOVERNMENT_GUIDELINE,
        GOVERNMENT_PUBLIC_HEALTH_GUIDANCE,
        INTERNAL_USER_PLAN
    }
}
