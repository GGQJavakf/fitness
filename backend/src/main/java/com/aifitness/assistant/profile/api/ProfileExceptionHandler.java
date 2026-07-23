package com.aifitness.assistant.profile.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.profile.application.ProfileService;
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
public final class ProfileExceptionHandler {

    @ExceptionHandler(ProfileService.VersionConflictException.class)
    ResponseEntity<ApiErrorResponse> versionConflict(ProfileService.VersionConflictException exception) {
        return error(
                HttpStatus.CONFLICT,
                ErrorCode.VERSION_CONFLICT,
                "资源版本冲突",
                Map.of("currentVersion", exception.currentVersion()));
    }

    @ExceptionHandler(ProfileService.ProfileNotFoundException.class)
    ResponseEntity<ApiErrorResponse> profileNotFound() {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "资源不存在", Map.of());
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
