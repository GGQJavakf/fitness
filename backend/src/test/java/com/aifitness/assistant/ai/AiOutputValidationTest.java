package com.aifitness.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.ai.application.AiOutputValidator;
import com.aifitness.assistant.ai.application.DecisionConsistencyGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiOutputValidationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiOutputValidator validator = new AiOutputValidator(json, new DecisionConsistencyGuard());

    @Test
    void acceptsOnlyCompleteTextThatRepeatsAuthoritativeNumbersAndDecision() {
        String raw = """
                {"summary":"本次完成 3 组，继续使用 20 KG。","highlights":["动作记录完整"],
                 "issues":[],"nextActions":["下次保持当前安排"],
                 "explanation":"规则结论为 KEEP，不改变重量。","safetyNotice":null}
                """;

        AiOutputValidator.ValidationResult result = validator.validate(
                raw,
                new AiOutputValidator.AuthoritativeFacts(
                        Set.of(new BigDecimal("3"), new BigDecimal("20")), Optional.of("KEEP")));

        assertThat(result.status()).isEqualTo(AiOutputValidator.ValidationStatus.VALID);
        assertThat(result.summary()).isPresent();
        assertThat(result.summary().orElseThrow().nextActions()).containsExactly("下次保持当前安排");
    }

    @Test
    void publishesTheSameClosedVersionedShapeEnforcedAtRuntime() throws Exception {
        Path schemaPath = Path.of(System.getProperty("user.dir"), "..", "contract", "schemas", "ai-summary.schema.json")
                .normalize();
        JsonNode schema = json.readTree(Files.readString(schemaPath));

        assertThat(schema.path("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required")).hasSize(6);
        assertThat(schema.path("properties").path("summary").path("maxLength").asInt()).isEqualTo(300);
        assertThat(schema.path("properties").path("explanation").path("maxLength").asInt()).isEqualTo(500);
        assertThat(schema.path("properties").path("nextActions").path("maxItems").asInt()).isEqualTo(5);
    }

    @Test
    void rejectsAllInvalidFixtureClassesAndNeverChangesAuthoritativeFacts() throws Exception {
        Path fixture = Path.of(System.getProperty("user.dir"), "..", "test-fixtures", "ai", "invalid-outputs-v1.json")
                .normalize();
        JsonNode cases = json.readTree(Files.readString(fixture)).path("cases");
        AiOutputValidator.AuthoritativeFacts facts = new AiOutputValidator.AuthoritativeFacts(
                Set.of(new BigDecimal("20")), Optional.of("KEEP"));

        for (JsonNode testCase : cases) {
            AiOutputValidator.ValidationResult result = validator.validate(testCase.path("raw").asText(), facts);
            assertThat(result.status().name())
                    .as(testCase.path("name").asText())
                    .isEqualTo(testCase.path("expected").asText());
            assertThat(result.summary()).isEmpty();
        }

        assertThat(facts.allowedNumbers()).containsExactly(new BigDecimal("20"));
        assertThat(facts.decision()).contains("KEEP");
    }

    @Test
    void rejectsOverlongItemsAndSensitiveContactData() {
        String tooLong = "x".repeat(161);
        String invalid = "{\"summary\":\"完成\",\"highlights\":[\"" + tooLong
                + "\"],\"issues\":[],\"nextActions\":[],\"explanation\":\"KEEP\",\"safetyNotice\":null}";
        assertThat(validator.validate(invalid, facts()).status())
                .isEqualTo(AiOutputValidator.ValidationStatus.INVALID_SCHEMA);

        String sensitive = """
                {"summary":"联系 13800000000","highlights":[],"issues":[],"nextActions":[],
                 "explanation":"KEEP","safetyNotice":null}
                """;
        assertThat(validator.validate(sensitive, facts()).status())
                .isEqualTo(AiOutputValidator.ValidationStatus.UNSAFE);
    }

    private static AiOutputValidator.AuthoritativeFacts facts() {
        return new AiOutputValidator.AuthoritativeFacts(Set.of(), Optional.of("KEEP"));
    }
}
