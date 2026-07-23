package com.aifitness.assistant.common.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ApiError(
        ErrorCode code,
        String message,
        List<FieldError> fieldErrors,
        Map<String, Object> details,
        boolean retryable) {

    public ApiError {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        fieldErrors = List.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors must not be null"));
        details = Map.copyOf(Objects.requireNonNull(details, "details must not be null"));
    }
}
