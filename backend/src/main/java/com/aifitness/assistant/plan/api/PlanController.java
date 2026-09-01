package com.aifitness.assistant.plan.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.CandidateCommitService;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.application.PlanExerciseOptionService;
import com.aifitness.assistant.plan.domain.FieldLock;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Profile({"local", "test", "staging-experience"})
public final class PlanController {
    private final PlanVersionService versions;
    private final CandidateCommitService candidateCommits;
    private final PlanExerciseOptionService exerciseOptions;
    private final Clock clock;

    public PlanController(
            PlanVersionService versions,
            CandidateCommitService candidateCommits,
            PlanExerciseOptionService exerciseOptions,
            Clock clock) {
        this.versions = versions;
        this.candidateCommits = candidateCommits;
        this.exerciseOptions = exerciseOptions;
        this.clock = clock;
    }

    @PostMapping("/candidate-commits")
    public ResponseEntity<ApiResponse<VersionResultData>> commitCandidate(
            AuthenticatedUserId user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CandidateCommitRequest request) {
        if (request == null || request.plan() == null) {
            throw new IllegalArgumentException("candidate commit request and plan are required");
        }
        PlanVersionService.VersionResult result = candidateCommits.commit(
                user,
                validCandidateId(request.candidateId()),
                request.expectedActiveVersionNumber(),
                request.plan().toDomain(),
                safeLocks(request.locks()),
                request.warningConfirmationToken(),
                idempotencyKey);
        HttpStatus status = result.status() == PlanVersionService.VersionStatus.CREATED
                ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response(VersionResultData.from(result)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ActivePlanData>> create(
            AuthenticatedUserId user, @RequestBody CreatePlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        TrainingPlan plan = versions.createInitial(user, validCandidateId(request.candidateId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response(ActivePlanData.from(plan)));
    }

    @GetMapping("/active")
    public ApiResponse<ActivePlanData> active(AuthenticatedUserId user) {
        return response(ActivePlanData.from(versions.getActive(user)));
    }

    @GetMapping("/{planId}/versions/{versionNo}")
    public ApiResponse<VersionData> version(
            AuthenticatedUserId user,
            @PathVariable UUID planId,
            @PathVariable int versionNo) {
        return response(VersionData.from(versions.getVersion(user, planId, versionNo)));
    }

    @GetMapping("/{planId}/exercise-options")
    public ApiResponse<ExerciseOptionListData> exerciseOptions(
            AuthenticatedUserId user,
            @PathVariable UUID planId,
            @RequestParam String dayCode) {
        return response(new ExerciseOptionListData(exerciseOptions.list(user, planId, dayCode)));
    }

    @GetMapping("/{planId}/exercise-replacements")
    public ApiResponse<ExerciseReplacementOptionListData> exerciseReplacements(
            AuthenticatedUserId user,
            @PathVariable UUID planId,
            @RequestParam String dayCode,
            @RequestParam String sourceExerciseCode) {
        return response(new ExerciseReplacementOptionListData(exerciseOptions
                .listReplacements(user, planId, dayCode, sourceExerciseCode).stream()
                .map(ExerciseReplacementOptionData::from)
                .toList()));
    }

    @GetMapping("/{planId}/day-options")
    public ApiResponse<DayOptionListData> dayOptions(
            AuthenticatedUserId user,
            @PathVariable UUID planId) {
        return response(new DayOptionListData(exerciseOptions.listDays(user, planId)));
    }

    @PostMapping("/{planId}/versions")
    public ResponseEntity<ApiResponse<VersionResultData>> createVersion(
            AuthenticatedUserId user,
            @PathVariable UUID planId,
            @RequestBody VersionRequest request) {
        PlanVersionService.VersionResult result = versions.createVersion(
                user, planId, request.baseVersionNumber(), request.plan().toDomain(),
                safeLocks(request.locks()), request.warningConfirmationToken());
        HttpStatus status = result.status() == PlanVersionService.VersionStatus.CREATED
                ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response(VersionResultData.from(result)));
    }

    @PostMapping("/{planId}/rebalance")
    public ApiResponse<VersionResultData> rebalance(
            AuthenticatedUserId user,
            @PathVariable UUID planId,
            @RequestBody VersionRequest request) {
        return response(VersionResultData.from(versions.previewRebalance(
                user, planId, request.baseVersionNumber(), request.plan().toDomain(),
                safeLocks(request.locks()))));
    }

    private static Map<String, FieldLock.Status> safeLocks(Map<String, FieldLock.Status> locks) {
        if (locks == null) {
            return Map.of();
        }
        if (locks.size() > 100 || locks.entrySet().stream().anyMatch(
                entry -> entry.getKey() == null || entry.getValue() == null
                        || entry.getValue() == FieldLock.Status.RULE_LOCKED)) {
            throw new IllegalArgumentException("locks are invalid");
        }
        return Map.copyOf(locks);
    }

    private static String validCandidateId(String value) {
        if (value == null || value.length() != 36) {
            throw new IllegalArgumentException("candidateId must be a canonical UUID");
        }
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("candidateId must be a canonical UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("candidateId must be a canonical UUID");
        }
        return value;
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record CreatePlanRequest(String candidateId) {}

    public record CandidateCommitRequest(
            String candidateId,
            Integer expectedActiveVersionNumber,
            PlanData plan,
            Map<String, FieldLock.Status> locks,
            String warningConfirmationToken) {
        public CandidateCommitRequest {
            if (expectedActiveVersionNumber == null || expectedActiveVersionNumber < 0) {
                throw new IllegalArgumentException(
                        "expectedActiveVersionNumber is required and must not be negative");
            }
        }
    }

    public record ExerciseOptionListData(List<PlanExerciseOptionService.Option> items) {
        public ExerciseOptionListData {
            items = List.copyOf(items);
        }
    }

    public record ExerciseReplacementOptionListData(List<ExerciseReplacementOptionData> items) {
        public ExerciseReplacementOptionListData {
            items = List.copyOf(items);
        }
    }

    public record ExerciseReplacementOptionData(
            String exerciseCode,
            String name,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            PlanDraft.WeightStatus weightStatus,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal targetWeightKg,
            String movementPattern,
            List<String> primaryMuscles,
            List<String> equipment,
            PlanExerciseOptionService.MatchReason matchReason) {
        static ExerciseReplacementOptionData from(PlanExerciseOptionService.ReplacementOption option) {
            return new ExerciseReplacementOptionData(
                    option.exerciseCode(), option.name(), option.workSets(), option.repMin(), option.repMax(),
                    option.restSeconds(), option.weightStatus(), option.targetWeightKg().orElse(null),
                    option.movementPattern(), option.primaryMuscles(), option.equipment(), option.matchReason());
        }
    }

    public record DayOptionListData(List<PlanExerciseOptionService.DayOption> items) {
        public DayOptionListData {
            items = List.copyOf(items);
        }
    }

    public record VersionRequest(
            int baseVersionNumber,
            PlanData plan,
            Map<String, FieldLock.Status> locks,
            String warningConfirmationToken) {
        public VersionRequest {
            if (baseVersionNumber < 1 || plan == null) {
                throw new IllegalArgumentException("baseVersionNumber and plan are required");
            }
        }
    }

    public record ActivePlanData(UUID planId, VersionData activeVersion) {
        static ActivePlanData from(TrainingPlan plan) {
            return new ActivePlanData(plan.id(), VersionData.from(plan.activeVersion()));
        }
    }

    public record VersionData(
            UUID id,
            UUID planId,
            int versionNumber,
            TrainingPlanVersion.SourceType sourceType,
            PlanData plan,
            RuleReferenceData ruleReference,
            Set<String> confirmedWarningCodes,
            Instant createdAt) {
        static VersionData from(TrainingPlanVersion version) {
            return new VersionData(
                    version.id(), version.planId(), version.versionNumber(), version.sourceType(),
                    PlanData.from(version.plan()), RuleReferenceData.from(version.ruleReference()),
                    version.confirmedWarningCodes(), version.createdAt());
        }
    }

    public record VersionResultData(
            PlanVersionService.VersionStatus status,
            PlanData plan,
            List<PlanVersionService.ValidationIssue> validationIssues,
            Optional<String> warningConfirmationToken,
            Optional<VersionData> version) {
        static VersionResultData from(PlanVersionService.VersionResult result) {
            return new VersionResultData(
                    result.status(), PlanData.from(result.plan()), result.validationIssues(),
                    result.warningConfirmationToken(), result.version().map(VersionData::from));
        }
    }

    public record PlanData(
            String templateCode,
            PlanDraft.TrainingSplit trainingSplit,
            String name,
            List<DayData> days,
            Map<String, FieldLock.Status> locks,
            @JsonInclude(JsonInclude.Include.NON_NULL) String presetCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String presetVersion,
            List<String> executionRules,
            List<String> progressionRules,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    PlanDraft.MovementImpactConstraint movementImpactConstraint) {
        public PlanData(
                String templateCode,
                PlanDraft.TrainingSplit trainingSplit,
                String name,
                List<DayData> days,
                Map<String, FieldLock.Status> locks) {
            this(templateCode, trainingSplit, name, days, locks, null, null, List.of(), List.of(), null);
        }

        PlanDraft toDomain() {
            if (days == null || days.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("plan days are required");
            }
            return new PlanDraft(
                    templateCode, trainingSplit, name, days.stream().map(DayData::toDomain).toList(),
                    Map.of(), presetCode, presetVersion,
                    executionRules == null ? List.of() : executionRules,
                    progressionRules == null ? List.of() : progressionRules,
                    movementImpactConstraint);
        }

        static PlanData from(PlanDraft plan) {
            return new PlanData(
                    plan.templateCode(), plan.trainingSplit(), plan.name(),
                    plan.days().stream().map(DayData::from).toList(), plan.locks(),
                    plan.presetCode(), plan.presetVersion(),
                    plan.executionRules(), plan.progressionRules(), plan.movementImpactConstraint());
        }
    }

    public record DayData(
            String code,
            String name,
            List<ExerciseData> exercises,
            @JsonInclude(JsonInclude.Include.NON_NULL) String weekday,
            @JsonInclude(JsonInclude.Include.NON_NULL) String focus,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer estimatedMinutesMin,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer estimatedMinutesMax,
            List<WarmupStepData> warmup,
            List<String> notes) {
        public DayData(String code, String name, List<ExerciseData> exercises) {
            this(code, name, exercises, null, null, null, null, List.of(), List.of());
        }

        PlanDraft.Day toDomain() {
            if (exercises == null || exercises.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("day exercises are required");
            }
            return new PlanDraft.Day(
                    code, name, exercises.stream().map(ExerciseData::toDomain).toList(), weekday, focus,
                    estimatedMinutesMin == null ? 0 : estimatedMinutesMin,
                    estimatedMinutesMax == null ? 0 : estimatedMinutesMax,
                    warmup == null ? List.of() : warmup.stream().map(WarmupStepData::toDomain).toList(),
                    notes == null ? List.of() : notes);
        }

        static DayData from(PlanDraft.Day day) {
            return new DayData(
                    day.code(), day.name(), day.exercises().stream().map(ExerciseData::from).toList(),
                    day.weekday(), day.focus(), nullablePositive(day.estimatedMinutesMin()),
                    nullablePositive(day.estimatedMinutesMax()),
                    day.warmup().stream().map(WarmupStepData::from).toList(), day.notes());
        }
    }

    public record WarmupStepData(String instruction, String prescription, boolean optional) {
        PlanDraft.WarmupStep toDomain() {
            return new PlanDraft.WarmupStep(instruction, prescription, optional);
        }

        static WarmupStepData from(PlanDraft.WarmupStep step) {
            return new WarmupStepData(step.instruction(), step.prescription(), step.optional());
        }
    }

    public record ExerciseData(
            String exerciseCode,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            PlanDraft.WeightStatus weightStatus,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal targetWeightKg,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer targetRirMin,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer targetRirMax,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer eccentricSeconds,
            boolean perSide,
            @JsonInclude(JsonInclude.Include.NON_NULL) String executionGroup,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer executionOrder,
            @JsonInclude(JsonInclude.Include.NON_NULL) OptionalSetRuleData optionalSetRule,
            List<String> notes) {
        public ExerciseData(
                String exerciseCode,
                int workSets,
                int repMin,
                int repMax,
                int restSeconds,
                PlanDraft.WeightStatus weightStatus,
                BigDecimal targetWeightKg) {
            this(exerciseCode, workSets, repMin, repMax, restSeconds, weightStatus, targetWeightKg,
                    null, null, null, false, null, null, null, List.of());
        }

        PlanDraft.Exercise toDomain() {
            return new PlanDraft.Exercise(
                    exerciseCode, workSets, repMin, repMax, restSeconds, weightStatus,
                    Optional.ofNullable(targetWeightKg), targetRirMin, targetRirMax, eccentricSeconds,
                    perSide, executionGroup, executionOrder == null ? 0 : executionOrder,
                    optionalSetRule == null ? null : optionalSetRule.toDomain(),
                    notes == null ? List.of() : notes);
        }

        static ExerciseData from(PlanDraft.Exercise exercise) {
            return new ExerciseData(
                    exercise.exerciseCode(), exercise.workSets(), exercise.repMin(), exercise.repMax(),
                    exercise.restSeconds(), exercise.weightStatus(), exercise.targetWeightKg().orElse(null),
                    exercise.targetRirMin(), exercise.targetRirMax(), exercise.eccentricSeconds(),
                    exercise.perSide(), exercise.executionGroup(), nullablePositive(exercise.executionOrder()),
                    OptionalSetRuleData.from(exercise.optionalSetRule()), exercise.notes());
        }
    }

    public record OptionalSetRuleData(
            String conditionCode, String exclusiveChoiceGroup, int additionalSets) {
        PlanDraft.OptionalSetRule toDomain() {
            return new PlanDraft.OptionalSetRule(conditionCode, exclusiveChoiceGroup, additionalSets);
        }

        static OptionalSetRuleData from(PlanDraft.OptionalSetRule rule) {
            return rule == null ? null : new OptionalSetRuleData(
                    rule.conditionCode(), rule.exclusiveChoiceGroup(), rule.additionalSets());
        }
    }

    private static Integer nullablePositive(int value) {
        return value == 0 ? null : value;
    }

    public record RuleReferenceData(String ruleVersion, String templateVersion, String contentVersion) {
        static RuleReferenceData from(RuleReference reference) {
            return new RuleReferenceData(
                    reference.ruleVersion(), reference.templateVersion(), reference.contentVersion());
        }
    }
}
