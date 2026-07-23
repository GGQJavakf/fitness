package com.aifitness.assistant.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.common.domain.Weight;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommonContractTypesTest {

    @Test
    void createsP0KilogramWeightWithoutFloatingPointLoss() {
        Weight weight = Weight.p0Kilograms(new BigDecimal("62.50"), "barbell-main");

        assertThat(weight.value()).isEqualByComparingTo("62.50");
        assertThat(weight.unit().name()).isEqualTo("KG");
        assertThatIllegalArgumentException().isThrownBy(() -> Weight.p0Kilograms(new BigDecimal("1.001"), null));
        assertThatIllegalArgumentException().isThrownBy(() -> Weight.p0Kilograms(new BigDecimal("-1"), null));
    }

    @Test
    void requiresCompleteRuleReferencesForRecalculation() {
        RuleReference reference = new RuleReference("rule-1", "template-1", "content-1");

        assertThat(reference.ruleVersion()).isEqualTo("rule-1");
        assertThatIllegalArgumentException().isThrownBy(() -> new RuleReference(" ", "template-1", "content-1"));
    }

    @Test
    void keepsSuccessAndErrorShapesMutuallyExplicit() {
        ResponseMeta meta = new ResponseMeta("01JTESTREQUEST", Instant.parse("2026-07-23T10:30:00Z"));
        ApiResponse<String> response = new ApiResponse<>("ok", meta);
        ApiError error = new ApiError(
                ErrorCode.VERSION_CONFLICT, "资源版本冲突", List.of(), Map.of("currentVersion", 2), false);

        assertThat(response.data()).isEqualTo("ok");
        assertThat(error.code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
    }
}
