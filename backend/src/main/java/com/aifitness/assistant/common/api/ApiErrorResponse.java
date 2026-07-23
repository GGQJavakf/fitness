package com.aifitness.assistant.common.api;

import java.util.Objects;

public record ApiErrorResponse(ApiError error, ErrorMeta meta) {

    public ApiErrorResponse {
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(meta, "meta must not be null");
    }
}
