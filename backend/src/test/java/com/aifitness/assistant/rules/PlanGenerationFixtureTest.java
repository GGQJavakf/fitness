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

    private static final RuleReference REFERENCE = new RuleReference("1.6.0", "1.0.0", "1.0.0");
    private static final PlanRulePolicy POLICY = policy(2, 12, 48);
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
                input(3, 60, Set.of("SQUAT", "ROW", "PRESS", "HINGE", "CORE"), Map.of());

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
                            frequency, 60, Set.of("SQUAT", "ROW", "PRESS", "HINGE", "CORE"), Map.of()));
            if (result.status() == PlanGenerationEngine.GenerationStatus.CANDIDATE_READY) {
                assertThat(result.candidate().orElseThrow().days()).hasSize(frequency);
            } else {
                assertThat(result.status()).isEqualTo(PlanGenerationEngine.GenerationStatus.NO_CANDIDATE);
                assertThat(result.candidate()).isEmpty();
                assertThat(result.issues()).anyMatch(issue -> java.util.Set.of(
                                "RECOVERY_WINDOW_TOO_SHORT", "INSUFFICIENT_ELIGIBLE_EXERCISES")
                        .contains(issue.reasonCode()));
            }
        });
    }

    @Test
    void rejectsTemplatesAfterEquipmentFilteringAndAdaptsPlansToAvailableTime() {
        PlanGenerationEngine engine = engine();

        PlanGenerationEngine.GenerationResult missingEquipment =
                engine.generate(input(3, 60, Set.of(), Map.of()));
        PlanGenerationEngine.GenerationResult tooShort =
                engine.generate(input(
                        3, 30, Set.of("SQUAT", "ROW", "PRESS", "HINGE", "CORE"), Map.of()));

        assertThat(missingEquipment.issues())
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .containsExactly("NO_ELIGIBLE_TEMPLATE");
        assertThat(tooShort.status()).isEqualTo(PlanGenerationEngine.GenerationStatus.CANDIDATE_READY);
        assertThat(tooShort.candidate().orElseThrow().days())
                .allSatisfy(day -> assertThat(day.exercises())
                        .isNotEmpty()
                        .hasSizeLessThanOrEqualTo(POLICY.planLimits().maximumExercisesPerSession()));
        assertThat(tooShort.issues())
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .doesNotContain("SESSION_DURATION_EXCEEDED");
    }

    @Test
    void prefersEquipmentTemplateWhenBothEquipmentAndBodyweightTemplatesAreEligible() {
        PlanGenerationEngine.Exercise equipmentExercise = new PlanGenerationEngine.Exercise(
                "DUMBBELL_SQUAT", 3, 8, 12, 90, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
        PlanGenerationEngine.Exercise bodyweightExercise = new PlanGenerationEngine.Exercise(
                "BODYWEIGHT_SQUAT", 3, 8, 12, 90, PlanGenerationEngine.WeightStatus.BODYWEIGHT);
        PlanGenerationEngine.Template bodyweight = repeatedTemplate(
                "A_BODYWEIGHT_2_DAY", 2, bodyweightExercise);
        PlanGenerationEngine.Template equipment = repeatedTemplate(
                "Z_EQUIPMENT_2_DAY", 2, equipmentExercise);
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "DUMBBELL_SQUAT",
                new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS"), false),
                "BODYWEIGHT_SQUAT",
                new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS"), true));

        PlanGenerationEngine.GenerationResult result = engine().generate(
                new PlanGenerationEngine.GenerationInput(
                        REFERENCE, 2, 60, List.of(bodyweight, equipment), facts, Map.of()));

        assertThat(result.candidate().orElseThrow().templateCode()).isEqualTo("Z_EQUIPMENT_2_DAY");
    }

    @Test
    void fallbackComposesFourOrFiveExercisesWithinTheCompleteFortyFiveMinuteBudget() {
        PlanGenerationEngine.Template template = threeExerciseTemplate();
        Map<String, PlanValidationEngine.ExerciseFacts> facts = personalizedFacts();

        PlanGenerationEngine.GenerationResult beginner = engine().generate(
                personalizedInput(
                        template,
                        facts,
                        PlanGenerationEngine.ExperienceLevel.BEGINNER,
                        PlanGenerationEngine.FitnessGoal.GENERAL_FITNESS));
        PlanGenerationEngine.GenerationResult advanced = engine().generate(
                personalizedInput(
                        template,
                        facts,
                        PlanGenerationEngine.ExperienceLevel.ADVANCED,
                        PlanGenerationEngine.FitnessGoal.GENERAL_FITNESS));

        assertThat(beginner.candidate()).isPresent();
        assertThat(advanced.candidate()).isPresent();
        assertThat(advanced.candidate().orElseThrow().days())
                .containsExactlyElementsOf(beginner.candidate().orElseThrow().days());
        assertThat(beginner.candidate().orElseThrow().days()).allSatisfy(day -> {
            assertThat(day.exercises().size()).isBetween(4, 5);
            int estimatedSeconds = 180 + (2 * 30) + day.exercises().stream()
                    .mapToInt(exercise -> exercise.workSets() * (45 + exercise.restSeconds()) + 75)
                    .sum();
            assertThat(estimatedSeconds).isLessThanOrEqualTo(45 * 60);
        });
        assertThat(beginner.issues())
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .doesNotContain("SESSION_DURATION_EXCEEDED");
    }

    @Test
    void goalChangesDeterministicPrescriptionWithoutImposingAnExerciseCount() {
        PlanGenerationEngine.Template template = threeExerciseTemplate();
        Map<String, PlanValidationEngine.ExerciseFacts> facts = personalizedFacts();

        PlanGenerationEngine.Candidate strength = engine().generate(
                        personalizedInput(
                                template,
                                facts,
                                PlanGenerationEngine.ExperienceLevel.BEGINNER,
                                PlanGenerationEngine.FitnessGoal.STRENGTH))
                .candidate().orElseThrow();
        PlanGenerationEngine.Candidate general = engine().generate(
                        personalizedInput(
                                template,
                                facts,
                                PlanGenerationEngine.ExperienceLevel.BEGINNER,
                                PlanGenerationEngine.FitnessGoal.GENERAL_FITNESS))
                .candidate().orElseThrow();

        PlanGenerationEngine.Exercise strengthExercise =
                strength.days().getFirst().exercises().getFirst();
        PlanGenerationEngine.Exercise generalExercise =
                general.days().getFirst().exercises().getFirst();
        assertThat(strengthExercise.repMin()).isEqualTo(5);
        assertThat(strengthExercise.restSeconds()).isEqualTo(120);
        assertThat(generalExercise.repMin()).isEqualTo(10);
        assertThat(generalExercise.restSeconds()).isEqualTo(75);
        assertThat(strength.days().getFirst().exercises().size())
                .isLessThanOrEqualTo(general.days().getFirst().exercises().size());
    }

    @Test
    void replacesAnUnavailableTemplateExerciseWithEligibleSameMovementPattern() {
        PlanGenerationEngine.Exercise unavailable = new PlanGenerationEngine.Exercise(
                "DUMBBELL_SQUAT", 3, 8, 12, 90, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
        PlanGenerationEngine.Template template = repeatedTemplate("GYM_2_DAY", 2, unavailable);
        Map<String, PlanValidationEngine.ExerciseFacts> eligible = Map.of(
                "BODYWEIGHT_SQUAT",
                new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS"), true));
        Map<String, PlanValidationEngine.ExerciseFacts> catalog = Map.of(
                "DUMBBELL_SQUAT",
                new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS"), false),
                "BODYWEIGHT_SQUAT",
                new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS"), true));

        PlanGenerationEngine.GenerationResult result = engine().generate(
                new PlanGenerationEngine.GenerationInput(
                        REFERENCE,
                        2,
                        30,
                        PlanGenerationEngine.ExperienceLevel.BEGINNER,
                        PlanGenerationEngine.FitnessGoal.GENERAL_FITNESS,
                        List.of(template),
                        eligible,
                        catalog,
                        Map.of()));

        assertThat(result.candidate()).isPresent();
        assertThat(result.candidate().orElseThrow().days())
                .allSatisfy(day -> assertThat(day.exercises())
                        .extracting(PlanGenerationEngine.Exercise::exerciseCode)
                        .containsExactly("BODYWEIGHT_SQUAT"));
        assertThat(result.candidate().orElseThrow().days().getFirst().exercises().getFirst().weightStatus())
                .isEqualTo(PlanGenerationEngine.WeightStatus.BODYWEIGHT);
    }

    private static PlanGenerationEngine.GenerationInput personalizedInput(
            PlanGenerationEngine.Template template,
            Map<String, PlanValidationEngine.ExerciseFacts> facts,
            PlanGenerationEngine.ExperienceLevel experience,
            PlanGenerationEngine.FitnessGoal goal) {
        return new PlanGenerationEngine.GenerationInput(
                REFERENCE, 3, 45, experience, goal, List.of(template), facts, facts, Map.of());
    }

    private static PlanGenerationEngine.Template threeExerciseTemplate() {
        List<PlanGenerationEngine.Exercise> exercises = List.of(
                new PlanGenerationEngine.Exercise(
                        "SQUAT", 3, 8, 12, 90, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION),
                new PlanGenerationEngine.Exercise(
                        "ROW", 3, 8, 12, 90, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION),
                new PlanGenerationEngine.Exercise(
                        "PRESS", 3, 8, 12, 90, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION));
        return new PlanGenerationEngine.Template(
                "THREE_DAY_BASE",
                "三日基础",
                3,
                IntStream.rangeClosed(1, 3)
                        .mapToObj(day -> new PlanGenerationEngine.Day(
                                "DAY_" + day, "第" + day + "天", exercises))
                        .toList());
    }

    private static Map<String, PlanValidationEngine.ExerciseFacts> personalizedFacts() {
        return Map.of(
                "SQUAT", new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS"), false),
                "ROW", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PULL", Set.of("BACK"), false),
                "PRESS", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PUSH", Set.of("CHEST"), false),
                "HINGE", new PlanValidationEngine.ExerciseFacts("HINGE", Set.of("HAMSTRINGS"), false),
                "CORE", new PlanValidationEngine.ExerciseFacts("CORE", Set.of("CORE"), true),
                "VERTICAL_PULL",
                new PlanValidationEngine.ExerciseFacts("VERTICAL_PULL", Set.of("BACK"), false));
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
        PlanGenerationEngine.Exercise core = new PlanGenerationEngine.Exercise(
                "CORE", 3, 8, 12, 90, PlanGenerationEngine.WeightStatus.BODYWEIGHT);
        List<PlanGenerationEngine.Exercise> exercises = List.of(squat, row, press, hinge, core);
        List<PlanGenerationEngine.Template> templates = IntStream.rangeClosed(2, 6)
                .mapToObj(value -> new PlanGenerationEngine.Template(
                        value == 3 ? "FULL_BODY_3_DAY_V1" : "TEST_" + value + "_DAY_V1",
                        value + "日训练",
                        value,
                        IntStream.rangeClosed(1, value)
                                .mapToObj(day -> new PlanGenerationEngine.Day(
                                        "DAY_" + day, "第" + day + "天", exercises))
                                .toList()))
                .toList();
        Map<String, PlanValidationEngine.ExerciseFacts> facts = eligibleExercises.stream()
                .collect(java.util.stream.Collectors.toMap(
                        code -> code,
                        code -> new PlanValidationEngine.ExerciseFacts(code, Set.of(code))));
        return new PlanGenerationEngine.GenerationInput(
                REFERENCE,
                frequency,
                sessionMinutes,
                PlanGenerationEngine.ExperienceLevel.BEGINNER,
                PlanGenerationEngine.FitnessGoal.GENERAL_FITNESS,
                templates,
                facts,
                facts,
                POLICY,
                lockedNumbers);
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

    @Test
    void rejectsAProfessionalPushDayWithDuplicateShoulderPressesAnUnrelatedSquatAndNoDirectTriceps() {
        PlanRulePolicy professional = policy(1, 12, 48);
        PlanValidationEngine validator = new PlanValidationEngine(professional);
        PlanGenerationEngine.Candidate candidate = new PlanGenerationEngine.Candidate(
                "UNPROFESSIONAL_PUSH",
                "重复肩推推日",
                List.of(new PlanGenerationEngine.Day(
                        "PUSH_A",
                        "推 A",
                        List.of(
                                exercise("BENCH_PRESS"),
                                exercise("OVERHEAD_PRESS"),
                                exercise("SEATED_PRESS"),
                                exercise("SQUAT")))),
                REFERENCE);
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "BENCH_PRESS", new PlanValidationEngine.ExerciseFacts(
                        "HORIZONTAL_PUSH", Set.of("CHEST", "TRICEPS")),
                "OVERHEAD_PRESS", new PlanValidationEngine.ExerciseFacts(
                        "VERTICAL_PUSH", Set.of("SHOULDERS", "TRICEPS")),
                "SEATED_PRESS", new PlanValidationEngine.ExerciseFacts(
                        "VERTICAL_PUSH", Set.of("SHOULDERS", "TRICEPS")),
                "SQUAT", new PlanValidationEngine.ExerciseFacts(
                        "SQUAT", Set.of("QUADRICEPS", "GLUTES")));

        assertThat(validator.validate(candidate, 45, facts))
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .contains(
                        "DUPLICATE_MOVEMENT_PATTERN",
                        "SESSION_FOCUS_MISMATCH",
                        "SESSION_REQUIRED_PATTERN_MISSING");
    }

    @Test
    void acceptsPushAndPullDaysWithOneCompoundPerPatternAndDirectArmWork() {
        PlanRulePolicy professional = policy(1, 12, 48);
        PlanValidationEngine validator = new PlanValidationEngine(professional);
        PlanGenerationEngine.Candidate candidate = new PlanGenerationEngine.Candidate(
                "PROFESSIONAL_UPPER",
                "专业推拉",
                List.of(
                        new PlanGenerationEngine.Day("PUSH_A", "推 A", List.of(
                                exercise("BENCH_PRESS"), exercise("OVERHEAD_PRESS"),
                                exercise("LATERAL_RAISE"), exercise("TRICEPS_EXTENSION"))),
                        new PlanGenerationEngine.Day("PULL_A", "拉 A", List.of(
                                exercise("ROW"), exercise("PULLDOWN"),
                                exercise("REVERSE_FLY"), exercise("BICEPS_CURL")))),
                REFERENCE);
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "BENCH_PRESS", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PUSH", Set.of("CHEST", "TRICEPS")),
                "OVERHEAD_PRESS", new PlanValidationEngine.ExerciseFacts("VERTICAL_PUSH", Set.of("SHOULDERS", "TRICEPS")),
                "LATERAL_RAISE", new PlanValidationEngine.ExerciseFacts("SHOULDER_ABDUCTION", Set.of("SHOULDERS")),
                "TRICEPS_EXTENSION", new PlanValidationEngine.ExerciseFacts("ELBOW_EXTENSION", Set.of("TRICEPS")),
                "ROW", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PULL", Set.of("BACK", "BICEPS")),
                "PULLDOWN", new PlanValidationEngine.ExerciseFacts("VERTICAL_PULL", Set.of("LATS", "BICEPS")),
                "REVERSE_FLY", new PlanValidationEngine.ExerciseFacts("SHOULDER_HORIZONTAL_ABDUCTION", Set.of("BACK", "SHOULDERS")),
                "BICEPS_CURL", new PlanValidationEngine.ExerciseFacts("ELBOW_FLEXION", Set.of("BICEPS")));

        assertThat(validator.validate(candidate, 45, facts))
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .doesNotContain(
                        "DUPLICATE_MOVEMENT_PATTERN",
                        "SESSION_FOCUS_MISMATCH",
                        "SESSION_REQUIRED_PATTERN_MISSING");
    }

    @Test
    void rejectsAFullBodyWeekThatOmitsAvailableDirectBicepsAndTricepsWork() {
        PlanRulePolicy professional = policy(1, 12, 48);
        PlanValidationEngine validator = new PlanValidationEngine(professional);
        PlanGenerationEngine.Candidate candidate = new PlanGenerationEngine.Candidate(
                "FULL_BODY_WITHOUT_ARMS",
                "缺少直接手臂训练",
                List.of(
                        new PlanGenerationEngine.Day("DAY_A", "全身 A", List.of(
                                exercise("SQUAT"), exercise("BENCH_PRESS"),
                                exercise("ROW"), exercise("CORE"))),
                        new PlanGenerationEngine.Day("DAY_B", "全身 B", List.of(
                                exercise("HINGE"), exercise("OVERHEAD_PRESS"),
                                exercise("PULLDOWN"), exercise("CORE")))),
                REFERENCE);
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "SQUAT", new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("QUADRICEPS", "GLUTES")),
                "HINGE", new PlanValidationEngine.ExerciseFacts("HINGE", Set.of("HAMSTRINGS", "GLUTES")),
                "BENCH_PRESS", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PUSH", Set.of("CHEST")),
                "OVERHEAD_PRESS", new PlanValidationEngine.ExerciseFacts("VERTICAL_PUSH", Set.of("SHOULDERS")),
                "ROW", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PULL", Set.of("BACK")),
                "PULLDOWN", new PlanValidationEngine.ExerciseFacts("VERTICAL_PULL", Set.of("BACK")),
                "CORE", new PlanValidationEngine.ExerciseFacts("CORE", Set.of("CORE")),
                "BICEPS_CURL", new PlanValidationEngine.ExerciseFacts("ELBOW_FLEXION", Set.of("BICEPS")),
                "TRICEPS_EXTENSION", new PlanValidationEngine.ExerciseFacts("ELBOW_EXTENSION", Set.of("TRICEPS")));

        assertThat(validator.validate(candidate, 45, facts))
                .filteredOn(issue -> issue.reasonCode().equals("WEEKLY_DIRECT_ARM_PATTERN_MISSING"))
                .extracting(PlanGenerationEngine.ValidationIssue::fieldPath)
                .containsExactlyInAnyOrder(
                        "/weeklyRequiredPatterns/ELBOW_FLEXION",
                        "/weeklyRequiredPatterns/ELBOW_EXTENSION");
    }

    @Test
    void rejectsAThreeDayFullBodyWeekThatOverusesShoulderPressesAndDirectArms() {
        PlanValidationEngine validator = new PlanValidationEngine(professionalThreeDayPolicy());
        PlanGenerationEngine.Candidate candidate = fullBodyCandidate(
                "VISUALLY_UNBALANCED",
                List.of(
                        List.of("SQUAT_A", "VERTICAL_PUSH_A", "HORIZONTAL_PULL_A", "BICEPS_A", "TRICEPS_A"),
                        List.of("HINGE_B", "HORIZONTAL_PUSH_B", "VERTICAL_PULL_B", "BICEPS_B", "TRICEPS_B"),
                        List.of("SQUAT_C", "VERTICAL_PUSH_C", "HORIZONTAL_PULL_C", "BICEPS_C", "TRICEPS_C")));

        assertThat(validator.validate(candidate, 45, professionalFullBodyFacts(candidate)))
                .filteredOn(issue -> issue.reasonCode().startsWith("WEEKLY_MOVEMENT_PATTERN_"))
                .extracting(
                        PlanGenerationEngine.ValidationIssue::reasonCode,
                        PlanGenerationEngine.ValidationIssue::fieldPath)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "WEEKLY_MOVEMENT_PATTERN_UNDERFILLED",
                                "/weeklyMovementPatterns/HORIZONTAL_PUSH"),
                        org.assertj.core.groups.Tuple.tuple(
                                "WEEKLY_MOVEMENT_PATTERN_OVERFILLED",
                                "/weeklyMovementPatterns/VERTICAL_PUSH"),
                        org.assertj.core.groups.Tuple.tuple(
                                "WEEKLY_MOVEMENT_PATTERN_OVERFILLED",
                                "/weeklyMovementPatterns/ELBOW_FLEXION"),
                        org.assertj.core.groups.Tuple.tuple(
                                "WEEKLY_MOVEMENT_PATTERN_OVERFILLED",
                                "/weeklyMovementPatterns/ELBOW_EXTENSION"));
    }

    @Test
    void acceptsAThreeDayFullBodyWeekWithBalancedPressPullLowerAndDirectArmCoverage() {
        PlanValidationEngine validator = new PlanValidationEngine(professionalThreeDayPolicy());
        PlanGenerationEngine.Candidate candidate = fullBodyCandidate(
                "PROFESSIONALLY_BALANCED",
                List.of(
                        List.of("SQUAT_A", "HORIZONTAL_PUSH_A", "HORIZONTAL_PULL_A", "BICEPS_A", "CORE_A"),
                        List.of("HINGE_B", "VERTICAL_PUSH_B", "VERTICAL_PULL_B", "TRICEPS_B", "CORE_B"),
                        List.of("SQUAT_C", "HINGE_C", "HORIZONTAL_PUSH_C", "HORIZONTAL_PULL_C", "CORE_C")));

        assertThat(validator.validate(candidate, 45, professionalFullBodyFacts(candidate)))
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .doesNotContain(
                        "WEEKLY_MOVEMENT_PATTERN_UNDERFILLED",
                        "WEEKLY_MOVEMENT_PATTERN_OVERFILLED",
                        "WEEKLY_DIRECT_ARM_PATTERN_MISSING");
    }

    @Test
    void validatesRecoveryAcrossTheLastAndFirstDayBoundary() {
        PlanValidationEngine validator = new PlanValidationEngine(POLICY);
        PlanGenerationEngine.Candidate candidate = new PlanGenerationEngine.Candidate(
                "CYCLIC",
                "跨周恢复",
                List.of(
                        day("A", "CORE_A"),
                        day("B", "LEGS_B"),
                        day("C", "BACK_C"),
                        day("D", "CORE_D")),
                REFERENCE);
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "CORE_A", new PlanValidationEngine.ExerciseFacts("CORE", Set.of("CORE")),
                "LEGS_B", new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS")),
                "BACK_C", new PlanValidationEngine.ExerciseFacts("PULL", Set.of("BACK")),
                "CORE_D", new PlanValidationEngine.ExerciseFacts("CORE", Set.of("CORE")));

        assertThat(validator.validate(candidate, 30, facts))
                .anySatisfy(issue -> {
                    assertThat(issue.reasonCode()).isEqualTo("RECOVERY_WINDOW_TOO_SHORT");
                    assertThat(issue.fieldPath()).isEqualTo("/days/A/primaryMuscles/CORE");
                });
    }

    @Test
    void evaluationRejectsAPlanWithAnUnsafeRecoveryWindow() {
        PlanGenerationEngine engine = new PlanGenerationEngine(new PlanValidationEngine(POLICY));
        PlanGenerationEngine.Candidate candidate = new PlanGenerationEngine.Candidate(
                "CYCLIC",
                "跨周恢复",
                List.of(
                        day("A", "CORE_A"),
                        day("B", "LEGS_B"),
                        day("C", "BACK_C"),
                        day("D", "CORE_D")),
                REFERENCE);
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "CORE_A", new PlanValidationEngine.ExerciseFacts("CORE", Set.of("CORE")),
                "LEGS_B", new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS")),
                "BACK_C", new PlanValidationEngine.ExerciseFacts("PULL", Set.of("BACK")),
                "CORE_D", new PlanValidationEngine.ExerciseFacts("CORE", Set.of("CORE")));

        PlanGenerationEngine.GenerationResult result = engine.evaluate(candidate, 30, facts, Map.of());

        assertThat(result.status()).isEqualTo(PlanGenerationEngine.GenerationStatus.NO_CANDIDATE);
        assertThat(result.candidate()).isEmpty();
        assertThat(result.issues())
                .anyMatch(issue -> issue.reasonCode().equals("RECOVERY_WINDOW_TOO_SHORT"));
    }

    @Test
    void rejectsAnUnderfilledFortyFiveMinuteCandidate() {
        PlanValidationEngine validator = new PlanValidationEngine(POLICY);
        PlanGenerationEngine.Candidate candidate = new PlanGenerationEngine.Candidate(
                "EDITED",
                "编辑后计划",
                List.of(
                        day("A", "SQUAT", "ROW", "PRESS"),
                        day("B", "HINGE", "CORE", "PULL")),
                REFERENCE);
        Map<String, PlanValidationEngine.ExerciseFacts> facts = fixtureFacts(candidate);

        assertThat(validator.validate(candidate, 45, facts))
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .contains("SESSION_TARGET_UNDERFILLED");
    }

    @Test
    void reportsInsufficientEligibleExercisesInsteadOfRepeatingAnUnsafePattern() {
        PlanGenerationEngine.Template template = threeExerciseTemplate();
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "SQUAT", new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS"), false),
                "ROW", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PULL", Set.of("BACK"), false),
                "PRESS", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PUSH", Set.of("CHEST"), false));

        PlanGenerationEngine.GenerationResult result = engine().generate(
                personalizedInput(
                        template,
                        facts,
                        PlanGenerationEngine.ExperienceLevel.BEGINNER,
                        PlanGenerationEngine.FitnessGoal.GENERAL_FITNESS));

        assertThat(result.status()).isEqualTo(PlanGenerationEngine.GenerationStatus.NO_CANDIDATE);
        assertThat(result.candidate()).isEmpty();
        assertThat(result.issues())
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .contains("INSUFFICIENT_ELIGIBLE_EXERCISES");
    }

    @Test
    void usesADistinctSafeAccessoryWhenTheMovementPatternAlreadyExists() {
        PlanGenerationEngine.Template template = threeExerciseTemplate();
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "SQUAT", new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS"), false),
                "BICEPS_ACCESSORY", new PlanValidationEngine.ExerciseFacts(
                        "ELBOW_FLEXION", Set.of("BICEPS"), false),
                "ROW", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PULL", Set.of("BACK"), false),
                "PRESS", new PlanValidationEngine.ExerciseFacts("HORIZONTAL_PUSH", Set.of("CHEST"), false));

        PlanGenerationEngine.GenerationResult result = engine().generate(
                personalizedInput(
                        template,
                        facts,
                        PlanGenerationEngine.ExperienceLevel.BEGINNER,
                        PlanGenerationEngine.FitnessGoal.GENERAL_FITNESS));

        assertThat(result.status()).isEqualTo(PlanGenerationEngine.GenerationStatus.CANDIDATE_READY);
        assertThat(result.candidate().orElseThrow().days()).allSatisfy(day -> {
            assertThat(day.exercises()).hasSize(4);
            assertThat(day.exercises())
                    .extracting(PlanGenerationEngine.Exercise::exerciseCode)
                    .doesNotHaveDuplicates();
        });
    }

    @Test
    void durationValidationIncludesGeneralAndRampWarmups() {
        PlanValidationEngine validator = new PlanValidationEngine(POLICY);
        assertThat(POLICY.duration().sessionWarmupSeconds(false)).isEqualTo(180);
        assertThat(POLICY.duration().sessionWarmupSeconds(true)).isEqualTo(240);

        List<PlanGenerationEngine.Exercise> exercises = List.of("SQUAT", "ROW", "PRESS", "HINGE").stream()
                .map(code -> new PlanGenerationEngine.Exercise(
                        code, 4, 8, 12, 100, PlanGenerationEngine.WeightStatus.KNOWN))
                .toList();
        PlanGenerationEngine.Candidate candidate = new PlanGenerationEngine.Candidate(
                "WARMUP_BUDGET",
                "完整时长预算",
                List.of(
                        new PlanGenerationEngine.Day("A", "A", exercises),
                        new PlanGenerationEngine.Day("B", "B", exercises)),
                REFERENCE);

        assertThat(validator.validate(candidate, 45, fixtureFacts(candidate)))
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .contains("SESSION_DURATION_EXCEEDED");

        List<PlanGenerationEngine.Exercise> withinBudgetExercises = exercises.stream()
                .map(exercise -> new PlanGenerationEngine.Exercise(
                        exercise.exerciseCode(), 3, 8, 12, 120, PlanGenerationEngine.WeightStatus.KNOWN))
                .toList();
        PlanGenerationEngine.Candidate withinBudget = new PlanGenerationEngine.Candidate(
                "WARMUP_BUDGET_ONCE",
                "热身仅按训练日计入一次",
                List.of(new PlanGenerationEngine.Day("A", "A", withinBudgetExercises)),
                REFERENCE);

        assertThat(validator.validate(withinBudget, 45, fixtureFacts(withinBudget)))
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .doesNotContain("SESSION_DURATION_EXCEEDED");
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

    private static PlanGenerationEngine.Exercise exercise(String code) {
        return new PlanGenerationEngine.Exercise(
                code, 2, 8, 12, 75, PlanGenerationEngine.WeightStatus.KNOWN);
    }

    private static PlanGenerationEngine.Template repeatedTemplate(
            String code, int frequency, PlanGenerationEngine.Exercise exercise) {
        return new PlanGenerationEngine.Template(
                code,
                code,
                frequency,
                IntStream.rangeClosed(1, frequency)
                        .mapToObj(day -> new PlanGenerationEngine.Day(
                                "DAY_" + day, "第" + day + "天", List.of(exercise)))
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
                "1.6.0",
                new PlanRulePolicy.PlanLimits(2, 6, 8, 90),
                new PlanRulePolicy.Prescription(2, 4, 5, 15),
                new PlanRulePolicy.Rest(45, 240),
                new PlanRulePolicy.Duration(45, 75),
                new PlanRulePolicy.Balance(maximumPatternOccurrences, maximumMuscleSets, recoveryHours));
    }

    private static PlanRulePolicy professionalThreeDayPolicy() {
        return new PlanRulePolicy(
                "1.6.0",
                new PlanRulePolicy.PlanLimits(2, 6, 8, 90),
                new PlanRulePolicy.Prescription(2, 4, 5, 15),
                new PlanRulePolicy.Rest(45, 240),
                new PlanRulePolicy.Duration(45, 75),
                new PlanRulePolicy.Balance(
                        1,
                        12,
                        48,
                        List.of(new PlanRulePolicy.WeeklyMovementPatternTargetSet(
                                3,
                                List.of(
                                        new PlanRulePolicy.MovementPatternSessionTarget("SQUAT", 2, 3),
                                        new PlanRulePolicy.MovementPatternSessionTarget("HINGE", 1, 2),
                                        new PlanRulePolicy.MovementPatternSessionTarget("HORIZONTAL_PUSH", 2, 2),
                                        new PlanRulePolicy.MovementPatternSessionTarget("VERTICAL_PUSH", 1, 1),
                                        new PlanRulePolicy.MovementPatternSessionTarget("HORIZONTAL_PULL", 2, 2),
                                        new PlanRulePolicy.MovementPatternSessionTarget("VERTICAL_PULL", 1, 1),
                                        new PlanRulePolicy.MovementPatternSessionTarget("ELBOW_FLEXION", 1, 2),
                                        new PlanRulePolicy.MovementPatternSessionTarget("ELBOW_EXTENSION", 1, 2))))));
    }

    private static PlanGenerationEngine.Candidate fullBodyCandidate(
            String code,
            List<List<String>> exerciseCodesByDay) {
        List<PlanGenerationEngine.Day> days = IntStream.range(0, exerciseCodesByDay.size())
                .mapToObj(index -> new PlanGenerationEngine.Day(
                        "DAY_" + (index + 1),
                        "训练日 " + (index + 1),
                        exerciseCodesByDay.get(index).stream().map(PlanGenerationFixtureTest::exercise).toList()))
                .toList();
        return new PlanGenerationEngine.Candidate(code, code, days, REFERENCE);
    }

    private static Map<String, PlanValidationEngine.ExerciseFacts> professionalFullBodyFacts(
            PlanGenerationEngine.Candidate candidate) {
        return candidate.days().stream()
                .flatMap(day -> day.exercises().stream())
                .map(PlanGenerationEngine.Exercise::exerciseCode)
                .collect(java.util.stream.Collectors.toMap(
                        code -> code,
                        code -> new PlanValidationEngine.ExerciseFacts(
                                professionalPattern(code), Set.of("MUSCLE_" + code))));
    }

    private static String professionalPattern(String exerciseCode) {
        if (exerciseCode.startsWith("SQUAT")) return "SQUAT";
        if (exerciseCode.startsWith("HINGE")) return "HINGE";
        if (exerciseCode.startsWith("HORIZONTAL_PUSH")) return "HORIZONTAL_PUSH";
        if (exerciseCode.startsWith("VERTICAL_PUSH")) return "VERTICAL_PUSH";
        if (exerciseCode.startsWith("HORIZONTAL_PULL")) return "HORIZONTAL_PULL";
        if (exerciseCode.startsWith("VERTICAL_PULL")) return "VERTICAL_PULL";
        if (exerciseCode.startsWith("BICEPS")) return "ELBOW_FLEXION";
        if (exerciseCode.startsWith("TRICEPS")) return "ELBOW_EXTENSION";
        if (exerciseCode.startsWith("CORE")) return "CORE";
        throw new IllegalArgumentException("unknown professional test exercise: " + exerciseCode);
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
