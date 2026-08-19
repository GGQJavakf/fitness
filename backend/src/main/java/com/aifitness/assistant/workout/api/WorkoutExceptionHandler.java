package com.aifitness.assistant.workout.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutRecoveryCheckService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.application.WorkoutCompletionService;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import com.aifitness.assistant.workout.application.WorkoutHistoryQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        WorkoutSessionController.class, WorkoutSetController.class, WorkoutSyncController.class,
        WorkoutHistoryController.class, ExerciseReplacementController.class
})
@Profile({"local", "test", "staging-experience"})
public final class WorkoutExceptionHandler {

    @ExceptionHandler({
            WorkoutSessionService.SessionNotFoundException.class,
            PlanWorkoutSnapshotQuery.PlanSnapshotNotFoundException.class
    })
    ResponseEntity<ApiErrorResponse> notFound() {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "资源不存在", Map.of());
    }

    @ExceptionHandler(ExerciseReplacementService.ExerciseNotFoundException.class)
    ResponseEntity<ApiErrorResponse> exerciseNotFound() {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "资源不存在", Map.of());
    }

    @ExceptionHandler(ExerciseReplacementService.IllegalReplacementException.class)
    ResponseEntity<ApiErrorResponse> illegalReplacement() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "替代动作不符合当前训练条件", Map.of());
    }

    @ExceptionHandler(ExerciseReplacementService.InsufficientReplacementsException.class)
    ResponseEntity<ApiErrorResponse> insufficientReplacements(
            ExerciseReplacementService.InsufficientReplacementsException exception) {
        return error(HttpStatus.CONFLICT, ErrorCode.INSUFFICIENT_REPLACEMENTS,
                "当前条件下没有兼容的替代动作",
                Map.of("availableCandidateCount", exception.availableCandidateCount(), "minimumRequired", 1));
    }

    @ExceptionHandler(WorkoutCompletionService.IncompleteWorkoutException.class)
    ResponseEntity<ApiErrorResponse> incompleteWorkout() {
        return error(HttpStatus.CONFLICT, ErrorCode.VALIDATION_FAILED,
                "训练尚未完整完成，可选择提前结束", Map.of("completionType", "EARLY_END"));
    }

    @ExceptionHandler(WorkoutHistoryQueryService.WorkoutNotTerminalException.class)
    ResponseEntity<ApiErrorResponse> workoutNotTerminal() {
        return error(HttpStatus.CONFLICT, ErrorCode.WORKOUT_NOT_TERMINAL,
                "训练尚未结束，不能生成历史汇总", Map.of());
    }

    @ExceptionHandler(WorkoutSessionService.VersionConflictException.class)
    ResponseEntity<ApiErrorResponse> versionConflict(WorkoutSessionService.VersionConflictException exception) {
        return error(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, "资源版本冲突",
                Map.of("currentVersion", exception.currentVersion()));
    }

    @ExceptionHandler(WorkoutSessionService.IdempotencyConflictException.class)
    ResponseEntity<ApiErrorResponse> idempotencyConflict() {
        return error(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_REUSED, "幂等键已用于不同请求", Map.of());
    }

    @ExceptionHandler(WorkoutRecoveryCheckService.RecoveryFactsUnavailableException.class)
    ResponseEntity<ApiErrorResponse> recoveryFactsUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR,
                "恢复检查暂时不可用", Map.of());
    }

    @ExceptionHandler(WorkoutSetService.AnomalyConfirmationRequiredException.class)
    ResponseEntity<ApiErrorResponse> anomalyConfirmation(
            WorkoutSetService.AnomalyConfirmationRequiredException exception) {
        return error(HttpStatus.CONFLICT, ErrorCode.ANOMALY_CONFIRMATION_REQUIRED,
                "异常训练数据需要显式确认", Map.of("reasons", exception.reasons()));
    }

    @ExceptionHandler({IllegalStateException.class, WorkoutSetService.SessionNotAcceptingSetsException.class})
    ResponseEntity<ApiErrorResponse> invalidState() {
        return error(HttpStatus.CONFLICT, ErrorCode.SESSION_ALREADY_TERMINAL, "会话状态不允许此操作", Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求参数无效", Map.of());
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, ErrorCode code, String message, Map<String, Object> details) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                new ApiError(code, message, List.of(), details, false), new ErrorMeta(requestId)));
    }
}
