package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.domain.FieldLock;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.UserProfile;
import com.aifitness.assistant.rules.domain.PlanGenerationEngine;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.rules.domain.PlanValidationEngine;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class PlanCandidateService {

    private final ProfileService profiles;
    private final TemplateQueryService templates;
    private final ExerciseQueryService exercises;
    private final PlanGenerationEngine generator;
    private final PlanValidationEngine validator;
    private final PlanRulePolicy policy;
    private final Clock clock;
    private final boolean aiEnabled;
    private final ConcurrentMap<String, CandidateEnvelope> generatedCandidates = new ConcurrentHashMap<>();

    public PlanCandidateService(
            ProfileService profiles,
            TemplateQueryService templates,
            ExerciseQueryService exercises,
            PlanGenerationEngine generator,
            PlanValidationEngine validator,
            PlanRulePolicy policy,
            Clock clock,
            boolean aiEnabled) {
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.templates = Objects.requireNonNull(templates, "templates must not be null");
        this.exercises = Objects.requireNonNull(exercises, "exercises must not be null");
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.aiEnabled = aiEnabled;
    }

    public GeneratedCandidates generate(
            AuthenticatedUserId user, long profileVersion, Map<String, Integer> lockedNumbers) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        UserProfile profile = profiles.getProfile(user);
        if (profile.version() != profileVersion) {
            throw new ProfileService.VersionConflictException(profile.version());
        }
        RuleReference reference = currentReference();
        List<PlanGenerationEngine.Template> eligibleTemplates = templates
                .list(user, Optional.of(profile.details().weeklyFrequency())).stream()
                .map(PlanCandidateService::toDomain)
                .toList();
        Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises = eligibleExercises(user);
        PlanGenerationEngine.GenerationResult result = generator.generate(
                new PlanGenerationEngine.GenerationInput(
                        reference,
                        profile.details().weeklyFrequency(),
                        profile.details().sessionMinutes(),
                        eligibleTemplates,
                        eligibleExercises,
                        lockedNumbers == null ? Map.of() : lockedNumbers));
        List<PlanVersionService.ValidationIssue> issues = result.issues().stream()
                .map(PlanCandidateService::toApplication)
                .toList();
        Map<String, FieldLock.Status> lockedOutcomes = result.lockedFieldOutcomes().keySet().stream()
                .collect(Collectors.toUnmodifiableMap(path -> path, ignored -> FieldLock.Status.USER_LOCKED));
        Optional<CandidateEnvelope> candidate = result.candidate()
                .map(value -> envelope(user, profile, value, lockedOutcomes));
        candidate.ifPresent(value -> generatedCandidates.put(candidateKey(user, value.candidateId()), value));
        return new GeneratedCandidates(
                GenerationStatus.valueOf(result.status().name()), candidate, issues, lockedOutcomes);
    }

    public List<PlanVersionService.ValidationIssue> validate(
            AuthenticatedUserId user, PlanDraft candidate, RuleReference reference) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(reference, "rule reference must not be null");
        if (!currentReference().equals(reference)) {
            throw new IllegalArgumentException("candidate rule reference is not active");
        }
        return validator.validate(
                toRules(candidate, reference), profiles.getProfile(user).details().sessionMinutes(), eligibleExercises(user))
                .stream().map(PlanCandidateService::toApplication).toList();
    }

    public RuleReference currentReference() {
        return new RuleReference(policy.version(), templates.version(), exercises.version());
    }

    public CandidateEnvelope candidate(AuthenticatedUserId user, String candidateId) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        CandidateEnvelope candidate = generatedCandidates.get(candidateKey(user, candidateId));
        if (candidate == null || !candidate.expiresAt().isAfter(clock.instant())) {
            if (candidate != null) {
                generatedCandidates.remove(candidateKey(user, candidateId));
            }
            throw new CandidateNotFoundException();
        }
        return candidate;
    }

    private static String candidateKey(AuthenticatedUserId user, String candidateId) {
        return user.value() + ":" + candidateId;
    }

    private CandidateEnvelope envelope(
            AuthenticatedUserId user,
            UserProfile profile,
            PlanGenerationEngine.Candidate candidate,
            Map<String, FieldLock.Status> locks) {
        String identity = user.value() + "|" + profile.version() + "|" + candidate
                + "|" + new java.util.TreeMap<>(locks);
        String candidateId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        ExplanationStatus status = aiEnabled ? ExplanationStatus.PENDING : ExplanationStatus.DEGRADED;
        String explanation = "计划结构和全部训练数字由规则版本 " + policy.version()
                + " 生成；未知初始重量保留为待校准状态。";
        Instant expiresAt = clock.instant().plus(15, ChronoUnit.MINUTES);
        return new CandidateEnvelope(
                candidateId, toPlanDraft(candidate, locks), candidate.ruleReference(), status, explanation, expiresAt);
    }

    private static PlanGenerationEngine.Template toDomain(PlanTemplateCatalog.Template template) {
        List<PlanGenerationEngine.Day> days = template.days().stream()
                .map(day -> new PlanGenerationEngine.Day(
                        day.code(), day.name(), day.exercises().stream()
                        .map(slot -> new PlanGenerationEngine.Exercise(
                                slot.exerciseCode(), slot.workSets(), slot.repMin(), slot.repMax(),
                                slot.restSeconds(), PlanGenerationEngine.WeightStatus.valueOf(
                                slot.initialWeightState())))
                        .toList()))
                .toList();
        return new PlanGenerationEngine.Template(
                template.code(), template.name(), template.sessionsPerWeek(), days);
    }

    private Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises(AuthenticatedUserId user) {
        Set<UUID> excluded = profiles.excludedExerciseIds(user);
        return exercises.list(user, ExerciseQueryService.Filter.none()).stream()
                .filter(exercise -> !excluded.contains(exercise.stableId()))
                .collect(Collectors.toUnmodifiableMap(
                        exercise -> exercise.code(),
                        exercise -> new PlanValidationEngine.ExerciseFacts(
                                exercise.movementPattern(), exercise.primaryMuscles())));
    }

    private static PlanDraft toPlanDraft(
            PlanGenerationEngine.Candidate candidate, Map<String, FieldLock.Status> locks) {
        return new PlanDraft(
                candidate.templateCode(), candidate.name(), candidate.days().stream()
                .map(day -> new PlanDraft.Day(
                        day.code(), day.name(), day.exercises().stream()
                        .map(exercise -> new PlanDraft.Exercise(
                                exercise.exerciseCode(), exercise.workSets(), exercise.repMin(), exercise.repMax(),
                                exercise.restSeconds(), PlanDraft.WeightStatus.valueOf(exercise.weightStatus().name())))
                        .toList()))
                .toList(), locks);
    }

    private static PlanGenerationEngine.Candidate toRules(PlanDraft plan, RuleReference reference) {
        return new PlanGenerationEngine.Candidate(
                plan.templateCode(), plan.name(), plan.days().stream()
                .map(day -> new PlanGenerationEngine.Day(
                        day.code(), day.name(), day.exercises().stream()
                        .map(exercise -> new PlanGenerationEngine.Exercise(
                                exercise.exerciseCode(), exercise.workSets(), exercise.repMin(), exercise.repMax(),
                                exercise.restSeconds(),
                                PlanGenerationEngine.WeightStatus.valueOf(exercise.weightStatus().name())))
                        .toList()))
                .toList(), reference);
    }

    private static PlanVersionService.ValidationIssue toApplication(
            PlanGenerationEngine.ValidationIssue issue) {
        return new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.valueOf(issue.severity().name()),
                issue.reasonCode(), issue.fieldPath());
    }

    public record GeneratedCandidates(
            GenerationStatus status,
            Optional<CandidateEnvelope> candidate,
            List<PlanVersionService.ValidationIssue> issues,
            Map<String, FieldLock.Status> lockedFieldOutcomes) {}

    public record CandidateEnvelope(
            String candidateId,
            PlanDraft plan,
            RuleReference ruleReference,
            ExplanationStatus explanationStatus,
            String explanation,
            Instant expiresAt) {}

    public enum ExplanationStatus { PENDING, DEGRADED }
    public enum GenerationStatus { CANDIDATE_READY, NO_CANDIDATE }

    public static final class CandidateNotFoundException extends RuntimeException {}
}
