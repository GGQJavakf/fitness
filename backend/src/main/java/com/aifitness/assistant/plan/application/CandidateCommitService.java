package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.domain.FieldLock;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Validates and activates an edited candidate without ever materializing an intermediate version. */
public final class CandidateCommitService {
    private final PlanRepository plans;
    private final PlanVersionService.PlanPolicy policy;
    private final WarningConfirmationStore warnings;
    private final CandidateCommitReceiptStore receipts;
    private final CandidateCommitTransaction transactions;
    private final Clock clock;

    public CandidateCommitService(
            PlanRepository plans,
            PlanVersionService.PlanPolicy policy,
            WarningConfirmationStore warnings,
            CandidateCommitReceiptStore receipts,
            CandidateCommitTransaction transactions,
            Clock clock) {
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.warnings = Objects.requireNonNull(warnings, "warnings must not be null");
        this.receipts = Objects.requireNonNull(receipts, "receipts must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public PlanVersionService.VersionResult commit(
            AuthenticatedUserId user,
            String candidateId,
            int expectedActiveVersionNumber,
            PlanDraft proposed,
            Map<String, FieldLock.Status> locks,
            String warningConfirmationToken,
            String idempotencyKey) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        requireCandidateId(candidateId);
        if (expectedActiveVersionNumber < 0) {
            throw new IllegalArgumentException("expectedActiveVersionNumber must not be negative");
        }
        Objects.requireNonNull(proposed, "proposed plan must not be null");
        Map<String, FieldLock.Status> safeLocks = Map.copyOf(locks == null ? Map.of() : locks);
        requireIdempotencyKey(idempotencyKey);
        String keyDigest = digest(idempotencyKey);
        String payloadDigest = semanticDigest(candidateId, expectedActiveVersionNumber, proposed, safeLocks);

        Optional<CandidateCommitReceiptStore.Receipt> prior =
                receipts.find(user.value(), keyDigest, payloadDigest);
        if (prior.isPresent()) {
            return replay(user, prior.orElseThrow());
        }

        try {
            return transactions.execute(user.value(), () -> commitInTransaction(
                    user, candidateId, expectedActiveVersionNumber, proposed, safeLocks,
                    warningConfirmationToken, keyDigest, payloadDigest));
        } catch (NonCommitResult result) {
            return result.result();
        } catch (WarningRequired warning) {
            String token = warnings.issue(
                    user, warning.fingerprint(), clock.instant().plus(10, ChronoUnit.MINUTES));
            return new PlanVersionService.VersionResult(
                    PlanVersionService.VersionStatus.WARNING_CONFIRMATION_REQUIRED,
                    warning.plan(), warning.issues(), Optional.of(token), Optional.empty());
        }
    }

