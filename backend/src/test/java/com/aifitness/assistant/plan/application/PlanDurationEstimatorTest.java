package com.aifitness.assistant.plan.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlanDurationEstimatorTest {

    private final PlanDurationEstimator estimator =
            new PlanDurationEstimator(new PlanRulePolicy.Duration(45, 75));

    @Test
    void doublesOnlyUnilateralWorkTimeAndCountsRestOnceAfterBothSides() {
        PlanDraft.Day day = day(List.of(exercise("DEAD_BUG", 2, 60, true, null, 0)));

        assertThat(estimator.estimateSeconds(day)).isEqualTo(555);
    }

    @Test
    void appliesTheSameUnilateralSemanticsInsideExecutionGroups() {
        PlanDraft.Day day = day(List.of(
                exercise("DEAD_BUG", 2, 60, true, "CORE", 1),
                exercise("GLUTE_BRIDGE_EXERCISE", 2, 60, false, "CORE", 2)));

        assertThat(estimator.estimateSeconds(day)).isEqualTo(585);
    }

    private static PlanDraft.Day day(List<PlanDraft.Exercise> exercises) {
        return new PlanDraft.Day("DAY", "Day", exercises, "MONDAY", "focus", 1, 90, List.of(), List.of());
    }

    private static PlanDraft.Exercise exercise(
            String code,
            int sets,
            int restSeconds,
            boolean perSide,
            String executionGroup,
            int executionOrder) {
        return new PlanDraft.Exercise(
                code, sets, 8, 12, restSeconds, PlanDraft.WeightStatus.BODYWEIGHT, Optional.empty(),
                3, 4, null, perSide, executionGroup, executionOrder, null, List.of());
    }
}
