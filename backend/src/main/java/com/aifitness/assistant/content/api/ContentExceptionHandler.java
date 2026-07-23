package com.aifitness.assistant.content.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
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
@Profile({"local", "test"})
public final class ContentExceptionHandler {

    @ExceptionHandler(ContentNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound() {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ApiError error = new ApiError(
                ErrorCode.RESOURCE_NOT_FOUND, "资源不存在", List.of(), Map.of(), false);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(error, new ErrorMeta(requestId)));
    }

    static final class ContentNotFoundException extends RuntimeException {}
}