    private PlanVersionService.VersionResult commitInTransaction(
            AuthenticatedUserId user,
            String candidateId,
            int expectedActiveVersionNumber,
            PlanDraft proposed,
            Map<String, FieldLock.Status> locks,
            String warningConfirmationToken,
            String keyDigest,
            String payloadDigest) {
        CandidateCommitReceiptStore.Claim claim =
                receipts.claim(user.value(), keyDigest, payloadDigest);
        if (claim.replay().isPresent()) {
            return replay(user, claim.replay().orElseThrow());
        }

        PlanVersionService.CandidatePlan candidate = policy.candidate(user, candidateId);
        Optional<TrainingPlan> active = plans.findActiveByUser(user.value());
        int currentVersion = active.map(TrainingPlan::activeVersionNumber).orElse(0);
        if (currentVersion != expectedActiveVersionNumber) {
            throw new PlanVersionService.VersionConflictException(currentVersion);
        }

        PlanDraft candidateBase = active
                .map(plan -> candidate.plan().preserveLockedValues(plan.activeVersion().plan(), Map.of()))
                .orElse(candidate.plan());
        PlanDraft merged = preserveCandidateIdentity(proposed, candidateBase)
                .preserveLockedValues(candidateBase, locks);
        List<PlanVersionService.ValidationIssue> issues =
                List.copyOf(policy.validate(user, merged, candidate.ruleReference()));
        if (hasSeverity(issues, PlanVersionService.Severity.ERROR)) {
            throw new NonCommitResult(new PlanVersionService.VersionResult(
                    PlanVersionService.VersionStatus.VALIDATION_ERROR,
                    merged, issues, Optional.empty(), Optional.empty()));
        }

        String warningFingerprint = warningFingerprint(user, payloadDigest, issues);
        if (hasSeverity(issues, PlanVersionService.Severity.WARNING)
                && !warnings.consume(user, warningConfirmationToken, warningFingerprint, clock.instant())) {
            throw new WarningRequired(merged, issues, warningFingerprint);
        }

        Set<String> confirmedWarnings = issues.stream()
                .filter(issue -> issue.severity() == PlanVersionService.Severity.WARNING)
                .map(PlanVersionService.ValidationIssue::reasonCode)
                .collect(Collectors.toUnmodifiableSet());
        UUID planId = active.map(TrainingPlan::id).orElseGet(UUID::randomUUID);
        int versionNumber = expectedActiveVersionNumber + 1;
        TrainingPlanVersion version = new TrainingPlanVersion(
                UUID.randomUUID(), planId, versionNumber,
                expectedActiveVersionNumber == 0
                        ? TrainingPlanVersion.SourceType.INITIAL
                        : TrainingPlanVersion.SourceType.USER_EDIT,
                merged, candidate.ruleReference(), confirmedWarnings, clock.instant());
        TrainingPlan persisted = expectedActiveVersionNumber == 0
                ? plans.create(user.value(), version)
                : plans.append(user.value(), planId, expectedActiveVersionNumber, version);
        TrainingPlanVersion activeVersion = persisted.activeVersion();
        receipts.complete(
                user.value(), keyDigest, payloadDigest,
                persisted.id(), activeVersion.versionNumber(), activeVersion.id());
        return new PlanVersionService.VersionResult(
                PlanVersionService.VersionStatus.CREATED,
                activeVersion.plan(), issues, Optional.empty(), Optional.of(activeVersion));
    }

    private PlanVersionService.VersionResult replay(
            AuthenticatedUserId user, CandidateCommitReceiptStore.Receipt receipt) {
        TrainingPlanVersion version = plans.findByIdAndUser(receipt.planId(), user.value())
                .map(plan -> plan.version(receipt.versionNumber()))
                .filter(found -> found.id().equals(receipt.versionId()))
                .orElseThrow(() -> new IllegalStateException("candidate commit receipt points to a missing version"));
        return new PlanVersionService.VersionResult(
                PlanVersionService.VersionStatus.CREATED,
                version.plan(), List.of(), Optional.empty(), Optional.of(version));
    }

    private static PlanDraft preserveCandidateIdentity(PlanDraft proposed, PlanDraft candidate) {
        return new PlanDraft(
                candidate.templateCode(), candidate.trainingSplit(), proposed.name(), proposed.days(), proposed.locks(),
                candidate.presetCode(), candidate.presetVersion(),
                candidate.executionRules(), candidate.progressionRules(), candidate.movementImpactConstraint());
    }

    private static boolean hasSeverity(
            List<PlanVersionService.ValidationIssue> issues, PlanVersionService.Severity severity) {
        return issues.stream().anyMatch(issue -> issue.severity() == severity);
    }

    private static String warningFingerprint(
            AuthenticatedUserId user,
            String payloadDigest,
            List<PlanVersionService.ValidationIssue> issues) {
        String canonicalIssues = issues.stream()
                .sorted(Comparator.comparing(PlanVersionService.ValidationIssue::reasonCode)
                        .thenComparing(PlanVersionService.ValidationIssue::fieldPath))
                .map(issue -> issue.severity() + ":" + issue.reasonCode() + ":" + issue.fieldPath())
                .collect(Collectors.joining("|"));
        return digest(user.value() + "|" + payloadDigest + "|" + canonicalIssues);
    }

    private static String semanticDigest(
            String candidateId,
            int expectedActiveVersionNumber,
            PlanDraft plan,
            Map<String, FieldLock.Status> requestedLocks) {
        StringBuilder value = new StringBuilder(2048);
        append(value, candidateId);
        value.append(expectedActiveVersionNumber).append(';');
        appendPlan(value, plan);
        appendLocks(value, requestedLocks);
        return digest(value.toString());
    }

