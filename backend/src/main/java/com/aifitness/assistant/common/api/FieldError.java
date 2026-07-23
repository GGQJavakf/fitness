package com.aifitness.assistant.common.api;

import java.util.Map;
import java.util.Objects;

public record FieldError(String path, String code, Map<String, Object> parameters) {

    public FieldError {
        path = requireText(path, "path");
        code = requireText(code, "code");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters must not be null"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
