package com.aifitness.assistant.rules.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClasspathPlanRulePolicyLoaderTest {

    @Test
    void acceptsRampWarmupBudgetWithinTheConfiguredMaximum() {
        assertThatCode(() -> ClasspathPlanRulePolicyLoader.validateRampWarmupSets(2, 3))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRampWarmupBudgetAboveTheConfiguredMaximum() {
        assertThatThrownBy(() -> ClasspathPlanRulePolicyLoader.validateRampWarmupSets(4, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("duration.rampWarmupSetsPerSession must not exceed warmup.maximumRampSets");
    }
}
