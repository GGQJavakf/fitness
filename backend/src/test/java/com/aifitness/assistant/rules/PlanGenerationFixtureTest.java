package com.aifitness.assistant.rules;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.rules.domain.PlanGenerationEngine;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.rules.domain.PlanValidationEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanGenerationFixtureTest {

    private static final RuleReference REFERENCE = new RuleReference("1.1.0", "1.0.0", "1.0.0");
    private static final PlanRulePolicy POLICY = policy(8, 12, 48);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validationEngineMatchesEveryM0PlanFixture() throws IOException {
        JsonNode document = JSON.readTree(Path.of(
                "..", "test-fixtures", "rules", "plan-validation-v1.json").toFile());
        PlanValidationEngine validator = new PlanValidationEngine(POLICY);

        document.path("cases").forEach(fixture -> {
            JsonNode input = fixture.path("input");
            PlanGenerationEngine.Candidate candidate = fixtureCandidate(input);
            List<PlanGenerationEngine.ValidationIssue> issues = validator.validate(
                    candidate, 90, fixtureFacts(candidate));
            String expected = fixture.at("/expected/reasonCodes/0").asText();
            if ("PLAN_WITHIN_CONFIGURED_LIMITS".equals(expected)) {
                assertThat(issues).as(fixture.path("id").asText()).isEmpty();
            } else {
                assertThat(issues).as(fixture.path("id").asText())
                        .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                        .contains(expected);
            }
        });
    }

    @Test
    void generatesTheSameCompleteCandidateForTheSameEligibleInput() {
        PlanGenerationEngine engine = engine();
        PlanGenerationEngine.GenerationInput input =
                input(3, 60, Set.of("SQUAT", "ROW", "PRESS", "HINGE"), Map.of());

        PlanGenerationEngine.GenerationResult first = engine.generate(input);
        PlanGenerationEngine.GenerationResult second = engine.generate(input);

        assertThat(second).isEqualTo(first);
        assertThat(first.status()).isEqualTo(PlanGenerationEngine.GenerationStatus.CANDIDATE_READY);
        assertThat(first.candidate()).isPresent();
        assertThat(first.candidate().orElseThrow().templateCode()).isEqualTo("FULL_BODY_3_DAY_V1");
        assertThat(first.candidate().orElseThrow().days()).hasSize(3);
        assertThat(first.candidate().orElseThrow().days().getFirst().exercises().getFirst().weightStatus())
                .isEqualTo(PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
        assertThat(first.issues())
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .containsExactly("INITIAL_WEIGHT_NEEDS_CALIBRATION");
    }

    @Test
    void frequenciesFromTwoToSixReturnCandidateOrActionableReason() {
        PlanGenerationEngine engine = engine();

        IntStream.rangeClosed(2, 6).forEach(frequency -> {
            PlanGenerationEngine.GenerationResult result =
                    engine.generate(input(
                            frequency, 60, Set.of("SQUAT", "ROW", "PRESS", "HINGE"), Map.of()));
            if (frequency == 3) {
                assertThat(result.status()).isEqualTo(PlanGenerationEngine.GenerationStatus.CANDIDATE_READY);
            } else {
                assertThat(result.status()).isEqualTo(PlanGenerationEngine.GenerationStatus.NO_CANDIDATE);
                assertThat(result.issues()).isNotEmpty();
                assertThat(result.issues()).allMatch(issue -> !issue.reasonCode().isBlank());
            }
        });
    }

    @Test
    void rejectsTemplatesAfterEquipmentFilteringAndPlansThatExceedAvailableTime() {
        PlanGenerationEngine engine = engine();

        PlanGenerationEngine.GenerationResult missingEquipment =
                engine.generate(input(3, 60, Set.of(), Map.of()));
        PlanGenerationEngine.GenerationResult tooShort =
                engine.generate(input(3, 30, Set.of("SQUAT", "ROW", "PRESS", "HINGE"), Map.of()));

        assertThat(missingEquipment.issues())
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .containsExactly("NO_ELIGIBLE_TEMPLATE");
        assertThat(tooShort.issues())
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .containsOnly("SESSION_DURATION_EXCEEDED")
                .hasSize(3);
    }

    private static PlanGenerationEngine.GenerationInput input(
            int frequency,
            int sessionMinutes,
            Set<String> eligibleExercises,
            Map<String, Integer> lockedNumbers) {
        PlanGenerationEngine.Exercise squat = new PlanGenerationEngine.Exercise(
                "SQUAT", 3, 8, 12, 120, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
        PlanGenerationEngine.Exercise row = new PlanGenerationEngine.Exercise(
                "ROW", 3, 8, 12, 120, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
        PlanGenerationEngine.Exercise press = new PlanGenerationEngine.Exercise(
                "PRESS", 3, 8, 12, 120, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
        PlanGenerationEngine.Exercise hinge = new PlanGenerationEngine.Exercise(
                "HINGE", 3, 8, 12, 120, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
        List<PlanGenerationEngine.Exercise> exercises = List.of(squat, row, press, hinge);
        List<PlanGenerationEngine.Day> days = List.of(
                new PlanGenerationEngine.Day("DAY_A", "A", exercises),
                new PlanGenerationEngine.Day("DAY_B", "B", exercises),
                new PlanGenerationEngine.Day("DAY_C", "C", exercises));
        PlanGenerationEngine.Template template =
                new PlanGenerationEngine.Template("FULL_BODY_3_DAY_V1", "三日全身", 3, days);
        Map<String, PlanValidationEngine.ExerciseFacts> facts = eligibleExercises.stream()
                .collect(java.util.stream.Collectors.toMap(
                        code -> code,
                        code -> new PlanValidationEngine.ExerciseFacts(code, Set.of(code))));
        return new PlanGenerationEngine.GenerationInput(
                REFERENCE, frequency, sessionMinutes, List.of(template), facts, lockedNumbers);
    }

    @Test
    void consumesPolicyValuesAndChecksMovementRecoveryAndVolume() {
        PlanRulePolicy strict = policy(1, 6, 72);
        PlanValidationEngine validator = new PlanValidationEngine(strict);
        PlanGenerationEngine.Candidate candidate = new PlanGenerationEngine.Candidate(
                "STRICT", "Strict", List.of(
                        day("A", "SQUAT_A", "SQUAT_B"),
                        day("B", "SQUAT_A", "ROW"),
                        day("C", "SQUAT_A", "ROW")), REFERENCE);
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "SQUAT_A", new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("GLUTES")),
                "SQUAT_B", new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("GLUTES")),
                "ROW", new PlanValidationEngine.ExerciseFacts("PULL", Set.of("BACK")));

        assertThat(validator.validate(candidate, 90, facts))
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .contains("DUPLICATE_MOVEMENT_PATTERN", "PRIMARY_MUSCLE_VOLUME_OUT_OF_RANGE",
                        "RECOVERY_WINDOW_TOO_SHORT");
        assertThat(validator.validate(candidate, 90, facts))
                .filteredOn(issue -> issue.reasonCode().equals("RECOVERY_WINDOW_TOO_SHORT"))
                .allMatch(issue -> issue.severity() == PlanGenerationEngine.ValidationSeverity.WARNING);
    }

    private static PlanGenerationEngine engine() {
        return new PlanGenerationEngine(new PlanValidationEngine(POLICY));
    }

    private static PlanGenerationEngine.Day day(String code, String... exercises) {
        return new PlanGenerationEngine.Day(code, code, java.util.Arrays.stream(exercises)
                .map(exercise -> new PlanGenerationEngine.Exercise(
                        exercise, 4, 8, 12, 90, PlanGenerationEngine.WeightStatus.KNOWN))
                .toList());
    }

    private static Map<String, PlanValidationEngine.ExerciseFacts> fixtureFacts(
            PlanGenerationEngine.Candidate candidate) {
        return candidate.days().stream().flatMap(day -> day.exercises().stream())
                .map(PlanGenerationEngine.Exercise::exerciseCode).distinct()
                .collect(java.util.stream.Collectors.toMap(
                        code -> code,
                        code -> new PlanValidationEngine.ExerciseFacts(code, Set.of(code))));
    }

    private static PlanRulePolicy policy(int maximumPatternOccurrences, int maximumMuscleSets, int recoveryHours) {
        return new PlanRulePolicy(
                "1.1.0",
                new PlanRulePolicy.PlanLimits(2, 5, 8, 90),
                new PlanRulePolicy.Prescription(2, 4, 5, 15),
                new PlanRulePolicy.Rest(45, 240),
                new PlanRulePolicy.Duration(45, 75),
                new PlanRulePolicy.Balance(maximumPatternOccurrences, maximumMuscleSets, recoveryHours));
    }

    private static PlanGenerationEngine.Candidate fixtureCandidate(JsonNode input) {
        int dayCount = input.path("sessionsPerWeek").asInt();
        int exerciseCount = input.path("exerciseCount").asInt();
        List<PlanGenerationEngine.Day> days = new ArrayList<>();
        for (int dayIndex = 0; dayIndex < dayCount; dayIndex++) {
            List<PlanGenerationEngine.Exercise> exercises = new ArrayList<>();
            for (int exerciseIndex = 0; exerciseIndex < exerciseCount; exerciseIndex++) {
                exercises.add(new PlanGenerationEngine.Exercise(
                        "EXERCISE_" + exerciseIndex,
                        input.path("workSets").asInt(),
                        input.path("repMin").asInt(),
                        input.path("repMax").asInt(),
                        input.path("restSeconds").asInt(),
                        PlanGenerationEngine.WeightStatus.valueOf(input.path("weightStatus").asText())));
            }
            days.add(new PlanGenerationEngine.Day("DAY_" + dayIndex, "Day " + dayIndex, exercises));
        }
        return new PlanGenerationEngine.Candidate(
                "FIXTURE", "Fixture", days,
                PlanGenerationEngine.WeightUnit.valueOf(input.path("unit").asText()), REFERENCE);
    }
}
