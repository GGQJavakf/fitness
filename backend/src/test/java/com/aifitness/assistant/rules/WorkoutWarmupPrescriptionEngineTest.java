package com.aifitness.assistant.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine;
import com.aifitness.assistant.rules.infrastructure.ClasspathPlanRulePolicyLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkoutWarmupPrescriptionEngineTest {

    private final PlanRulePolicy policy = ClasspathPlanRulePolicyLoader.load(new ObjectMapper());
    private final WorkoutWarmupPrescriptionEngine engine = new WorkoutWarmupPrescriptionEngine(policy);

    @Test
    void prescribesGeneralWarmupOnceAndAttachesRampSetsToFirstLoadedCompoundExercise() {
        var result = engine.prescribe(
                List.of(
                        exercise(1, "DUMBBELL_CURL", "ELBOW_FLEXION", "KNOWN", "10", Set.of("DUMBBELL")),
                        exercise(2, "BODYWEIGHT_SQUAT", "SQUAT", "BODYWEIGHT", null, Set.of("BODYWEIGHT")),
                        exercise(3, "DUMBBELL_SQUAT", "SQUAT", "KNOWN", "20", Set.of("DUMBBELL")),
                        exercise(4, "DUMBBELL_PRESS", "HORIZONTAL_PUSH", "KNOWN", "15", Set.of("DUMBBELL"))),
                Map.of("DUMBBELL", levels("2.5", "5", "7.5", "10", "12.5", "15", "20")));

        assertThat(result.schemaVersion()).isEqualTo("workout-warmup-prescription-v1");
        assertThat(result.ruleVersion()).isEqualTo(policy.version());
        assertThat(result.generalWarmup().occurrences()).isEqualTo(1);
        assertThat(result.generalWarmup().durationSeconds()).isEqualTo(180);
        assertThat(result.rampWarmup()).get().satisfies(ramp -> {
            assertThat(ramp.exerciseOrder()).isEqualTo(3);
            assertThat(ramp.status()).isEqualTo(WorkoutWarmupPrescriptionEngine.RampStatus.READY);
            assertThat(ramp.equipmentType()).contains("DUMBBELL");
            assertThat(ramp.sets()).containsExactly(
                    new WorkoutWarmupPrescriptionEngine.RampSet(new BigDecimal("10"), 10),
                    new WorkoutWarmupPrescriptionEngine.RampSet(new BigDecimal("12.5"), 6));
        });
        assertThat(result.countsTowardTrainingVolume()).isFalse();
        assertThat(result.countsTowardProgression()).isFalse();
    }

    @Test
    void returnsExplicitCalibrationInsteadOfInventingWeightsWhenLevelsAreUnavailable() {
        var result = engine.prescribe(
                List.of(exercise(1, "DUMBBELL_SQUAT", "SQUAT", "KNOWN", "20", Set.of("DUMBBELL"))),
                Map.of());

        assertThat(result.rampWarmup()).get().satisfies(ramp -> {
            assertThat(ramp.status()).isEqualTo(WorkoutWarmupPrescriptionEngine.RampStatus.CALIBRATION_REQUIRED);
            assertThat(ramp.sets()).isEmpty();
            assertThat(ramp.calibrationCode()).contains("EQUIPMENT_LEVELS_UNAVAILABLE");
            assertThat(ramp.calibrationMessage()).isPresent().get().asString().contains("校准");
        });
    }

    @Test
    void returnsExplicitCalibrationWhenTheWorkWeightIsUnknown() {
        var result = engine.prescribe(
                List.of(exercise(
                        1, "DUMBBELL_SQUAT", "SQUAT", "NEEDS_CALIBRATION", null, Set.of("DUMBBELL"))),
                Map.of("DUMBBELL", levels("2.5", "5", "7.5", "10")));

        assertThat(result.rampWarmup()).get().satisfies(ramp -> {
            assertThat(ramp.status()).isEqualTo(WorkoutWarmupPrescriptionEngine.RampStatus.CALIBRATION_REQUIRED);
            assertThat(ramp.sets()).isEmpty();
            assertThat(ramp.calibrationCode()).contains("WORK_WEIGHT_NEEDS_CALIBRATION");
        });
    }

    @Test
    void requiresConcreteEquipmentSelectionWhenProfilesContainDuplicateEquipmentTypes() {
        var result = engine.prescribe(
                List.of(exercise(1, "DUMBBELL_SQUAT", "SQUAT", "KNOWN", "20", Set.of("DUMBBELL"))),
                Map.of(),
                Set.of("DUMBBELL"));

        assertThat(result.rampWarmup()).get().satisfies(ramp -> {
            assertThat(ramp.status()).isEqualTo(WorkoutWarmupPrescriptionEngine.RampStatus.CALIBRATION_REQUIRED);
            assertThat(ramp.sets()).isEmpty();
            assertThat(ramp.calibrationCode()).contains("EQUIPMENT_PROFILE_AMBIGUOUS");
        });
    }

    @Test
    void requiresCalibrationWhenAnExerciseUsesMultipleLoadBearingEquipmentTypes() {
        var result = engine.prescribe(
                List.of(exercise(
                        1, "CABLE_MACHINE_ROW", "HORIZONTAL_PULL", "KNOWN", "40", Set.of("CABLE", "MACHINE"))),
                Map.of("CABLE", levels("10", "20", "30")));

        assertThat(result.rampWarmup()).get().satisfies(ramp -> {
            assertThat(ramp.status()).isEqualTo(WorkoutWarmupPrescriptionEngine.RampStatus.CALIBRATION_REQUIRED);
            assertThat(ramp.sets()).isEmpty();
            assertThat(ramp.calibrationCode()).contains("EQUIPMENT_LEVELS_AMBIGUOUS");
        });
    }

    @Test
    void policyCannotEnableWarmupTrainingVolume() {
        assertThatThrownBy(() -> new PlanRulePolicy.Warmup(
                        3,
                        List.of(new BigDecimal("0.5"), new BigDecimal("0.7")),
                        List.of(10, 6),
                        Set.of("SQUAT"),
                        "CALIBRATION_STEPS_ONLY",
                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not count");
    }

    private static WorkoutWarmupPrescriptionEngine.ExerciseInput exercise(
            int order,
            String code,
            String movementPattern,
            String weightStatus,
            String targetWeight,
            Set<String> equipment) {
        return new WorkoutWarmupPrescriptionEngine.ExerciseInput(
                order,
                code,
                movementPattern,
                weightStatus,
                Optional.ofNullable(targetWeight).map(BigDecimal::new),
                equipment);
    }

    private static List<BigDecimal> levels(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }
}
