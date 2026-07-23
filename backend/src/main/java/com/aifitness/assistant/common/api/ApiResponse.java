package com.aifitness.assistant.common.api;

import java.util.Objects;

public record ApiResponse<T>(T data, ResponseMeta meta) {

    public ApiResponse {
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(meta, "meta must not be null");
    }
}
