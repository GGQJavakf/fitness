package com.aifitness.assistant.ai.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AiController.class)
@Profile({"local", "test", "staging-experience"})
public final class AiExceptionHandler {

    @ExceptionHandler({
            PlanCandidateService.CandidateNotFoundException.class,
            WorkoutSessionService.SessionNotFoundException.class
    })
    ResponseEntity<ApiErrorResponse> notFound() {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "资源不存在或已过期");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求参数无效");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, ErrorCode code, String message) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                new ApiError(code, message, List.of(), Map.of(), false), new ErrorMeta(requestId)));
    }
}
