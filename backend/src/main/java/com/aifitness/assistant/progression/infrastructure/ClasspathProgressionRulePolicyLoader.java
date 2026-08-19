package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.progression.domain.ProgressionRulePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;

public final class ClasspathProgressionRulePolicyLoader {
    private static final String PATH = "rule-config/rule-config-v1.json";

    private ClasspathProgressionRulePolicyLoader() {}

    public static ProgressionRulePolicy load(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        try (InputStream input = new ClassPathResource(PATH).getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            JsonNode progression = root.path("parameters").path("progression");
            return new ProgressionRulePolicy(
                    requiredText(root.at("/metadata/version"), "metadata.version"),
                    requiredInt(progression, "longTrainingGapDays"),
                    requiredInt(progression, "multipleFailedSetsThreshold"));
        } catch (IOException exception) {
            throw new IllegalStateException("validated progression rule policy cannot be loaded", exception);
        }
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalStateException("validated progression rule field is missing: " + field);
        }
        return value.asInt();
    }

    private static String requiredText(JsonNode value, String field) {
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("validated progression rule field is missing: " + field);
        }
        return value.asText();
    }
}
