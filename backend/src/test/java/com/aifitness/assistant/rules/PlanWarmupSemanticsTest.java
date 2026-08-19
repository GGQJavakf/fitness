package com.aifitness.assistant.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.rules.domain.PlanGenerationEngine;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.rules.domain.PlanValidationEngine;
import com.aifitness.assistant.rules.infrastructure.ClasspathPlanRulePolicyLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanWarmupSemanticsTest {

    @Test
    void durationCountsOneGeneralAndOneRampWarmupAllowancePerTrainingDay() {
        PlanRulePolicy policy = ClasspathPlanRulePolicyLoader.load(new ObjectMapper());
        PlanValidationEngine validator = new PlanValidationEngine(policy);
        List<PlanGenerationEngine.Exercise> exercises = List.of(
                loaded("SQUAT"), loaded("HINGE"), loaded("PUSH"), loaded("PULL"));
        PlanGenerationEngine.Candidate candidate = new PlanGenerationEngine.Candidate(
                "TWO_DAY", "两日训练",
                List.of(
                        new PlanGenerationEngine.Day("DAY_A", "A", exercises),
                        new PlanGenerationEngine.Day("DAY_B", "B", exercises)),
                new RuleReference(policy.version(), "template-v1", "content-v1"));
        Map<String, PlanValidationEngine.ExerciseFacts> facts = Map.of(
                "SQUAT", facts("SQUAT", "LEGS"),
                "HINGE", facts("HINGE", "HAMSTRINGS"),
                "PUSH", facts("HORIZONTAL_PUSH", "CHEST"),
                "PULL", facts("HORIZONTAL_PULL", "BACK"));

        assertThat(validator.validate(candidate, 25, facts))
                .extracting(PlanGenerationEngine.ValidationIssue::reasonCode)
                .doesNotContain("SESSION_DURATION_EXCEEDED");
    }

    private static PlanGenerationEngine.Exercise loaded(String code) {
        return new PlanGenerationEngine.Exercise(
                code, 2, 8, 12, 45, PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION);
    }

    private static PlanValidationEngine.ExerciseFacts facts(String pattern, String muscle) {
        return new PlanValidationEngine.ExerciseFacts(pattern, Set.of(muscle), false);
    }
}
