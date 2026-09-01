package com.aifitness.assistant.workout.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSessionStartService;
import com.aifitness.assistant.workout.application.WorkoutCompletionService;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.domain.WorkoutWarmupPrescription;
import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workout-sessions")
@Profile({"local", "test", "staging-experience"})
public final class WorkoutSessionController {
    private final WorkoutSessionService sessions;
    private final WorkoutSessionStartService starts;
    private final WorkoutCompletionService completion;
    private final ExerciseReplacementService replacements;
    private final Clock clock;

    public WorkoutSessionController(
            WorkoutSessionService sessions, WorkoutSessionStartService starts, WorkoutCompletionService completion,
            ExerciseReplacementService replacements, Clock clock) {
        this.sessions = sessions;
        this.starts = starts;
        this.completion = completion;
        this.replacements = replacements;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<?> start(
            AuthenticatedUserId user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StartRequest request) {
        if (request == null || !idempotencyKey.equals(request.clientSessionKey())) {
            throw new IllegalArgumentException("Idempotency-Key must match clientSessionKey");
        }
        ActiveWorkoutReplacementRequest replacementRequest = request.activeWorkoutReplacement();
        if (replacementRequest != null
                && (replacementRequest.sessionId() == null || replacementRequest.expectedVersion() == null
                || replacementRequest.expectedVersion() < 0)) {
            throw new IllegalArgumentException(
                    "active workout replacement requires sessionId and a non-negative expectedVersion");
        }
        String trainingDayCode = request.resolvedTrainingDayCode();
        WorkoutSessionStartService.StartResult result = starts.start(
                user,
                new WorkoutSessionService.StartCommand(
                        request.clientSessionKey(), request.planId(), request.planVersionNo(), trainingDayCode),
                Optional.ofNullable(request.recoveryConfirmationToken()),
                Optional.ofNullable(replacementRequest).map(replacement ->
                        new WorkoutSessionStartService.ActiveWorkoutReplacement(
                                replacement.sessionId(), replacement.expectedVersion())));
        if (result instanceof WorkoutSessionStartService.Started started) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response(SessionData.from(started.session())));
        }
        if (result instanceof WorkoutSessionStartService.ActiveWorkoutExists active) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                    new ApiError(
                            ErrorCode.ACTIVE_WORKOUT_EXISTS,
                            "存在尚未结束的训练，请先继续或结束该训练",
                            List.of(),
                            Map.of(
                                    "activeSession", SessionData.from(active.session()),
                                    "sets", active.sets().stream()
                                            .map(set -> WorkoutSetController.SetData.from(
                                                    set, active.session().version()))
                                            .toList()),
                            false),
                    new ErrorMeta(requestId())));
        }
        if (result instanceof WorkoutSessionStartService.TerminalReplay terminal) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                    new ApiError(
                            ErrorCode.WORKOUT_START_ALREADY_TERMINAL,
                            "上次使用该启动键的训练已经结束，请重新开始",
                            List.of(),
                            Map.of("terminalSession", SessionData.from(terminal.session())),
                            false),
                    new ErrorMeta(requestId())));
        }
        WorkoutSessionStartService.ConfirmationRequired warning =
                (WorkoutSessionStartService.ConfirmationRequired) result;
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                new ApiError(
                        ErrorCode.RECOVERY_CONFIRMATION_REQUIRED,
                        "主要肌群恢复时间不足，需要明确确认后继续",
                        List.of(),
                        Map.of(
                                "assessment", WorkoutRecoveryController.RecoveryCheckData.from(warning.assessment()),
                                "confirmationToken", warning.confirmationToken(),
                                "confirmationExpiresAt", warning.confirmationExpiresAt()),
                        false),
                new ErrorMeta(requestId())));
    }

    @GetMapping("/{id}")
    public ApiResponse<SessionData> get(AuthenticatedUserId user, @PathVariable UUID id) {
        return response(SessionData.from(sessions.get(user, id)));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<SessionData> transition(
            AuthenticatedUserId user,
            @PathVariable UUID id,
            @RequestBody StatusRequest request) {
        if (request == null || request.status() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("status and expectedVersion are required");
        }
        if (request.status() == WorkoutStatus.COMPLETING || request.status() == WorkoutStatus.COMPLETED) {
            throw new IllegalArgumentException("completion must use the authoritative completion endpoint");
        }
        return response(SessionData.from(
                sessions.transition(user, id, request.status(), request.expectedVersion())));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<CompletionData> complete(
            AuthenticatedUserId user,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CompletionRequest request) {
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128
                || request == null || request.completionType() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("valid completion request and idempotency key are required");
        }
        WorkoutCompletionService.Result result = completion.complete(
                user, id, request.expectedVersion(), request.completionType());
        return response(new CompletionData(
                SessionData.from(result.session()), result.completedWorkSets(), result.complete(),
                result.automaticProgressionEligible()));
    }

    @PutMapping("/{id}/exercises/{exerciseId}")
    public ApiResponse<SessionData> updateExercise(
            AuthenticatedUserId user, @PathVariable UUID id, @PathVariable UUID exerciseId,
            @RequestBody ExerciseUpdateRequest request) {
        if (request == null || request.action() != ExerciseAction.REPLACE
                || request.replacementExerciseId() == null || request.replacementExerciseId().isBlank()
                || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("a legal replacement and expectedVersion are required");
        }
        return response(SessionData.from(replacements.replace(
                user, id, exerciseId, request.replacementExerciseId(), request.expectedVersion())));
    }

    private <T> ApiResponse<T> response(T data) {
        return new ApiResponse<>(data, new ResponseMeta(requestId(), clock.instant()));
    }

    private static String requestId() {
        String requestId = MDC.get("requestId");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    public record StartRequest(
            String clientSessionKey,
            UUID planId,
            int planVersionNo,
            String planDayId,
            String trainingDayCode,
            String recoveryConfirmationToken,
            ActiveWorkoutReplacementRequest activeWorkoutReplacement) {
        public StartRequest(
                String clientSessionKey,
                UUID planId,
                int planVersionNo,
                String planDayId,
                String trainingDayCode,
                String recoveryConfirmationToken) {
            this(clientSessionKey, planId, planVersionNo, planDayId, trainingDayCode,
                    recoveryConfirmationToken, null);
        }

        String resolvedTrainingDayCode() {
            if (planDayId == null || planDayId.isBlank()) {
                throw new IllegalArgumentException("planDayId is required for compatibility");
            }
            if (trainingDayCode != null && !trainingDayCode.isBlank() && !planDayId.equals(trainingDayCode)) {
                throw new IllegalArgumentException("planDayId and trainingDayCode must identify the same day");
            }
            return trainingDayCode == null || trainingDayCode.isBlank() ? planDayId : trainingDayCode;
        }
    }

    public record ActiveWorkoutReplacementRequest(UUID sessionId, Long expectedVersion) {}

    public record StatusRequest(WorkoutStatus status, long expectedVersion) {}

    public record CompletionRequest(
            long expectedVersion, WorkoutCompletionService.CompletionType completionType) {}

    public record CompletionData(
            SessionData session, int completedWorkSets, boolean complete,
            boolean automaticProgressionEligible) {}

    public enum ExerciseAction { REPLACE }
    public record ExerciseUpdateRequest(
            ExerciseAction action, String replacementExerciseId, long expectedVersion) {}

    public record SessionData(
            UUID id,
            UUID planId,
            UUID planVersionId,
            int planVersionNo,
            String clientSessionKey,
            String planDayId,
            String trainingDayCode,
            WorkoutStatus status,
            Instant startedAt,
            Optional<Instant> completedAt,
            long version,
            List<ExerciseData> exercises,
            Optional<WarmupPrescriptionData> warmupPrescription) {
        static SessionData from(WorkoutSession session) {
            return new SessionData(
                    session.id(), session.planId(), session.planVersionId(), session.planVersionNumber(),
                    session.clientSessionKey(),
                    session.trainingDayCode(), session.trainingDayCode(), session.status(),
                    session.startedAt(), session.completedAt(),
                    session.version(), session.exercises().stream().map(ExerciseData::from).toList(),
                    session.warmupPrescription().map(WarmupPrescriptionData::from));
        }
    }

    public record WarmupPrescriptionData(
            String schemaVersion,
            String ruleVersion,
            GeneralWarmupData generalWarmup,
            Optional<RampWarmupData> rampWarmup,
            List<WarmupInstructionData> instructions,
            boolean countsTowardTrainingVolume,
            boolean countsTowardProgression) {
        static WarmupPrescriptionData from(WorkoutWarmupPrescription value) {
            return new WarmupPrescriptionData(
                    value.schemaVersion(),
                    value.ruleVersion(),
                    new GeneralWarmupData(
                            value.generalWarmup().occurrences(), value.generalWarmup().durationSeconds()),
                    value.rampWarmup().map(RampWarmupData::from),
                    value.instructions().stream().map(WarmupInstructionData::from).toList(),
                    value.countsTowardTrainingVolume(),
                    value.countsTowardProgression());
        }
    }

    public record GeneralWarmupData(int occurrences, int durationSeconds) {}

    public record WarmupInstructionData(
            String instruction, Optional<String> prescription, boolean optional) {
        static WarmupInstructionData from(WorkoutWarmupPrescription.Instruction value) {
            return new WarmupInstructionData(value.instruction(), value.prescription(), value.optional());
        }
    }

    public record RampWarmupData(
            UUID exerciseId,
            int exerciseOrder,
            WorkoutWarmupPrescription.RampStatus status,
            Optional<String> equipmentType,
            List<RampSetData> sets,
            Optional<String> calibrationCode,
            Optional<String> calibrationMessage) {
        static RampWarmupData from(WorkoutWarmupPrescription.RampWarmup value) {
            return new RampWarmupData(
                    value.exerciseId(),
                    value.exerciseOrder(),
                    value.status(),
                    value.equipmentType(),
                    value.sets().stream().map(RampSetData::from).toList(),
                    value.calibrationCode(),
                    value.calibrationMessage());
        }
    }

    public record RampSetData(BigDecimal weightKg, int reps) {
        static RampSetData from(WorkoutWarmupPrescription.RampSet value) {
            return new RampSetData(value.weightKg(), value.reps());
        }
    }

    public record ExerciseData(
            UUID id,
            int order,
            String exerciseCode,
            String exerciseName,
            String contentVersion,
            Set<String> equipment,
            PrescriptionData prescription,
            WorkoutExerciseSnapshot.Status status) {
        static ExerciseData from(WorkoutExerciseSnapshot exercise) {
            return new ExerciseData(
                    exercise.id(), exercise.order(), exercise.exerciseCode(), exercise.exerciseName(),
                    exercise.contentVersion(), exercise.equipment(),
                    PrescriptionData.from(exercise.prescription()), exercise.status());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record PrescriptionData(
            int workSets, int repMin, int repMax, int restSeconds, String weightStatus,
            Optional<BigDecimal> targetWeightKg, String unit,
            Optional<Integer> targetRirMin, Optional<Integer> targetRirMax,
            Optional<Integer> eccentricSeconds, boolean perSide,
            Optional<String> executionGroup, Optional<Integer> executionOrder,
            Optional<OptionalSetRuleData> optionalSetRule) {
        static PrescriptionData from(WorkoutExerciseSnapshot.Prescription value) {
            return new PrescriptionData(
                    value.workSets(), value.repMin(), value.repMax(), value.restSeconds(),
                    value.weightStatus(), value.targetWeightKg(), value.unit(),
                    value.targetRirMin(), value.targetRirMax(), value.eccentricSeconds(), value.perSide(),
                    value.executionGroup(), value.executionOrder(),
                    value.optionalSetRule().map(OptionalSetRuleData::from));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record OptionalSetRuleData(
            String conditionCode, String exclusiveChoiceGroup, int additionalSets,
            Optional<String> description) {
        static OptionalSetRuleData from(WorkoutExerciseSnapshot.Prescription.OptionalSetRule value) {
            return new OptionalSetRuleData(
                    value.conditionCode(), value.exclusiveChoiceGroup(), value.additionalSets(),
                    value.description());
        }
    }
}
