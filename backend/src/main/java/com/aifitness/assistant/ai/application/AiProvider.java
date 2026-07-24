package com.aifitness.assistant.ai.application;

import java.util.Map;
import java.util.Objects;

@FunctionalInterface
public interface AiProvider {

    Output generate(Request request);

    static AiProvider disabled() {
        return request -> {
            throw new ProviderUnavailableException("no AI provider is configured");
        };
    }

    enum Purpose {
        PLAN_EXPLANATION,
        WORKOUT_SUMMARY,
        ALTERNATIVE_RANKING
    }

    record Request(Purpose purpose, Map<String, Object> input) {
        public Request {
            Objects.requireNonNull(purpose, "purpose must not be null");
            input = Map.copyOf(Objects.requireNonNull(input, "input must not be null"));
        }
    }

    record Output(String provider, String model, String content) {
        public Output {
            provider = requireText(provider, "provider");
            model = requireText(model, "model");
            content = requireText(content, "content");
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }

    final class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message) {
            super(message);
        }
    }
}
