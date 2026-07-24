package com.aifitness.assistant.rules;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.rules.domain.PlanGenerationEngine;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.rules.domain.PlanValidationEngine;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LockedFieldPreservationPropertyTest {

    private static final String PATH_PREFIX = "/days/DAY_A/exercises/SQUAT/";
    private static final PlanRulePolicy POLICY = new PlanRulePolicy(
            "1.1.0", new PlanRulePolicy.PlanLimits(2, 5, 8, 90),
            new PlanRulePolicy.Prescription(2, 4, 5, 15), new PlanRulePolicy.Rest(45, 240),
            new PlanRulePolicy.Duration(45, 75), new PlanRulePolicy.Balance(1, 12, 48));

    @ParameterizedTest
    @ValueSource(strings = {"workSets", "repMin", "repMax", "restSeconds"})
    void everyValidUserLockedNumberIsMergedWithoutSilentOverwrite(String field) {
        PlanGenerationEngine engine = engine();
        int value = switch (field) {
            case "workSets" -> 4;
            case "repMin" -> 7;
            case "repMax" -> 13;
            case "restSeconds" -> 180;
            default -> throw new IllegalArgumentException(field);
        };

        PlanGenerationEngine.GenerationResult result = engine.generate(input(Map.of(PATH_PREFIX + field, value)));

        assertThat(result.candidate()).isPresent();
        PlanGenerationEngine.Exercise exercise = result.candidate().orElseThrow()
                .days().getFirst().exercises().getFirst();
        int actual = switch (field) {
            case "workSets" -> exercise.workSets();
            case "repMin" -> exercise.repMin();
            case "repMax" -> exercise.repMax();
            case "restSeconds" -> exercise.restSeconds();
            default -> throw new IllegalArgumentException(field);
        };
        assertThat(actual).isEqualTo(value);
        assertThat(result.lockedFieldOutcomes())
                .containsEntry(PATH_PREFIX + field, PlanGenerationEngine.LockStatus.USER_LOCKED);
    }

    @Test
    void invalidUserLockedNumberIsReportedAndNeverReplacedByTemplateValue() {
        PlanGenerationEngine engine = engine();

        String path = PATH_PREFIX + "restSeconds";
        PlanGenerationEngine.GenerationResult result = engine.generate(input(Map.of(path, 300)));

        assertThat(result.status()).isEqualTo(PlanGenerationEngine.GenerationStatus.NO_CANDIDATE);
        assertThat(result.candidate()).isEmpty();
        assertThat(result.issues())
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .containsExactly("REST_OUT_OF_RANGE");
        assertThat(result.lockedFieldOutcomes()).containsEntry(path, PlanGenerationEngine.LockStatus.USER_LOCKED);
    }

    @Test
    void unknownLockPathFailsExplicitly() {
        String path = "/days/DAY_A/exercises/UNKNOWN/restSeconds";
        PlanGenerationEngine.GenerationResult result = engine().generate(input(Map.of(path, 120)));

        assertThat(result.candidate()).isEmpty();
        assertThat(result.issues()).extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .containsExactly("LOCKED_FIELD_PATH_NOT_FOUND");
        assertThat(result.lockedFieldOutcomes()).containsEntry(path, PlanGenerationEngine.LockStatus.USER_LOCKED);
    }

    private static PlanGenerationEngine engine() {
        return new PlanGenerationEngine(new PlanValidationEngine(POLICY));
    }

    private static PlanGenerationEngine.GenerationInput input(Map<String, Integer> lockedNumbers) {
        PlanGenerationEngine.Exercise exercise = new PlanGenerationEngine.Exercise(
                "SQUAT", 3, 8, 12, 120, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
        PlanGenerationEngine.Day first = new PlanGenerationEngine.Day("DAY_A", "A", List.of(exercise));
        PlanGenerationEngine.Day second = new PlanGenerationEngine.Day("DAY_B", "B", List.of(exercise));
        PlanGenerationEngine.Day third = new PlanGenerationEngine.Day("DAY_C", "C", List.of(exercise));
        PlanGenerationEngine.Template template =
                new PlanGenerationEngine.Template("THREE_DAY_TEST", "测试模板", 3, List.of(first, second, third));
        return new PlanGenerationEngine.GenerationInput(
                new RuleReference("1.1.0", "1.0.0", "1.0.0"),
                3,
                60,
                List.of(template),
                Map.of("SQUAT", new PlanValidationEngine.ExerciseFacts("SQUAT", Set.of("LEGS"))),
                lockedNumbers);
    }
}
