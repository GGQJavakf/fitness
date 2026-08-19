package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class PlanCandidateService {

    private static final Pattern DAY_CODE = Pattern.compile("DAY_[1-6]");
    private static final int DEFAULT_MAXIMUM_CACHED_CANDIDATES = 512;

    private final ProfileService profiles;
    private final TemplateQueryService templates;
    private final ExerciseQueryService exercises;
    private final PlanGenerationEngine generator;
    private final PlanValidationEngine validator;
    private final PlanRulePolicy policy;
    private final Clock clock;
    private final PlanCandidateCache generatedCandidates;

    public PlanCandidateService(
            ProfileService profiles,
            TemplateQueryService templates,
            ExerciseQueryService exercises,
            PlanGenerationEngine generator,
            PlanValidationEngine validator,
            PlanRulePolicy policy,
            Clock clock) {
        this(profiles, templates, exercises, generator, validator, policy, clock,
                DEFAULT_MAXIMUM_CACHED_CANDIDATES);
    }

    public PlanCandidateService(
            ProfileService profiles,
            TemplateQueryService templates,
            ExerciseQueryService exercises,
            PlanGenerationEngine generator,
            PlanValidationEngine validator,
            PlanRulePolicy policy,
            Clock clock,
            int maximumCachedCandidates) {
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.templates = Objects.requireNonNull(templates, "templates must not be null");
        this.exercises = Objects.requireNonNull(exercises, "exercises must not be null");
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.generatedCandidates = new PlanCandidateCache(clock, maximumCachedCandidates);
    }

    public GenerationContext generationContext(AuthenticatedUserId user, long profileVersion) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        UserProfile profile = currentProfile(user, profileVersion);
        Set<UUID> preferred = profiles.preferredExerciseIds(user);
        List<GenerationExercise> eligible = eligibleExerciseCatalog(user, profile.details().experience()).stream()
                .map(exercise -> new GenerationExercise(
                        exercise.code(),
                        exercise.name(),
                        exercise.movementPattern(),
                        exercise.difficulty(),
                        exercise.equipment().stream().sorted().toList(),
                        exercise.primaryMuscles().stream().sorted().toList(),
                        preferred.contains(exercise.stableId()),
                        exercise.equipment().contains("BODYWEIGHT")))
                .toList();
        PlanRulePolicy.PlanLimits limits = policy.planLimits();
        PlanRulePolicy.Prescription prescription = policy.prescription();
        PlanRulePolicy.Rest rest = policy.rest();
        PlanRulePolicy.Duration duration = policy.duration();
        PlanRulePolicy.Balance balance = policy.balance();
        return new GenerationContext(
                new GenerationProfile(
                        profile.details().experience().name(),
                        profile.details().goal().name(),
                        profile.details().weeklyFrequency(),
                        profile.details().sessionMinutes(),
                        profile.details().location().name(),
                        profile.version()),
                eligible,
                new GenerationConstraints(
                        limits.minimumSessionsPerWeek(),
                        limits.maximumSessionsPerWeek(),
                        limits.maximumExercisesPerSession(),
                        prescription.minimumWorkSets(),
                        prescription.maximumWorkSets(),
                        prescription.minimumReps(),
                        prescription.maximumReps(),
                        rest.minimumSeconds(),
                        rest.maximumSeconds(),
                        duration.secondsPerWorkSet(),
                        duration.secondsPerExerciseTransition(),
                        balance.maximumMovementPatternOccurrencesPerSession(),
                        balance.maximumWorkSetsPerPrimaryMusclePerSession(),
                        balance.minimumRecoveryHoursBetweenPrimaryMuscleSessions()),
                currentReference());
    }

    public GeneratedCandidates generate(
            AuthenticatedUserId user, long profileVersion, Map<String, Integer> lockedNumbers) {
        return generate(user, profileVersion, lockedNumbers, null, null, true, null);
    }

    public GeneratedCandidates generate(
            AuthenticatedUserId user,
            long profileVersion,
            Map<String, Integer> lockedNumbers,
            String additionalRequirements,
            AiPlanProposal aiProposal,
            boolean fallbackAllowed) {
        return generate(user, profileVersion, lockedNumbers, additionalRequirements,
                aiProposal, fallbackAllowed, null);
    }

    public GeneratedCandidates generate(
            AuthenticatedUserId user,
            long profileVersion,
            Map<String, Integer> lockedNumbers,
            String additionalRequirements,
            AiPlanProposal aiProposal,
            boolean fallbackAllowed,
            TrainingSplit requestedSplit) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        UserProfile profile = currentProfile(user, profileVersion);
        TrainingSplit trainingSplit = requestedSplit;
        if (trainingSplit != null && !trainingSplit.supports(profile.details().weeklyFrequency())) {
            return noCandidate(
                    List.of(issue("SPLIT_FREQUENCY_MISMATCH", "/trainingSplit")),
                    lockedNumbers == null ? Map.of() : Map.copyOf(lockedNumbers));
        }
        Map<String, Integer> locks = lockedNumbers == null ? Map.of() : Map.copyOf(lockedNumbers);
        Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises =
                eligibleExercises(user, profile.details().experience());

        if (aiProposal != null) {
            AiPlanProposal ruleOwnedProposal = applyRulePrescription(profile.details().goal(), aiProposal);
            List<PlanVersionService.ValidationIssue> proposalIssues =
                    validateProposal(ruleOwnedProposal, profile, eligibleExercises);
            if (proposalIssues.isEmpty()) {
                PlanGenerationEngine.GenerationResult evaluated = generator.evaluate(
                        toAiCandidate(ruleOwnedProposal, currentReference(), eligibleExercises, trainingSplit),
                        profile.details().sessionMinutes(),
                        eligibleExercises,
                        locks);
                GeneratedCandidates generated = fromResult(
                        user, profile, evaluated, GenerationSource.AI_PERSONALIZED, trainingSplit);
                return generated;
            }
            return noCandidate(proposalIssues, locks);
        } else if (!fallbackAllowed) {
            return noCandidate(
                    List.of(issue("AI_PROPOSAL_REQUIRED", "/aiProposal")),
                    locks);
        }

        return generateFallback(user, profile, eligibleExercises, locks, trainingSplit);
    }

    public List<PlanVersionService.ValidationIssue> validate(
            AuthenticatedUserId user, PlanDraft candidate, RuleReference reference) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(reference, "rule reference must not be null");
        RuleReference activeReference = currentReference();
        UserProfile profile = profiles.getProfile(user);
        List<PlanVersionService.ValidationIssue> issues = new ArrayList<>(validator.validate(
                        toRules(candidate, activeReference),
                        profile.details().sessionMinutes(),
                        eligibleExercises(user, profile.details().experience()))
                .stream().map(PlanCandidateService::toApplication).toList());
        if (candidate.trainingSplit() != null) {
            TrainingSplit split = TrainingSplit.valueOf(candidate.trainingSplit().name());
            if (!split.supports(candidate.days().size())
                    || profile.details().weeklyFrequency() != candidate.days().size()) {
                issues.add(issue("SPLIT_FREQUENCY_MISMATCH", "/trainingSplit"));
            }
        }
        if (!activeReference.equals(reference)) {
            issues.add(new PlanVersionService.ValidationIssue(
                    PlanVersionService.Severity.WARNING,
                    "RULE_REFERENCE_UPGRADED",
                    "/ruleReference"));
        }
        return List.copyOf(issues);
    }

    public RuleReference currentReference() {
        return new RuleReference(policy.version(), templates.version(), exercises.version());
    }

    public CandidateEnvelope candidate(AuthenticatedUserId user, String candidateId) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        return generatedCandidates.get(candidateKey(user, candidateId))
                .orElseThrow(CandidateNotFoundException::new);
    }

    private GeneratedCandidates generateFallback(
            AuthenticatedUserId user,
            UserProfile profile,
            Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises,
            Map<String, Integer> locks,
            TrainingSplit trainingSplit) {
        List<PlanGenerationEngine.Template> eligibleTemplates = templates
                .listForGeneration(Optional.of(profile.details().weeklyFrequency())).stream()
                .filter(template -> trainingSplit == null || trainingSplit.acceptsTemplate(template.code()))
                .map(PlanCandidateService::toDomain)
                .toList();
        PlanGenerationEngine.GenerationResult result = generator.generate(
                new PlanGenerationEngine.GenerationInput(
                        currentReference(),
                        profile.details().weeklyFrequency(),
                        profile.details().sessionMinutes(),
                        PlanGenerationEngine.ExperienceLevel.valueOf(profile.details().experience().name()),
                        ruleGoal(profile.details().goal()),
                        eligibleTemplates,
                        eligibleExercises,
                        catalogExercises(),
                        policy,
                        locks));
        return fromResult(user, profile, result, GenerationSource.FALLBACK_RULE_PLAN, trainingSplit);
    }

    private static PlanGenerationEngine.FitnessGoal ruleGoal(UserProfile.FitnessGoal goal) {
        return goal == UserProfile.FitnessGoal.FAT_LOSS
                ? PlanGenerationEngine.FitnessGoal.GENERAL_FITNESS
                : PlanGenerationEngine.FitnessGoal.valueOf(goal.name());
    }

    private AiPlanProposal applyRulePrescription(UserProfile.FitnessGoal goal, AiPlanProposal proposal) {
        PlanRulePolicy.GoalPrescription prescription = policy.goalPrescriptions().get(ruleGoal(goal));
        return new AiPlanProposal(
                proposal.name(),
                proposal.days().stream()
                        .map(day -> new AiPlanDay(
                                day.code(),
                                day.name(),
                                day.exercises().stream()
                                        .map(exercise -> new AiPlanExercise(
                                                exercise.exerciseCode(),
                                                prescription.workSets(),
                                                prescription.repMin(),
                                                prescription.repMax(),
                                                prescription.restSeconds()))
                                        .toList()))
                        .toList());
    }

    private GeneratedCandidates fromResult(
            AuthenticatedUserId user,
            UserProfile profile,
            PlanGenerationEngine.GenerationResult result,
            GenerationSource source,
            TrainingSplit trainingSplit) {
        List<PlanVersionService.ValidationIssue> issues = result.issues().stream()
                .map(PlanCandidateService::toApplication)
                .toList();
        Map<String, FieldLock.Status> lockedOutcomes = lockedOutcomes(result.lockedFieldOutcomes().keySet());
        Optional<CandidateEnvelope> candidate = result.candidate()
                .map(value -> envelope(user, profile, value, lockedOutcomes, source, trainingSplit));
        candidate.ifPresent(value -> generatedCandidates.put(candidateKey(user, value.candidateId()), value));
        return new GeneratedCandidates(
                GenerationStatus.valueOf(result.status().name()), candidate, issues, lockedOutcomes);
    }

    private static GeneratedCandidates noCandidate(
            List<PlanVersionService.ValidationIssue> issues,
            Map<String, Integer> locks) {
        return new GeneratedCandidates(
                GenerationStatus.NO_CANDIDATE,
                Optional.empty(),
                List.copyOf(issues),
                lockedOutcomes(locks.keySet()));
    }

    private List<PlanVersionService.ValidationIssue> validateProposal(
            AiPlanProposal proposal,
            UserProfile profile,
            Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises) {
        List<PlanVersionService.ValidationIssue> issues = new ArrayList<>();
        if (!validAiText(proposal.name(), 80)) {
            issues.add(issue("PLAN_NAME_INVALID", "/aiProposal/name"));
        }
        if (proposal.days() == null || proposal.days().size() != profile.details().weeklyFrequency()) {
            issues.add(issue("SESSION_FREQUENCY_MISMATCH", "/aiProposal/days"));
            return List.copyOf(issues);
        }
        Set<String> dayCodes = new HashSet<>();
        for (int dayIndex = 0; dayIndex < proposal.days().size(); dayIndex++) {
            AiPlanDay day = proposal.days().get(dayIndex);
            String dayPath = "/aiProposal/days/" + dayIndex;
            if (day == null || !DAY_CODE.matcher(nullToEmpty(day.code())).matches()
                    || !dayCodes.add(day.code())) {
                issues.add(issue("DAY_CODE_INVALID", dayPath + "/code"));
                continue;
            }
            if (!validAiText(day.name(), 80)) {
                issues.add(issue("DAY_NAME_INVALID", dayPath + "/name"));
            }
            if (day.exercises() == null || day.exercises().isEmpty()
                    || day.exercises().size() > policy.planLimits().maximumExercisesPerSession()) {
                issues.add(issue("EXERCISE_COUNT_OUT_OF_RANGE", dayPath + "/exercises"));
                continue;
            }
            Set<String> codes = new HashSet<>();
            for (int exerciseIndex = 0; exerciseIndex < day.exercises().size(); exerciseIndex++) {
                AiPlanExercise exercise = day.exercises().get(exerciseIndex);
                String exercisePath = dayPath + "/exercises/" + exerciseIndex;
                if (exercise == null || !validText(exercise.exerciseCode(), 64)
                        || !eligibleExercises.containsKey(exercise.exerciseCode())) {
                    issues.add(issue("EXERCISE_NOT_ELIGIBLE", exercisePath + "/exerciseCode"));
                    continue;
                }
                if (!codes.add(exercise.exerciseCode())) {
                    issues.add(issue("DUPLICATE_EXERCISE", exercisePath + "/exerciseCode"));
                }
                if (exercise.workSets() < policy.prescription().minimumWorkSets()
                        || exercise.workSets() > policy.prescription().maximumWorkSets()) {
                    issues.add(issue("WORK_SETS_OUT_OF_RANGE", exercisePath + "/workSets"));
                }
                if (exercise.repMin() < policy.prescription().minimumReps()
                        || exercise.repMax() > policy.prescription().maximumReps()
                        || exercise.repMin() > exercise.repMax()) {
                    issues.add(issue("REP_RANGE_OUT_OF_RANGE", exercisePath + "/repRange"));
                }
                if (exercise.restSeconds() < policy.rest().minimumSeconds()
                        || exercise.restSeconds() > policy.rest().maximumSeconds()) {
                    issues.add(issue("REST_OUT_OF_RANGE", exercisePath + "/restSeconds"));
                }
            }
        }
        return List.copyOf(issues);
    }

    private static PlanGenerationEngine.Candidate toAiCandidate(
            AiPlanProposal proposal,
            RuleReference reference,
            Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises,
            TrainingSplit trainingSplit) {
        return new PlanGenerationEngine.Candidate(
                "AI_PERSONALIZED",
                proposal.name().trim(),
                java.util.stream.IntStream.range(0, proposal.days().size())
                        .mapToObj(dayIndex -> {
                            AiPlanDay day = proposal.days().get(dayIndex);
                            return new PlanGenerationEngine.Day(
                                day.code(),
                                day.name().trim(),
                                day.exercises().stream()
                                        .map(exercise -> {
                                            PlanValidationEngine.ExerciseFacts facts =
                                                    eligibleExercises.get(exercise.exerciseCode());
                                            return new PlanGenerationEngine.Exercise(
                                                    exercise.exerciseCode(),
                                                    exercise.workSets(),
                                                    exercise.repMin(),
                                                    exercise.repMax(),
                                                    exercise.restSeconds(),
                                                    facts.bodyweight()
                                                            ? PlanGenerationEngine.WeightStatus.BODYWEIGHT
                                                            : PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
                                        })
                                        .toList(),
                                trainingSplit == null
                                        ? PlanGenerationEngine.SessionFocus.forWeeklyIndex(
                                                proposal.days().size(), dayIndex)
                                        : PlanGenerationEngine.SessionFocus.forSplitIndex(
                                                PlanGenerationEngine.TrainingSplit.valueOf(trainingSplit.name()),
                                                proposal.days().size(), dayIndex));
                        })
                        .toList(),
                PlanGenerationEngine.WeightUnit.KG,
                reference);
    }

    private UserProfile currentProfile(AuthenticatedUserId user, long profileVersion) {
        UserProfile profile = profiles.getProfile(user);
        if (profile.version() != profileVersion) {
            throw new ProfileService.VersionConflictException(profile.version());
        }
        return profile;
    }

    private static String candidateKey(AuthenticatedUserId user, String candidateId) {
        return user.value() + ":" + candidateId;
    }

    private CandidateEnvelope envelope(
            AuthenticatedUserId user,
            UserProfile profile,
            PlanGenerationEngine.Candidate candidate,
            Map<String, FieldLock.Status> locks,
            GenerationSource source,
            TrainingSplit trainingSplit) {
        String identity = user.value() + "|" + profile.version() + "|" + source + "|" + candidate
                + "|" + new java.util.TreeMap<>(locks);
        String candidateId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        ExplanationStatus status = source == GenerationSource.AI_PERSONALIZED
                ? ExplanationStatus.PENDING
                : ExplanationStatus.DEGRADED;
        String explanation = source == GenerationSource.AI_PERSONALIZED
                ? "AI 已结合你的资料、器械和额外需求生成方案；服务端规则已校验训练时长、训练量与恢复边界。"
                : "已按你的资料、训练目标和可用器械生成规则计划；训练数字均经过服务端校验，未知初始重量保留为待校准状态。";
        Instant expiresAt = clock.instant().plus(15, ChronoUnit.MINUTES);
        return new CandidateEnvelope(
                candidateId,
                source,
                toPlanDraft(candidate, locks, trainingSplit),
                candidate.ruleReference(),
                status,
                explanation,
                expiresAt);
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

    private List<ExerciseCatalog.Exercise> eligibleExerciseCatalog(
            AuthenticatedUserId user, UserProfile.ExperienceLevel experience) {
        Set<UUID> excluded = profiles.excludedExerciseIds(user);
        return exercises.list(user, ExerciseQueryService.Filter.none()).stream()
                .filter(exercise -> !excluded.contains(exercise.stableId()))
                .filter(exercise -> ExerciseDifficultyEligibility.allows(experience, exercise.difficulty()))
                .toList();
    }

    private Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises(
            AuthenticatedUserId user, UserProfile.ExperienceLevel experience) {
        return eligibleExerciseCatalog(user, experience).stream()
                .collect(Collectors.toUnmodifiableMap(
                        ExerciseCatalog.Exercise::code,
                        PlanCandidateService::facts));
    }

    private Map<String, PlanValidationEngine.ExerciseFacts> catalogExercises() {
        return exercises.catalog().stream()
                .collect(Collectors.toUnmodifiableMap(
                        ExerciseCatalog.Exercise::code,
                        PlanCandidateService::facts));
    }

    private static PlanValidationEngine.ExerciseFacts facts(ExerciseCatalog.Exercise exercise) {
        return new PlanValidationEngine.ExerciseFacts(
                exercise.movementPattern(),
                exercise.primaryMuscles(),
                exercise.equipment().contains("BODYWEIGHT"));
    }

    private static Map<String, FieldLock.Status> lockedOutcomes(Set<String> paths) {
        return paths.stream().collect(Collectors.toUnmodifiableMap(
                path -> path,
                ignored -> FieldLock.Status.USER_LOCKED));
    }

    private static PlanDraft toPlanDraft(
            PlanGenerationEngine.Candidate candidate,
            Map<String, FieldLock.Status> locks,
            TrainingSplit trainingSplit) {
        return new PlanDraft(
                candidate.templateCode(), toDomainSplit(trainingSplit, candidate.templateCode()),
                candidate.name(), candidate.days().stream()
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
                plan.templateCode(), plan.name(), java.util.stream.IntStream.range(0, plan.days().size())
                .mapToObj(dayIndex -> {
                    PlanDraft.Day day = plan.days().get(dayIndex);
                    return new PlanGenerationEngine.Day(
                            day.code(), day.name(), day.exercises().stream()
                            .map(exercise -> new PlanGenerationEngine.Exercise(
                                    exercise.exerciseCode(), exercise.workSets(), exercise.repMin(), exercise.repMax(),
                                    exercise.restSeconds(),
                                    PlanGenerationEngine.WeightStatus.valueOf(exercise.weightStatus().name())))
                            .toList(),
                            persistedSessionFocus(plan, day, dayIndex));
                })
                .toList(), reference);
    }

    private static PlanGenerationEngine.SessionFocus persistedSessionFocus(
            PlanDraft plan, PlanDraft.Day day, int dayIndex) {
        if ("AI_PERSONALIZED".equals(plan.templateCode()) && plan.trainingSplit() != null) {
            return PlanGenerationEngine.SessionFocus.forSplitIndex(
                    PlanGenerationEngine.TrainingSplit.valueOf(plan.trainingSplit().name()),
                    plan.days().size(), dayIndex);
        }
        PlanGenerationEngine.SessionFocus namedFocus =
                PlanGenerationEngine.SessionFocus.infer(day.code(), day.name());
        if (namedFocus != PlanGenerationEngine.SessionFocus.FULL_BODY || plan.trainingSplit() == null) {
            return namedFocus;
        }
        return PlanGenerationEngine.SessionFocus.forSplitIndex(
                PlanGenerationEngine.TrainingSplit.valueOf(plan.trainingSplit().name()),
                plan.days().size(), dayIndex);
    }

    private static PlanDraft.TrainingSplit toDomainSplit(
            TrainingSplit requestedSplit, String templateCode) {
        return requestedSplit == null
                ? PlanDraft.inferTrainingSplit(templateCode)
                : PlanDraft.TrainingSplit.valueOf(requestedSplit.name());
    }

    private static PlanVersionService.ValidationIssue toApplication(
            PlanGenerationEngine.ValidationIssue issue) {
        return new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.valueOf(issue.severity().name()),
                issue.reasonCode(), issue.fieldPath());
    }

    private static PlanVersionService.ValidationIssue issue(String reasonCode, String fieldPath) {
        return new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.ERROR, reasonCode, fieldPath);
    }

    private static boolean validText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.trim().length() <= maximumLength;
    }

    private static boolean validAiText(String value, int maximumLength) {
        return validText(value, maximumLength)
                && TrainingPreferenceSafetyPolicy.normalize(value, maximumLength)
                        .filter(normalized -> !normalized.isBlank())
                        .isPresent()
                && !TrainingPreferenceSafetyPolicy.containsAbsoluteWeight(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record GenerationContext(
            GenerationProfile profile,
            List<GenerationExercise> exercises,
            GenerationConstraints constraints,
            RuleReference ruleReference) {}

    public record GenerationProfile(
            String experience,
            String goal,
            int weeklyFrequency,
            int sessionMinutes,
            String location,
            long profileVersion) {}

    public record GenerationExercise(
            String code,
            String name,
            String movementPattern,
            String difficulty,
            List<String> equipment,
            List<String> primaryMuscles,
            boolean preferred,
            boolean bodyweight) {}

    public record GenerationConstraints(
            int minimumSessionsPerWeek,
            int maximumSessionsPerWeek,
            int maximumExercisesPerSession,
            int minimumWorkSets,
            int maximumWorkSets,
            int minimumReps,
            int maximumReps,
            int minimumRestSeconds,
            int maximumRestSeconds,
            int secondsPerWorkSet,
            int secondsPerExerciseTransition,
            int maximumMovementPatternOccurrencesPerSession,
            int maximumWorkSetsPerPrimaryMusclePerSession,
            int minimumRecoveryHoursBetweenPrimaryMuscleSessions) {}

    public record AiPlanProposal(String name, List<AiPlanDay> days) {}

    public record AiPlanDay(String code, String name, List<AiPlanExercise> exercises) {}

    public record AiPlanExercise(
            String exerciseCode,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds) {}

    public record GeneratedCandidates(
            GenerationStatus status,
            Optional<CandidateEnvelope> candidate,
            List<PlanVersionService.ValidationIssue> issues,
            Map<String, FieldLock.Status> lockedFieldOutcomes) {}

    public record CandidateEnvelope(
            String candidateId,
            GenerationSource generationSource,
            PlanDraft plan,
            RuleReference ruleReference,
            ExplanationStatus explanationStatus,
            String explanation,
            Instant expiresAt) {

        public CandidateEnvelope(
                String candidateId,
                PlanDraft plan,
                RuleReference ruleReference,
                ExplanationStatus explanationStatus,
                String explanation,
                Instant expiresAt) {
            this(
                    candidateId,
                    GenerationSource.FALLBACK_RULE_PLAN,
                    plan,
                    ruleReference,
                    explanationStatus,
                    explanation,
                    expiresAt);
        }
    }

    public enum ExplanationStatus { PENDING, DEGRADED }
    public enum GenerationStatus { CANDIDATE_READY, NO_CANDIDATE }
    public enum GenerationSource { AI_PERSONALIZED, FALLBACK_RULE_PLAN }

    public enum TrainingSplit {
        UPPER_LOWER(Set.of(2, 4), Set.of("UPPER_LOWER_2_DAY_V1", "UPPER_LOWER_4_DAY_V1")),
        PUSH_PULL_LEGS(Set.of(3, 6), Set.of("PUSH_PULL_LEGS_3_DAY_V1", "PUSH_PULL_LEGS_6_DAY_V1")),
        BODY_PART_FIVE_DAY(Set.of(5), Set.of("BODY_PART_5_DAY_V1"));

        private final Set<Integer> frequencies;
        private final Set<String> templateCodes;

        TrainingSplit(Set<Integer> frequencies, Set<String> templateCodes) {
            this.frequencies = Set.copyOf(frequencies);
            this.templateCodes = Set.copyOf(templateCodes);
        }

        boolean supports(int frequency) {
            return frequencies.contains(frequency);
        }

        boolean acceptsTemplate(String templateCode) {
            return templateCodes.contains(templateCode);
        }

        static TrainingSplit forFrequency(int frequency) {
            return switch (frequency) {
                case 2, 4 -> UPPER_LOWER;
                case 3, 6 -> PUSH_PULL_LEGS;
                case 5 -> BODY_PART_FIVE_DAY;
                default -> throw new IllegalArgumentException("unsupported training frequency");
            };
        }
    }

    public static final class CandidateNotFoundException extends RuntimeException {}
}
