package com.aifitness.assistant.workout.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutCompletionService;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
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
    private final WorkoutCompletionService completion;
    private final ExerciseReplacementService replacements;
    private final Clock clock;

    public WorkoutSessionController(
            WorkoutSessionService sessions, WorkoutCompletionService completion,
            ExerciseReplacementService replacements, Clock clock) {
        this.sessions = sessions;
        this.completion = completion;
        this.replacements = replacements;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SessionData>> start(
            AuthenticatedUserId user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StartRequest request) {
        if (request == null || !idempotencyKey.equals(request.clientSessionKey())) {
            throw new IllegalArgumentException("Idempotency-Key must match clientSessionKey");
        }
        WorkoutSession session = sessions.start(user, new WorkoutSessionService.StartCommand(
                request.clientSessionKey(), request.planId(), request.planVersionNo(), request.planDayId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response(SessionData.from(session)));
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
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record StartRequest(
            String clientSessionKey, UUID planId, int planVersionNo, String planDayId) {}

    public record StatusRequest(WorkoutStatus status, long expectedVersion) {}

    public record CompletionRequest(
            long expectedVersion, WorkoutCompletionService.CompletionType completionType) {}

    public record CompletionData(
            SessionData session, int completedWorkSets, boolean complete,
            boolean automaticProgressionEligible) {}

    public enum ExerciseAction { SKIP, REPLACE, COMPLETE }
    public record ExerciseUpdateRequest(
            ExerciseAction action, String replacementExerciseId, long expectedVersion) {}

    public record SessionData(
            UUID id,
            UUID planId,
            UUID planVersionId,
            int planVersionNo,
            String planDayId,
            WorkoutStatus status,
            Instant startedAt,
            Optional<Instant> completedAt,
            long version,
            List<ExerciseData> exercises) {
        static SessionData from(WorkoutSession session) {
            return new SessionData(
                    session.id(), session.planId(), session.planVersionId(), session.planVersionNumber(),
                    session.trainingDayCode(), session.status(), session.startedAt(), session.completedAt(),
                    session.version(), session.exercises().stream().map(ExerciseData::from).toList());
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

    public record PrescriptionData(
            int workSets, int repMin, int repMax, int restSeconds, String weightStatus,
            Optional<BigDecimal> targetWeightKg, String unit) {
        static PrescriptionData from(WorkoutExerciseSnapshot.Prescription value) {
            return new PrescriptionData(
                    value.workSets(), value.repMin(), value.repMax(), value.restSeconds(),
                    value.weightStatus(), value.targetWeightKg(), value.unit());
        }
    }
}
