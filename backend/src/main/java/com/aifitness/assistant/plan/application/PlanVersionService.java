package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.domain.FieldLock;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class PlanVersionService {

    private final PlanRepository plans;
    private final PlanPolicy policy;
    private final Clock clock;
    private final Map<String, PendingWarning> pendingWarnings = new ConcurrentHashMap<>();

    public PlanVersionService(PlanRepository plans, PlanPolicy policy, Clock clock) {
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public TrainingPlan createInitial(AuthenticatedUserId user, String candidateId) {
        requireUser(user);
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        UUID planId = initialPlanId(user, candidateId);
        Optional<TrainingPlan> active = plans.findActiveByUser(user.value());
        if (active.isPresent()) {
            if (active.get().id().equals(planId)) {
                return active.get();
            }
            throw new ActivePlanAlreadyExistsException();
        }
        CandidatePlan candidate = policy.candidate(user, candidateId);
        List<ValidationIssue> issues = policy.validate(user, candidate.plan(), candidate.ruleReference());
        if (hasSeverity(issues, Severity.ERROR)) {
            throw new PlanValidationException(issues);
        }
        Set<String> confirmedWarnings = issues.stream()
                .filter(issue -> issue.severity() == Severity.WARNING)
                .map(ValidationIssue::reasonCode)
                .collect(Collectors.toUnmodifiableSet());
        TrainingPlanVersion first = new TrainingPlanVersion(
                initialVersionId(planId), planId, 1, TrainingPlanVersion.SourceType.INITIAL,
                candidate.plan(), candidate.ruleReference(), confirmedWarnings, clock.instant());
        return plans.create(user.value(), first);
    }

    public TrainingPlan getActive(AuthenticatedUserId user) {
        requireUser(user);
        return plans.findActiveByUser(user.value()).orElseThrow(PlanNotFoundException::new);
    }

    public TrainingPlanVersion getVersion(AuthenticatedUserId user, UUID planId, int versionNumber) {
        requireUser(user);
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        TrainingPlan plan = plans.findByIdAndUser(planId, user.value()).orElseThrow(PlanNotFoundException::new);
        try {
            return plan.version(versionNumber);
        } catch (IllegalArgumentException ignored) {
            throw new PlanNotFoundException();
        }
    }

    public TrainingPlanVersion applyProgression(
            AuthenticatedUserId user,
            String exerciseCode,
            int expectedVersion,
            BigDecimal acceptedWeightKg) {
        requireUser(user);
        if (exerciseCode == null || exerciseCode.isBlank() || expectedVersion < 1) {
            throw new IllegalArgumentException("progression target and version are required");
        }
        Objects.requireNonNull(acceptedWeightKg, "accepted weight must not be null");
        TrainingPlan current = plans.findActiveByUser(user.value()).orElseThrow(PlanNotFoundException::new);
        if (current.activeVersionNumber() != expectedVersion) {
            throw new VersionConflictException(current.activeVersionNumber());
        }
        TrainingPlanVersion base = current.activeVersion();
        if (base.plan().isTargetWeightLocked(exerciseCode)) {
            throw new LockedProgressionFieldException();
        }
        PlanDraft proposed = base.plan().withTargetWeight(exerciseCode, acceptedWeightKg);
        List<ValidationIssue> issues = List.copyOf(policy.validate(user, proposed, base.ruleReference()));
        if (!issues.isEmpty()) {
            throw new PlanValidationException(issues);
        }
        TrainingPlanVersion version = new TrainingPlanVersion(
                UUID.randomUUID(), current.id(), expectedVersion + 1, TrainingPlanVersion.SourceType.PROGRESSION,
                proposed, base.ruleReference(), Set.of(), clock.instant());
        return plans.append(user.value(), current.id(), expectedVersion, version).activeVersion();
    }

    public VersionResult previewRebalance(
            AuthenticatedUserId user,
            UUID planId,
            int baseVersionNumber,
            PlanDraft proposed,
            Map<String, FieldLock.Status> locks) {
        return evaluate(user, planId, baseVersionNumber, proposed, locks, null, false);
    }

    public VersionResult createVersion(
            AuthenticatedUserId user,
            UUID planId,
            int baseVersionNumber,
            PlanDraft proposed,
            Map<String, FieldLock.Status> locks,
            String warningConfirmationToken) {
        return evaluate(user, planId, baseVersionNumber, proposed, locks, warningConfirmationToken, true);
    }

    private VersionResult evaluate(
            AuthenticatedUserId user,
            UUID planId,
            int baseVersionNumber,
            PlanDraft proposed,
            Map<String, FieldLock.Status> locks,
            String warningConfirmationToken,
            boolean save) {
        requireUser(user);
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(proposed, "proposed plan must not be null");
        TrainingPlan current = plans.findByIdAndUser(planId, user.value()).orElseThrow(PlanNotFoundException::new);
        if (current.activeVersionNumber() != baseVersionNumber) {
            throw new VersionConflictException(current.activeVersionNumber());
        }
        TrainingPlanVersion base = current.activeVersion();
        PlanDraft merged = proposed.preserveLockedValues(base.plan(), locks);
        List<ValidationIssue> issues = List.copyOf(policy.validate(user, merged, base.ruleReference()));
        if (hasSeverity(issues, Severity.ERROR)) {
            return new VersionResult(VersionStatus.VALIDATION_ERROR, merged, issues, Optional.empty(), Optional.empty());
        }
        if (!save) {
            return new VersionResult(VersionStatus.PREVIEW, merged, issues, Optional.empty(), Optional.empty());
        }
        if (hasSeverity(issues, Severity.WARNING)) {
            String fingerprint = fingerprint(user, planId, baseVersionNumber, merged, issues);
            if (!validToken(warningConfirmationToken, fingerprint)) {
                pendingWarnings.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
                String token = UUID.randomUUID().toString();
                pendingWarnings.put(token, new PendingWarning(fingerprint, clock.instant().plus(10, ChronoUnit.MINUTES)));
                return new VersionResult(
                        VersionStatus.WARNING_CONFIRMATION_REQUIRED, merged, issues,
                        Optional.of(token), Optional.empty());
            }
            pendingWarnings.remove(warningConfirmationToken);
        }
        Set<String> confirmedWarnings = issues.stream()
                .filter(issue -> issue.severity() == Severity.WARNING)
                .map(ValidationIssue::reasonCode)
                .collect(Collectors.toUnmodifiableSet());
        TrainingPlanVersion version = new TrainingPlanVersion(
                UUID.randomUUID(), planId, baseVersionNumber + 1, TrainingPlanVersion.SourceType.USER_EDIT,
                merged, base.ruleReference(), confirmedWarnings, clock.instant());
        TrainingPlan updated = plans.append(user.value(), planId, baseVersionNumber, version);
        return new VersionResult(
                VersionStatus.CREATED, merged, issues, Optional.empty(), Optional.of(updated.activeVersion()));
    }

    private boolean validToken(String token, String fingerprint) {
        if (token == null || token.isBlank()) {
            return false;
        }
        PendingWarning pending = pendingWarnings.get(token);
        return pending != null
                && pending.expiresAt().isAfter(clock.instant())
                && pending.fingerprint().equals(fingerprint);
    }

    private static String fingerprint(
            AuthenticatedUserId user,
            UUID planId,
            int baseVersion,
            PlanDraft plan,
            List<ValidationIssue> issues) {
        String value = user.value() + "|" + planId + "|" + baseVersion + "|" + plan + "|" + issues;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean hasSeverity(List<ValidationIssue> issues, Severity severity) {
        return issues.stream().anyMatch(issue -> issue.severity() == severity);
    }

    private static UUID initialPlanId(AuthenticatedUserId user, String candidateId) {
        String identity = user.value() + "|initial-plan|" + candidateId;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID initialVersionId(UUID planId) {
        return UUID.nameUUIDFromBytes(
                (planId + "|version|1").getBytes(StandardCharsets.UTF_8));
    }

    private static void requireUser(AuthenticatedUserId user) {
        Objects.requireNonNull(user, "authenticated user must not be null");
    }

    public interface PlanPolicy {
        CandidatePlan candidate(AuthenticatedUserId user, String candidateId);

        List<ValidationIssue> validate(AuthenticatedUserId user, PlanDraft plan, RuleReference reference);
    }

    public record CandidatePlan(String candidateId, PlanDraft plan, RuleReference ruleReference) {
        public CandidatePlan {
            if (candidateId == null || candidateId.isBlank()) {
                throw new IllegalArgumentException("candidateId must not be blank");
            }
            Objects.requireNonNull(plan, "plan must not be null");
            Objects.requireNonNull(ruleReference, "ruleReference must not be null");
        }
    }

    public record ValidationIssue(Severity severity, String reasonCode, String fieldPath) {
        public ValidationIssue {
            Objects.requireNonNull(severity, "severity must not be null");
            if (reasonCode == null || reasonCode.isBlank() || fieldPath == null || fieldPath.isBlank()) {
                throw new IllegalArgumentException("validation issue fields must not be blank");
            }
        }
    }

    public record VersionResult(
            VersionStatus status,
            PlanDraft plan,
            List<ValidationIssue> validationIssues,
            Optional<String> warningConfirmationToken,
            Optional<TrainingPlanVersion> version) {
        public VersionResult {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
            validationIssues = List.copyOf(validationIssues);
            warningConfirmationToken = warningConfirmationToken == null ? Optional.empty() : warningConfirmationToken;
            version = version == null ? Optional.empty() : version;
        }
    }

    public enum Severity { INFO, WARNING, ERROR }

    public enum VersionStatus { PREVIEW, WARNING_CONFIRMATION_REQUIRED, VALIDATION_ERROR, CREATED }

    private record PendingWarning(String fingerprint, Instant expiresAt) {}

    public static final class VersionConflictException extends RuntimeException {
        private final int currentVersion;

        public VersionConflictException(int currentVersion) {
            super("plan version conflict");
            this.currentVersion = currentVersion;
        }

        public int getCurrentVersion() {
            return currentVersion;
        }
    }

    public static final class PlanNotFoundException extends RuntimeException {}

    public static final class ActivePlanAlreadyExistsException extends RuntimeException {}

    public static final class LockedProgressionFieldException extends RuntimeException {}

    public static final class PlanValidationException extends RuntimeException {
        private final List<ValidationIssue> issues;

        public PlanValidationException(List<ValidationIssue> issues) {
            super("plan validation failed");
            this.issues = List.copyOf(issues);
        }

        public List<ValidationIssue> getIssues() {
            return issues;
        }
    }
}
