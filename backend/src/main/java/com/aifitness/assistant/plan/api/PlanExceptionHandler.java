package com.aifitness.assistant.plan.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.application.CandidateCommitService;
import com.aifitness.assistant.plan.application.PlanVersionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {PlanController.class})
@Profile({"local", "test", "staging-experience"})
public final class PlanExceptionHandler {

    @ExceptionHandler(PlanVersionService.VersionConflictException.class)
    ResponseEntity<ApiErrorResponse> versionConflict(PlanVersionService.VersionConflictException exception) {
        return error(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, "资源版本冲突",
                Map.of("currentVersion", exception.getCurrentVersion()));
    }

    @ExceptionHandler(PlanVersionService.ActivePlanAlreadyExistsException.class)
    ResponseEntity<ApiErrorResponse> activePlanExists() {
        return error(HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, "活动计划已存在", Map.of());
    }

    @ExceptionHandler(CandidateCommitService.IdempotencyKeyReusedException.class)
    ResponseEntity<ApiErrorResponse> idempotencyKeyReused() {
        return error(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "幂等键已用于不同请求", Map.of());
    }

    @ExceptionHandler({
            PlanVersionService.PlanNotFoundException.class,
            PlanCandidateService.CandidateNotFoundException.class
    })
    ResponseEntity<ApiErrorResponse> notFound() {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "资源不存在或已过期", Map.of());
    }

    @ExceptionHandler(PlanVersionService.PlanValidationException.class)
    ResponseEntity<ApiErrorResponse> validation(PlanVersionService.PlanValidationException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.PLAN_VALIDATION_FAILED, "计划校验失败",
                Map.of("issues", exception.getIssues()));
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, ErrorCode code, String message, Map<String, Object> details) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ApiError error = new ApiError(code, message, List.of(), details, false);
        return ResponseEntity.status(status).body(new ApiErrorResponse(error, new ErrorMeta(requestId)));
    }
}
