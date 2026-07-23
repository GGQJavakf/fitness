package com.aifitness.assistant.identity.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.identity.application.WechatLoginService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Profile({"local", "test"})
public final class AuthExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> invalidInput() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求参数不合法");
    }

    @ExceptionHandler(WechatLoginService.AuthenticationRequiredException.class)
    ResponseEntity<ApiErrorResponse> authenticationRequired() {
        return error(HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED, "登录状态已失效");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ApiErrorResponse> malformedRequest() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求参数不合法");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorResponse> missingAuthenticationHeader() {
        return error(HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED, "登录状态已失效");
    }

    @ExceptionHandler(com.aifitness.assistant.identity.application.ResourceOwnershipGuard.ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> resourceNotFound() {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, ErrorCode code, String message) {
        ApiError error = new ApiError(code, message, List.of(), Map.of(), false);
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(status).body(new ApiErrorResponse(error, new ErrorMeta(requestId)));
    }
}
