package com.aifitness.assistant.rules;

import com.aifitness.assistant.rules.domain.RuleEvaluationInput;
import com.aifitness.assistant.rules.domain.RuleEvaluationResult;
import com.aifitness.assistant.rules.domain.RuleEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RuleFixtureContractTest {

    private static final Path FIXTURE_ROOT = Path.of("..", "test-fixtures", "rules");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PLAN_FIELDS = Set.of(
            "unit", "sessionsPerWeek", "exerciseCount", "workSets", "repMin", "repMax",
            "restSeconds", "weightStatus");
    private static final Set<String> PROGRESSION_FIELDS = Set.of(
            "unit", "historySufficient", "painOrSafetyFlag", "anomalousInput", "conflictingInput",
            "longTrainingGap", "variantChanged", "bodyweightRequiresConfirmation",
            "consecutiveBelowMin", "multipleFailedSets", "allSetsAtMax", "consecutiveAllAtMax",
            "oneSessionBelowMin", "weightUserLocked");

    @Test
    void domainBoundaryUsesTypedInputsAndResultsWithoutFixtureIdentity() {
        RuleEvaluationInput.PlanValidation input = new RuleEvaluationInput.PlanValidation(
                "1.0.0", RuleEvaluationInput.WeightUnit.KG, 3, 6, 3, 8, 12, 90,
                RuleEvaluationInput.WeightStatus.KNOWN);
        RuleEvaluationResult.PlanValidation result = new RuleEvaluationResult.PlanValidation(
                input.ruleVersion(), RuleEvaluationResult.PlanOutcome.VALID,
                RuleEvaluationResult.PlanApplication.ACCEPTED,
                List.of(RuleEvaluationResult.PlanReasonCode.PLAN_WITHIN_CONFIGURED_LIMITS));

        assertThat(input.ruleVersion()).isEqualTo("1.0.0");
        assertThat(result.reasonCodes())
                .containsExactly(RuleEvaluationResult.PlanReasonCode.PLAN_WITHIN_CONFIGURED_LIMITS);
        assertThatIllegalArgumentException().isThrownBy(() -> new RuleEvaluationInput.PlanValidation(
                " ", RuleEvaluationInput.WeightUnit.KG, 3, 6, 3, 8, 12, 90,
                RuleEvaluationInput.WeightStatus.KNOWN));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuleEvaluationResult.PlanValidation(
                "1.0.0", RuleEvaluationResult.PlanOutcome.VALID,
                RuleEvaluationResult.PlanApplication.ACCEPTED, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuleEvaluationResult.PlanValidation(
                "1.0.0", RuleEvaluationResult.PlanOutcome.VALID,
                RuleEvaluationResult.PlanApplication.REJECTED,
                List.of(RuleEvaluationResult.PlanReasonCode.PLAN_WITHIN_CONFIGURED_LIMITS)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuleEvaluationResult.Progression(
                "1.0.0", RuleEvaluationResult.ProgressionOutcome.REVIEW,
                RuleEvaluationResult.Application.RECOMMENDATION_PENDING,
                List.of(RuleEvaluationResult.ProgressionReasonCode.PAIN_OR_SAFETY_FLAG)));
    }

    @Test
    void fixtureDocumentsAreStrictVersionedUniqueCompleteAndDigestProtected()
            throws IOException, NoSuchAlgorithmException {
        assertFixtureDocument("plan-validation-v1.json", "PLAN_VALIDATION");
        assertFixtureDocument("progression-v1.json", "PROGRESSION");
    }

    @Test
    void strictFixtureValidationRejectsWrongTypesDuplicateInputsAndContradictions() throws IOException {
        JsonNode plan = readFixture("plan-validation-v1.json");
        ObjectNode wrongType = plan.deepCopy();
        ((ObjectNode) wrongType.at("/cases/0/input")).put("sessionsPerWeek", "3");
        assertThat(fixtureErrors(wrongType, "PLAN_VALIDATION")).contains("invalid plan input types");

        ObjectNode wrongWeightStatus = plan.deepCopy();
        ((ObjectNode) wrongWeightStatus.at("/cases/0/input")).put("weightStatus", "UNKNOWN");
        assertThat(fixtureErrors(wrongWeightStatus, "PLAN_VALIDATION")).contains("invalid weightStatus");

        ObjectNode duplicateInput = plan.deepCopy();
        ((ObjectNode) duplicateInput.at("/cases/1")).set("input", duplicateInput.at("/cases/0/input").deepCopy());
        assertThat(fixtureErrors(duplicateInput, "PLAN_VALIDATION")).contains("duplicate effective input");

        JsonNode progression = readFixture("progression-v1.json");
        ObjectNode contradiction = progression.deepCopy();
        ((ObjectNode) contradiction.at("/cases/0/input")).put("consecutiveBelowMin", 2);
        assertThat(fixtureErrors(contradiction, "PROGRESSION")).contains("contradictory performance facts");
    }

    @Test
    void planFixturesMatchIndependentTypedOracleAndRepeatDeterministically() throws IOException {
        JsonNode document = readFixture("plan-validation-v1.json");
        RuleEvaluator.Plan evaluator =
                RuleFixtureContractTest::evaluatePlan;

        document.path("cases").forEach(fixture -> {
            RuleEvaluationInput.PlanValidation first = toPlanInput(document, fixture);
            RuleEvaluationInput.PlanValidation second = toPlanInput(document, fixture);
            assertThat(second).isEqualTo(first);
            RuleEvaluationResult.PlanValidation firstResult = evaluator.evaluate(first);
            RuleEvaluationResult.PlanValidation secondResult = evaluator.evaluate(second);
            assertThat(secondResult).isEqualTo(firstResult);
            assertExpected(fixture, firstResult.outcome().name(), firstResult.reasonCodes().getFirst().name(),
                    firstResult.application().name());
        });
    }

    @Test
    void progressionFixturesMatchIndependentTypedPriorityOracleAndRepeatDeterministically() throws IOException {
        JsonNode document = readFixture("progression-v1.json");
        RuleEvaluator.Progression evaluator =
                RuleFixtureContractTest::evaluateProgression;

        document.path("cases").forEach(fixture -> {
            RuleEvaluationInput.Progression first = toProgressionInput(document, fixture);
            RuleEvaluationInput.Progression second = toProgressionInput(document, fixture);
            assertThat(second).isEqualTo(first);
            RuleEvaluationResult.Progression firstResult = evaluator.evaluate(first);
            RuleEvaluationResult.Progression secondResult = evaluator.evaluate(second);
            assertThat(secondResult).isEqualTo(firstResult);
            assertExpected(fixture, firstResult.outcome().name(), firstResult.reasonCodes().getFirst().name(),
                    firstResult.application().name());
        });
    }

    @Test
    void everyReviewPriorityOverridesLowerPerformanceSignals() throws IOException {
        JsonNode document = readFixture("progression-v1.json");
        assertPriority(document, "PROGRESSION_PAIN_OVERRIDES_MAX", "PAIN_OR_SAFETY_FLAG");
        assertPriority(document, "PROGRESSION_ANOMALY_OVERRIDES_MAX", "ANOMALOUS_INPUT");
        assertPriority(document, "PROGRESSION_CONFLICT_OVERRIDES_MAX", "CONFLICTING_INPUT");
        assertPriority(document, "PROGRESSION_INSUFFICIENT_HISTORY", "INSUFFICIENT_HISTORY");
        assertPriority(document, "PROGRESSION_LONG_GAP", "LONG_TRAINING_GAP");
        assertPriority(document, "PROGRESSION_VARIANT_CHANGED", "VARIANT_CHANGED");
        assertPriority(document, "PROGRESSION_UNIT_CHANGED", "UNIT_CHANGED");
        assertPriority(document, "PROGRESSION_BODYWEIGHT_CONFIRMATION", "BODYWEIGHT_REQUIRES_CONFIRMATION");
    }

    private static void assertFixtureDocument(String file, String ruleSet)
            throws IOException, NoSuchAlgorithmException {
        JsonNode document = readFixture(file);
        assertThat(fixtureErrors(document, ruleSet)).as(file + " strict contract errors").isEmpty();
        JsonNode metadata = document.path("metadata");
        assertThat(metadata.path("fixtureVersion").asText()).isEqualTo("1.0.0");
        assertThat(metadata.path("ruleVersion").asText())
                .isEqualTo("PLAN_VALIDATION".equals(ruleSet) ? "1.1.0" : "1.0.0");
        assertThat(metadata.path("oracleVersion").asText()).isEqualTo("M0-04-1");
        assertThat(metadata.path("ruleSet").asText()).isEqualTo(ruleSet);
        assertThat(metadata.path("generationConstraints")).isNotEmpty();

        String expectedDigest = metadata.path("digestSha256").asText();
        ((ObjectNode) metadata).remove("digestSha256");
        byte[] canonical = JSON.writeValueAsBytes(canonicalize(document));
        String actualDigest = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical));
        assertThat(actualDigest).as(file + " canonical SHA-256").isEqualTo(expectedDigest);
    }

    private static List<String> fixtureErrors(JsonNode document, String ruleSet) {
        List<String> errors = new ArrayList<>();
        if (!document.path("metadata").isObject() || !document.path("cases").isArray()
                || document.path("cases").isEmpty()) {
            errors.add("missing document structure");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        Set<String> effectiveInputs = new HashSet<>();
        Set<String> required = "PLAN_VALIDATION".equals(ruleSet) ? PLAN_FIELDS : PROGRESSION_FIELDS;
        document.path("cases").forEach(fixture -> {
            String id = fixture.path("id").asText();
            if (id.isBlank() || !ids.add(id)) {
                errors.add("duplicate or blank fixture id");
            }
            JsonNode input = effectiveInput(document, fixture);
            Set<String> fields = new HashSet<>();
            input.fieldNames().forEachRemaining(fields::add);
            if (!fields.containsAll(required)
                    || fields.stream().anyMatch(field -> !required.contains(field) && !"rir".equals(field))) {
                errors.add("invalid effective input fields");
            }
            try {
                String canonicalInput = JSON.writeValueAsString(canonicalize(input));
                if (!effectiveInputs.add(canonicalInput)) {
                    errors.add("duplicate effective input");
                }
            } catch (IOException exception) {
                errors.add("unreadable effective input");
            }
            if ("PLAN_VALIDATION".equals(ruleSet)) {
                validatePlanTypes(input, errors);
            } else {
                validateProgressionTypes(input, errors);
            }
            JsonNode expected = fixture.path("expected");
            if (!expected.isObject() || !expected.path("outcome").isTextual()
                    || !expected.path("reasonCodes").isArray() || expected.path("reasonCodes").size() != 1
                    || !expected.path("reasonCodes").get(0).isTextual()
                    || !expected.path("application").isTextual()) {
                errors.add("invalid expected result");
            } else {
                validateExpectedEnums(expected, ruleSet, errors);
            }
        });
        return errors;
    }

    private static void validatePlanTypes(JsonNode input, List<String> errors) {
        boolean integers = Set.of("sessionsPerWeek", "exerciseCount", "workSets", "repMin", "repMax", "restSeconds")
                .stream().allMatch(field -> input.path(field).isIntegralNumber());
        if (!input.path("unit").isTextual() || !input.path("weightStatus").isTextual() || !integers) {
            errors.add("invalid plan input types");
        }
        if (!Set.of("KG", "LB").contains(input.path("unit").asText())) {
            errors.add("invalid unit");
        }
        if (!Set.of("KNOWN", "NEEDS_CALIBRATION", "BODYWEIGHT").contains(input.path("weightStatus").asText())) {
            errors.add("invalid weightStatus");
        }
    }

    private static void validateProgressionTypes(JsonNode input, List<String> errors) {
        Set<String> booleans = Set.of(
                "historySufficient", "painOrSafetyFlag", "anomalousInput", "conflictingInput",
                "longTrainingGap", "variantChanged", "bodyweightRequiresConfirmation", "multipleFailedSets",
                "allSetsAtMax", "oneSessionBelowMin", "weightUserLocked");
        if (!input.path("unit").isTextual() || booleans.stream().anyMatch(field -> !input.path(field).isBoolean())
                || !input.path("consecutiveBelowMin").isIntegralNumber()
                || !input.path("consecutiveAllAtMax").isIntegralNumber()
                || input.path("consecutiveBelowMin").asInt() < 0
                || input.path("consecutiveAllAtMax").asInt() < 0
                || (input.has("rir") && (!input.path("rir").isIntegralNumber()
                || input.path("rir").asInt() < 0 || input.path("rir").asInt() > 3))) {
            errors.add("invalid progression input types");
        }
        if (!Set.of("KG", "LB").contains(input.path("unit").asText())) {
            errors.add("invalid unit");
        }
        boolean reduction = input.path("consecutiveBelowMin").asInt() >= 2
                || input.path("multipleFailedSets").asBoolean() || input.path("oneSessionBelowMin").asBoolean();
        if (reduction && input.path("allSetsAtMax").asBoolean()) {
            errors.add("contradictory performance facts");
        }
    }

    private static void validateExpectedEnums(JsonNode expected, String ruleSet, List<String> errors) {
        try {
            if ("PLAN_VALIDATION".equals(ruleSet)) {
                RuleEvaluationResult.PlanReasonCode.valueOf(expected.path("reasonCodes").get(0).asText());
                RuleEvaluationResult.PlanOutcome.valueOf(expected.path("outcome").asText());
                RuleEvaluationResult.PlanApplication.valueOf(expected.path("application").asText());
            } else {
                RuleEvaluationResult.ProgressionReasonCode.valueOf(expected.path("reasonCodes").get(0).asText());
                RuleEvaluationResult.ProgressionOutcome.valueOf(expected.path("outcome").asText());
                RuleEvaluationResult.Application.valueOf(expected.path("application").asText());
            }
        } catch (IllegalArgumentException exception) {
            errors.add("invalid expected enum");
        }
    }

    private static RuleEvaluationResult.PlanValidation evaluatePlan(RuleEvaluationInput.PlanValidation input) {
        RuleEvaluationResult.PlanOutcome outcome;
        RuleEvaluationResult.PlanReasonCode reason;
        RuleEvaluationResult.PlanApplication application;
        if (input.unit() != RuleEvaluationInput.WeightUnit.KG) {
            outcome = RuleEvaluationResult.PlanOutcome.ERROR;
            reason = RuleEvaluationResult.PlanReasonCode.P0_UNIT_NOT_SUPPORTED;
            application = RuleEvaluationResult.PlanApplication.REJECTED;
        } else if (input.sessionsPerWeek() < 2 || input.sessionsPerWeek() > 6) {
            outcome = RuleEvaluationResult.PlanOutcome.ERROR;
            reason = RuleEvaluationResult.PlanReasonCode.SESSION_FREQUENCY_OUT_OF_RANGE;
            application = RuleEvaluationResult.PlanApplication.REJECTED;
        } else if (input.exerciseCount() < 1 || input.exerciseCount() > 8) {
            outcome = RuleEvaluationResult.PlanOutcome.ERROR;
            reason = RuleEvaluationResult.PlanReasonCode.EXERCISE_COUNT_OUT_OF_RANGE;
            application = RuleEvaluationResult.PlanApplication.REJECTED;
        } else if (input.workSets() < 2 || input.workSets() > 4) {
            outcome = RuleEvaluationResult.PlanOutcome.ERROR;
            reason = RuleEvaluationResult.PlanReasonCode.WORK_SETS_OUT_OF_RANGE;
            application = RuleEvaluationResult.PlanApplication.REJECTED;
        } else if (input.repMin() < 5 || input.repMax() > 15 || input.repMin() > input.repMax()) {
            outcome = RuleEvaluationResult.PlanOutcome.ERROR;
            reason = RuleEvaluationResult.PlanReasonCode.REP_RANGE_OUT_OF_RANGE;
            application = RuleEvaluationResult.PlanApplication.REJECTED;
        } else if (input.restSeconds() < 45 || input.restSeconds() > 240) {
            outcome = RuleEvaluationResult.PlanOutcome.ERROR;
            reason = RuleEvaluationResult.PlanReasonCode.REST_OUT_OF_RANGE;
            application = RuleEvaluationResult.PlanApplication.REJECTED;
        } else if (input.weightStatus() == RuleEvaluationInput.WeightStatus.NEEDS_CALIBRATION) {
            outcome = RuleEvaluationResult.PlanOutcome.WARNING;
            reason = RuleEvaluationResult.PlanReasonCode.INITIAL_WEIGHT_NEEDS_CALIBRATION;
            application = RuleEvaluationResult.PlanApplication.CALIBRATION_REQUIRED;
        } else {
            outcome = RuleEvaluationResult.PlanOutcome.VALID;
            reason = RuleEvaluationResult.PlanReasonCode.PLAN_WITHIN_CONFIGURED_LIMITS;
            application = RuleEvaluationResult.PlanApplication.ACCEPTED;
        }
        return new RuleEvaluationResult.PlanValidation(input.ruleVersion(), outcome, application, List.of(reason));
    }

    private static RuleEvaluationResult.Progression evaluateProgression(RuleEvaluationInput.Progression input) {
        if (input.painOrSafetyFlag()) return review(input, RuleEvaluationResult.ProgressionReasonCode.PAIN_OR_SAFETY_FLAG);
        if (input.anomalousInput()) return review(input, RuleEvaluationResult.ProgressionReasonCode.ANOMALOUS_INPUT);
        if (input.conflictingInput()) return review(input, RuleEvaluationResult.ProgressionReasonCode.CONFLICTING_INPUT);
        if (!input.historySufficient()) return review(input, RuleEvaluationResult.ProgressionReasonCode.INSUFFICIENT_HISTORY);
        if (input.longTrainingGap()) return review(input, RuleEvaluationResult.ProgressionReasonCode.LONG_TRAINING_GAP);
        if (input.variantChanged()) return review(input, RuleEvaluationResult.ProgressionReasonCode.VARIANT_CHANGED);
        if (input.unit() != RuleEvaluationInput.WeightUnit.KG) return review(input, RuleEvaluationResult.ProgressionReasonCode.UNIT_CHANGED);
        if (input.bodyweightRequiresConfirmation()) {
            return review(input, RuleEvaluationResult.ProgressionReasonCode.BODYWEIGHT_REQUIRES_CONFIRMATION);
        }
        RuleEvaluationResult.ProgressionOutcome outcome;
        RuleEvaluationResult.ProgressionReasonCode reason;
        RuleEvaluationResult.Application application;
        if (input.consecutiveBelowMin() >= 2) {
            outcome = RuleEvaluationResult.ProgressionOutcome.REDUCE;
            reason = RuleEvaluationResult.ProgressionReasonCode.CONSECUTIVE_BELOW_MIN;
            application = RuleEvaluationResult.Application.RECOMMENDATION_PENDING;
        } else if (input.multipleFailedSets()) {
            outcome = RuleEvaluationResult.ProgressionOutcome.REDUCE;
            reason = RuleEvaluationResult.ProgressionReasonCode.MULTIPLE_FAILED_SETS;
            application = RuleEvaluationResult.Application.RECOMMENDATION_PENDING;
        } else if (input.allSetsAtMax() && input.rir() != null && input.rir() == 0) {
            outcome = RuleEvaluationResult.ProgressionOutcome.KEEP;
            reason = RuleEvaluationResult.ProgressionReasonCode.RIR_ZERO_AT_MAX;
            application = RuleEvaluationResult.Application.NO_CHANGE;
        } else if (input.allSetsAtMax() && input.rir() != null && input.rir() >= 1 && input.rir() <= 3) {
            outcome = RuleEvaluationResult.ProgressionOutcome.INCREASE;
            reason = RuleEvaluationResult.ProgressionReasonCode.ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR;
            application = RuleEvaluationResult.Application.RECOMMENDATION_PENDING;
        } else if (input.allSetsAtMax() && input.rir() == null && input.consecutiveAllAtMax() >= 2) {
            outcome = RuleEvaluationResult.ProgressionOutcome.INCREASE;
            reason = RuleEvaluationResult.ProgressionReasonCode.ALL_SETS_AT_MAX_TWICE_WITHOUT_RIR;
            application = RuleEvaluationResult.Application.RECOMMENDATION_PENDING;
        } else {
            outcome = RuleEvaluationResult.ProgressionOutcome.KEEP;
            reason = input.allSetsAtMax() ? RuleEvaluationResult.ProgressionReasonCode.PARTIAL_AT_MAX
                    : RuleEvaluationResult.ProgressionReasonCode.WITHIN_TARGET_RANGE;
            application = RuleEvaluationResult.Application.NO_CHANGE;
        }
        if (input.weightUserLocked() && outcome == RuleEvaluationResult.ProgressionOutcome.INCREASE) {
            outcome = RuleEvaluationResult.ProgressionOutcome.KEEP;
            reason = RuleEvaluationResult.ProgressionReasonCode.WEIGHT_USER_LOCKED;
            application = RuleEvaluationResult.Application.SUGGEST_ONLY;
        }
        return new RuleEvaluationResult.Progression(input.ruleVersion(), outcome, application, List.of(reason));
    }

    private static RuleEvaluationResult.Progression review(
            RuleEvaluationInput.Progression input, RuleEvaluationResult.ProgressionReasonCode reason) {
        return new RuleEvaluationResult.Progression(input.ruleVersion(), RuleEvaluationResult.ProgressionOutcome.REVIEW,
                RuleEvaluationResult.Application.REVIEW_REQUIRED, List.of(reason));
    }

    private static RuleEvaluationInput.PlanValidation toPlanInput(JsonNode document, JsonNode fixture) {
        JsonNode input = effectiveInput(document, fixture);
        return new RuleEvaluationInput.PlanValidation(document.at("/metadata/ruleVersion").asText(),
                RuleEvaluationInput.WeightUnit.valueOf(input.path("unit").asText()),
                input.path("sessionsPerWeek").asInt(), input.path("exerciseCount").asInt(),
                input.path("workSets").asInt(), input.path("repMin").asInt(), input.path("repMax").asInt(),
                input.path("restSeconds").asInt(),
                RuleEvaluationInput.WeightStatus.valueOf(input.path("weightStatus").asText()));
    }

    private static RuleEvaluationInput.Progression toProgressionInput(JsonNode document, JsonNode fixture) {
        JsonNode input = effectiveInput(document, fixture);
        return new RuleEvaluationInput.Progression(document.at("/metadata/ruleVersion").asText(),
                RuleEvaluationInput.WeightUnit.valueOf(input.path("unit").asText()),
                input.path("historySufficient").asBoolean(), input.path("painOrSafetyFlag").asBoolean(),
                input.path("anomalousInput").asBoolean(), input.path("conflictingInput").asBoolean(),
                input.path("longTrainingGap").asBoolean(), input.path("variantChanged").asBoolean(),
                input.path("bodyweightRequiresConfirmation").asBoolean(),
                input.path("consecutiveBelowMin").asInt(), input.path("multipleFailedSets").asBoolean(),
                input.path("allSetsAtMax").asBoolean(), input.path("consecutiveAllAtMax").asInt(),
                input.path("oneSessionBelowMin").asBoolean(), input.path("weightUserLocked").asBoolean(),
                input.has("rir") ? input.path("rir").asInt() : null);
    }

    private static void assertPriority(JsonNode document, String id, String reason) {
        JsonNode fixture = findCase(document.path("cases"), id);
        RuleEvaluationInput.Progression input = toProgressionInput(document, fixture);
        assertThat(input.allSetsAtMax()).as(id + " lower-priority signal").isTrue();
        RuleEvaluationResult.Progression result = evaluateProgression(input);
        assertThat(result.outcome()).isEqualTo(RuleEvaluationResult.ProgressionOutcome.REVIEW);
        assertThat(result.reasonCodes().getFirst().name()).isEqualTo(reason);
    }

    private static void assertExpected(
            JsonNode fixture, String outcome, String reason, String application) {
        assertThat(fixture.at("/expected/outcome").asText()).as(fixture.path("id").asText()).isEqualTo(outcome);
        assertThat(fixture.at("/expected/reasonCodes/0").asText()).as(fixture.path("id").asText()).isEqualTo(reason);
        if (application != null) {
            assertThat(fixture.at("/expected/application").asText()).as(fixture.path("id").asText())
                    .isEqualTo(application);
        }
    }

    private static JsonNode findCase(JsonNode cases, String id) {
        for (JsonNode fixture : cases) {
            if (id.equals(fixture.path("id").asText())) return fixture;
        }
        throw new AssertionError("Missing fixture: " + id);
    }

    private static JsonNode readFixture(String file) throws IOException {
        return JSON.readTree(FIXTURE_ROOT.resolve(file).toFile());
    }

    private static JsonNode effectiveInput(JsonNode document, JsonNode fixture) {
        ObjectNode effective = JSON.createObjectNode();
        if (document.path("inputDefaults").isObject()) effective.setAll((ObjectNode) document.path("inputDefaults"));
        effective.setAll((ObjectNode) fixture.path("input"));
        return effective;
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted(Comparator.naturalOrder()).forEach(name -> sorted.set(name, canonicalize(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            var array = JSON.createArrayNode();
            node.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return node.deepCopy();
    }
}
