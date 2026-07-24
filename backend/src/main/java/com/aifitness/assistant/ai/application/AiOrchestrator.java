package com.aifitness.assistant.ai.application;

import java.util.Map;
import java.util.Objects;

public final class AiOrchestrator {
    private final boolean enabled;
    private final AiProvider provider;
    private final AiInputRedactor redactor;

    public AiOrchestrator(boolean enabled, AiProvider provider, AiInputRedactor redactor) {
        this.enabled = enabled;
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
    }

    public Result generate(AiProvider.Purpose purpose, Map<String, ?> input, String template) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("template must not be blank");
        }
        if (!enabled) {
            return new Result(Status.DEGRADED, template, "AI_DISABLED", null, null);
        }
        try {
            AiProvider.Output output = provider.generate(new AiProvider.Request(purpose, redactor.redact(input)));
            return new Result(
                    Status.PENDING_VALIDATION,
                    output.content(),
                    "NOT_VALIDATED",
                    output.provider(),
                    output.model());
        } catch (RuntimeException exception) {
            return new Result(Status.DEGRADED, template, "PROVIDER_UNAVAILABLE", null, null);
        }
    }

    public enum Status {
        PENDING_VALIDATION,
        DEGRADED
    }

    public record Result(Status status, String content, String validationStatus, String provider, String model) {}
}
