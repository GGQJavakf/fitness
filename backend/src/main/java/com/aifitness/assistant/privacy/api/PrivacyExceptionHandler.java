package com.aifitness.assistant.privacy.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Profile({"local", "test", "staging-experience"})
public final class PrivacyExceptionHandler {

    @ExceptionHandler(PrivacyRequestService.ReauthenticationRequiredException.class)
    ResponseEntity<ApiErrorResponse> reauthenticationRequired() {
        return error(HttpStatus.UNAUTHORIZED, ErrorCode.REAUTHENTICATION_REQUIRED, "请重新验证身份");
    }

    @ExceptionHandler(PrivacyRequestService.SecondConfirmationRequiredException.class)
    ResponseEntity<ApiErrorResponse> confirmationRequired() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请准确输入 DELETE 完成二次确认");
    }

    @ExceptionHandler(PrivacyRequestService.PrivacyRateLimitedException.class)
    ResponseEntity<ApiErrorResponse> rateLimited() {
        return error(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, ErrorCode code, String message) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ApiError error = new ApiError(code, message, List.of(), Map.of(), false);
        return ResponseEntity.status(status).body(new ApiErrorResponse(error, new ErrorMeta(requestId)));
    }
}
