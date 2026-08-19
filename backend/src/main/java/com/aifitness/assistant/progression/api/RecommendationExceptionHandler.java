package com.aifitness.assistant.progression.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.progression.application.RecommendationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RecommendationController.class)
@Profile({"local", "test", "staging-experience"})
public final class RecommendationExceptionHandler {

    @ExceptionHandler(RecommendationService.RecommendationNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound() {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "建议不存在", Map.of());
    }

    @ExceptionHandler(RecommendationService.RecommendationAlreadyDecidedException.class)
    ResponseEntity<ApiErrorResponse> alreadyDecided() {
        return error(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, "建议已处理，不能重复操作", Map.of());
    }

    @ExceptionHandler(RecommendationService.IdempotencyKeyReusedException.class)
    ResponseEntity<ApiErrorResponse> idempotencyKeyReused() {
        return error(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "幂等键已用于不同请求", Map.of());
    }

    @ExceptionHandler(RecommendationService.LockedWeightException.class)
    ResponseEntity<ApiErrorResponse> lockedWeight() {
        return error(HttpStatus.CONFLICT, ErrorCode.PLAN_VALIDATION_FAILED,
                "重量已锁定，请先明确解锁后再采纳", Map.of("field", "targetWeightKg"));
    }

    @ExceptionHandler(PlanVersionService.VersionConflictException.class)
    ResponseEntity<ApiErrorResponse> versionConflict(PlanVersionService.VersionConflictException exception) {
        return error(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, "计划版本已变化",
                Map.of("currentVersion", exception.getCurrentVersion()));
    }

    @ExceptionHandler(PlanVersionService.PlanValidationException.class)
    ResponseEntity<ApiErrorResponse> validation(PlanVersionService.PlanValidationException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.PLAN_VALIDATION_FAILED, "采纳后计划校验失败",
                Map.of("issues", exception.getIssues()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求参数无效", Map.of());
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, ErrorCode code, String message, Map<String, Object> details) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                new ApiError(code, message, List.of(), details, false), new ErrorMeta(requestId)));
    }
}
