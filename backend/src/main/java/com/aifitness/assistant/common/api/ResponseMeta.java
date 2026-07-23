package com.aifitness.assistant.common.api;

import java.time.Instant;
import java.util.Objects;

public record ResponseMeta(String requestId, Instant serverTime) {

    public ResponseMeta {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        Objects.requireNonNull(serverTime, "serverTime must not be null");
    }
}