    private static void appendPlan(StringBuilder value, PlanDraft plan) {
        append(value, plan.templateCode());
        append(value, plan.trainingSplit() == null ? null : plan.trainingSplit().name());
        append(value, plan.name());
        append(value, plan.presetCode());
        append(value, plan.presetVersion());
        appendTexts(value, plan.executionRules());
        appendTexts(value, plan.progressionRules());
        append(value, plan.movementImpactConstraint() == null ? null : plan.movementImpactConstraint().name());
        appendLocks(value, plan.locks());
        value.append(plan.days().size()).append(';');
        for (PlanDraft.Day day : plan.days()) {
            append(value, day.code());
            append(value, day.name());
            append(value, day.weekday());
            append(value, day.focus());
            value.append(day.estimatedMinutesMin()).append(';').append(day.estimatedMinutesMax()).append(';');
            value.append(day.warmup().size()).append(';');
            for (PlanDraft.WarmupStep step : day.warmup()) {
                append(value, step.instruction());
                append(value, step.prescription());
                value.append(step.optional()).append(';');
            }
            appendTexts(value, day.notes());
            value.append(day.exercises().size()).append(';');
            for (PlanDraft.Exercise exercise : day.exercises()) {
                append(value, exercise.exerciseCode());
                value.append(exercise.workSets()).append(';')
                        .append(exercise.repMin()).append(';')
                        .append(exercise.repMax()).append(';')
                        .append(exercise.restSeconds()).append(';');
                append(value, exercise.weightStatus().name());
                append(value, exercise.targetWeightKg().map(CandidateCommitService::canonicalDecimal).orElse(null));
                append(value, nullable(exercise.targetRirMin()));
                append(value, nullable(exercise.targetRirMax()));
                append(value, nullable(exercise.eccentricSeconds()));
                value.append(exercise.perSide()).append(';');
                append(value, exercise.executionGroup());
                value.append(exercise.executionOrder()).append(';');
                PlanDraft.OptionalSetRule optional = exercise.optionalSetRule();
                if (optional == null) {
                    append(value, null);
                } else {
                    append(value, optional.conditionCode());
                    append(value, optional.exclusiveChoiceGroup());
                    value.append(optional.additionalSets()).append(';');
                }
                appendTexts(value, exercise.notes());
            }
        }
    }

    private static void appendLocks(StringBuilder value, Map<String, FieldLock.Status> locks) {
        List<Map.Entry<String, FieldLock.Status>> entries = new ArrayList<>(locks.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        value.append(entries.size()).append(';');
        for (Map.Entry<String, FieldLock.Status> entry : entries) {
            append(value, entry.getKey());
            append(value, entry.getValue().name());
        }
    }

    private static void appendTexts(StringBuilder value, List<String> texts) {
        value.append(texts.size()).append(';');
        texts.forEach(text -> append(value, text));
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
            return;
        }
        target.append(value.length()).append(':').append(value);
    }

    private static String canonicalDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String nullable(Integer value) {
        return value == null ? null : value.toString();
    }

    private static String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireCandidateId(String candidateId) {
        if (candidateId == null || candidateId.length() != 36) {
            throw new IllegalArgumentException("candidateId must be a canonical UUID");
        }
        try {
            if (!UUID.fromString(candidateId).toString().equals(candidateId)) {
                throw new IllegalArgumentException("candidateId must be a canonical UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("candidateId must be a canonical UUID");
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.length() < 8 || value.length() > 128
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 8 to 128 safe ASCII characters");
        }
    }

    private static final class NonCommitResult extends RuntimeException {
        private final PlanVersionService.VersionResult result;

        private NonCommitResult(PlanVersionService.VersionResult result) {
            super(null, null, false, false);
            this.result = result;
        }

        private PlanVersionService.VersionResult result() {
            return result;
        }
    }

    private static final class WarningRequired extends RuntimeException {
        private final PlanDraft plan;
        private final List<PlanVersionService.ValidationIssue> issues;
        private final String fingerprint;

        private WarningRequired(
                PlanDraft plan, List<PlanVersionService.ValidationIssue> issues, String fingerprint) {
            super(null, null, false, false);
            this.plan = plan;
            this.issues = List.copyOf(issues);
            this.fingerprint = fingerprint;
        }

        private PlanDraft plan() {
            return plan;
        }

        private List<PlanVersionService.ValidationIssue> issues() {
            return issues;
        }

        private String fingerprint() {
            return fingerprint;
        }
    }

    public static final class IdempotencyKeyReusedException extends RuntimeException {}
}
