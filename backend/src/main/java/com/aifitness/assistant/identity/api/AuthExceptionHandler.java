package com.aifitness.assistant.identity.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Profile({"local", "test", "staging-experience"})
public final class AuthExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalidInput() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求参数不合法");
    }

    @ExceptionHandler(WechatLoginService.AuthenticationRequiredException.class)
    ResponseEntity<ApiErrorResponse> authenticationRequired() {
        return error(HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED, "登录状态已失效");
    }

    @ExceptionHandler(WechatLoginService.AccessRevokedException.class)
    ResponseEntity<ApiErrorResponse> accessRevoked() {
        return error(HttpStatus.UNAUTHORIZED, ErrorCode.ACCESS_REVOKED, "账号访问已终止");
    }

    @ExceptionHandler(WechatLoginService.AuthenticationRateLimitedException.class)
    ResponseEntity<ApiErrorResponse> authenticationRateLimited() {
        return error(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试");
    }

    @ExceptionHandler(WechatIdentityProvider.ProviderUnavailableException.class)
    ResponseEntity<ApiErrorResponse> identityProviderUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR,
                "微信登录服务暂时不可用，请稍后重试", true);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class,
            MethodValidationException.class
    })
    ResponseEntity<ApiErrorResponse> malformedRequest() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求参数不合法");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorResponse> missingBusinessHeader() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求参数不合法");
    }

    @ExceptionHandler(com.aifitness.assistant.identity.application.ResourceOwnershipGuard.ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> resourceNotFound() {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, ErrorCode code, String message) {
        return error(status, code, message, false);
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, ErrorCode code, String message, boolean retryable) {
        ApiError error = new ApiError(code, message, List.of(), Map.of(), retryable);
        return ResponseEntity.status(status).body(new ApiErrorResponse(error, new ErrorMeta(requestId())));
    }

    private String requestId() {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return requestId;
    }
}
