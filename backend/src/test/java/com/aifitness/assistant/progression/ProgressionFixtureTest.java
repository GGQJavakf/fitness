package com.aifitness.assistant.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.progression.domain.EquipmentRoundingPolicy;
import com.aifitness.assistant.progression.domain.ProgressionDecision;
import com.aifitness.assistant.progression.domain.ProgressionEngine;
import com.aifitness.assistant.rules.domain.RuleEvaluationInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressionFixtureTest {
    private static final Path FIXTURE = Path.of("..", "test-fixtures", "rules", "progression-v1.json");
    private static final ProgressionDecision.Prescription CURRENT =
            new ProgressionDecision.Prescription(new BigDecimal("40"), 8, 12);
    private static final ProgressionEngine.EnginePolicy POLICY =
            new ProgressionEngine.EnginePolicy("double-progression-v1", new BigDecimal("0.05"));
    private static final EquipmentRoundingPolicy EQUIPMENT =
            new EquipmentRoundingPolicy("KG", List.of(new BigDecimal("2.5"), new BigDecimal("5")));

    @Test
    void allM0FixturesProduceTheUniqueExpectedDecisionAndReason() throws IOException {
        JsonNode document = new ObjectMapper().readTree(Files.readString(FIXTURE));
        ProgressionEngine engine = new ProgressionEngine();

        for (JsonNode fixture : document.path("cases")) {
            ProgressionDecision decision = engine.evaluate(input(document, fixture), CURRENT, POLICY, EQUIPMENT);
            JsonNode expected = fixture.path("expected");
            assertThat(decision.decision().name()).as(fixture.path("id").asText())
                    .isEqualTo(expected.path("outcome").asText());
            assertThat(decision.reasonCode().name()).isEqualTo(expected.path("reasonCodes").get(0).asText());
            assertThat(decision.application().name()).isEqualTo(expected.path("application").asText());
            assertThat(decision.algorithmVersion()).isEqualTo("double-progression-v1");
            assertNumericResult(decision);
        }
    }

    @Test
    void equipmentRoundingRetainsRawAndRoundedEvidence() {
        ProgressionEngine engine = new ProgressionEngine();
        ProgressionDecision reduced = engine.evaluate(signals(false, 2, false, null), CURRENT, POLICY, EQUIPMENT);
        ProgressionDecision increased = engine.evaluate(signals(true, 0, false, 2), CURRENT, POLICY, EQUIPMENT);

        assertThat(reduced.rawRecommendedWeight()).contains(new BigDecimal("38"));
        assertThat(reduced.roundedWeight()).contains(new BigDecimal("37.5"));
        assertThat(reduced.roundingRule()).contains("FLOOR_TO_MIN_INCREMENT");
        assertThat(reduced.availableEquipmentSteps()).containsExactly(new BigDecimal("2.5"), new BigDecimal("5"));
        assertThat(increased.rawRecommendedWeight()).contains(new BigDecimal("42.5"));
        assertThat(increased.roundedWeight()).contains(new BigDecimal("42.5"));
        assertThat(increased.roundingRule()).contains("ADD_ONE_MIN_INCREMENT");
    }

    @Test
    void p0EquipmentPolicyRejectsUnsupportedUnitsAndInvalidSteps() {
        assertThatThrownBy(() -> new EquipmentRoundingPolicy("LB", List.of(BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only supports KG");
        assertThatThrownBy(() -> new EquipmentRoundingPolicy("KG", List.of(BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive values");
        assertThatThrownBy(() -> new EquipmentRoundingPolicy("KG", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive values");
    }

    @Test
    void reductionRoundingNeverProducesNegativeWeight() {
        EquipmentRoundingPolicy policy = new EquipmentRoundingPolicy("KG", List.of(new BigDecimal("2.5")));

        assertThat(policy.roundReduction(BigDecimal.ONE, new BigDecimal("0.95")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static void assertNumericResult(ProgressionDecision decision) {
        switch (decision.decision()) {
            case INCREASE -> {
                assertThat(decision.roundedWeight()).isPresent();
                assertThat(decision.recommendedPrescription().weightKg()).isEqualByComparingTo("42.5");
            }
            case REDUCE -> {
                assertThat(decision.rawRecommendedWeight()).contains(new BigDecimal("38"));
                assertThat(decision.roundedWeight()).contains(new BigDecimal("37.5"));
                assertThat(decision.recommendedPrescription().weightKg()).isEqualByComparingTo("37.5");
            }
            case KEEP, REVIEW -> {
                assertThat(decision.recommendedPrescription()).isEqualTo(CURRENT);
                assertThat(decision.rawRecommendedWeight()).isEmpty();
                assertThat(decision.roundedWeight()).isEmpty();
                assertThat(decision.availableEquipmentSteps()).isEmpty();
            }
        }
    }

    private static RuleEvaluationInput.Progression input(JsonNode document, JsonNode fixture) {
        JsonNode defaults = document.path("inputDefaults");
        JsonNode values = fixture.path("input");
        return new RuleEvaluationInput.Progression(
                document.at("/metadata/ruleVersion").asText(),
                RuleEvaluationInput.WeightUnit.valueOf(text(values, defaults, "unit")),
                bool(values, defaults, "historySufficient"), bool(values, defaults, "painOrSafetyFlag"),
                bool(values, defaults, "anomalousInput"), bool(values, defaults, "conflictingInput"),
                bool(values, defaults, "longTrainingGap"), bool(values, defaults, "variantChanged"),
                bool(values, defaults, "bodyweightRequiresConfirmation"),
                integer(values, defaults, "consecutiveBelowMin"), bool(values, defaults, "multipleFailedSets"),
                bool(values, defaults, "allSetsAtMax"), integer(values, defaults, "consecutiveAllAtMax"),
                bool(values, defaults, "oneSessionBelowMin"), bool(values, defaults, "weightUserLocked"),
                values.has("rir") ? values.path("rir").intValue() : null);
    }

    private static RuleEvaluationInput.Progression signals(
            boolean allAtMax, int consecutiveBelow, boolean locked, Integer rir) {
        return new RuleEvaluationInput.Progression(
                "1.0.0", RuleEvaluationInput.WeightUnit.KG, true, false, false, false, false, false,
                false, consecutiveBelow, false, allAtMax, allAtMax ? 1 : 0, false, locked, rir);
    }

    private static boolean bool(JsonNode values, JsonNode defaults, String name) {
        return values.has(name) ? values.path(name).booleanValue() : defaults.path(name).booleanValue();
    }

    private static int integer(JsonNode values, JsonNode defaults, String name) {
        return values.has(name) ? values.path(name).intValue() : defaults.path(name).intValue();
    }

    private static String text(JsonNode values, JsonNode defaults, String name) {
        return values.has(name) ? values.path(name).textValue() : defaults.path(name).textValue();
    }
}
