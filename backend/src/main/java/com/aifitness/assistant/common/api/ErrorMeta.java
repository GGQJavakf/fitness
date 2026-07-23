package com.aifitness.assistant.common.api;

public record ErrorMeta(String requestId) {

    public ErrorMeta {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
    }
}
