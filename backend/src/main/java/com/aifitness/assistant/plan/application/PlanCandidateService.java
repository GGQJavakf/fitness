package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
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
        Optional<CandidateEnvelope> candidate = result.candidate().map(value -> envelope(user, profile, value));
        return new GeneratedCandidates(
                result.status(), candidate, result.issues(), result.lockedFieldOutcomes());
    }

    public List<PlanGenerationEngine.ValidationIssue> validate(
            AuthenticatedUserId user, PlanGenerationEngine.Candidate candidate) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (!currentReference().equals(candidate.ruleReference())) {
            throw new IllegalArgumentException("candidate rule reference is not active");
        }
        return validator.validate(
                candidate, profiles.getProfile(user).details().sessionMinutes(), eligibleExercises(user));
    }

    public RuleReference currentReference() {
        return new RuleReference(policy.version(), templates.version(), exercises.version());
    }

    private CandidateEnvelope envelope(
            AuthenticatedUserId user, UserProfile profile, PlanGenerationEngine.Candidate candidate) {
        String identity = user.value() + "|" + profile.version() + "|" + candidate;
        String candidateId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        ExplanationStatus status = aiEnabled ? ExplanationStatus.PENDING : ExplanationStatus.DEGRADED;
        String explanation = "计划结构和全部训练数字由规则版本 " + policy.version()
                + " 生成；未知初始重量保留为待校准状态。";
        Instant expiresAt = clock.instant().plus(15, ChronoUnit.MINUTES);
        return new CandidateEnvelope(candidateId, candidate, status, explanation, expiresAt);
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

    public record GeneratedCandidates(
            PlanGenerationEngine.GenerationStatus status,
            Optional<CandidateEnvelope> candidate,
            List<PlanGenerationEngine.ValidationIssue> issues,
            Map<String, PlanGenerationEngine.LockStatus> lockedFieldOutcomes) {}

    public record CandidateEnvelope(
            String candidateId,
            PlanGenerationEngine.Candidate plan,
            ExplanationStatus explanationStatus,
            String explanation,
            Instant expiresAt) {}

    public enum ExplanationStatus { PENDING, DEGRADED }
}
