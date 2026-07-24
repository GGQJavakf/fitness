package com.aifitness.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.ai.application.AiInputRedactor;
import com.aifitness.assistant.ai.application.AiOrchestrator;
import com.aifitness.assistant.ai.application.AiProvider;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AiDisabledModeTest {

    @Test
    void disabledModeUsesTemplateWithoutCallingAProvider() {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = request -> {
            calls.incrementAndGet();
            return new AiProvider.Output("provider", "model", "{\"summary\":\"must not be used\"}");
        };
        AiOrchestrator orchestrator = new AiOrchestrator(false, provider, new AiInputRedactor());

        AiOrchestrator.Result result = orchestrator.generate(
                AiProvider.Purpose.WORKOUT_SUMMARY,
                Map.of("decision", "KEEP", "wechatOpenId", "must-not-leak"),
                "本次训练已记录，规则建议保持当前重量。");

        assertThat(result.status()).isEqualTo(AiOrchestrator.Status.DEGRADED);
        assertThat(result.content()).isEqualTo("本次训练已记录，规则建议保持当前重量。");
        assertThat(result.validationStatus()).isEqualTo("AI_DISABLED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void enabledModeSendsOnlyRedactedStructuredInput() {
        java.util.concurrent.atomic.AtomicReference<AiProvider.Request> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        AiProvider provider = request -> {
            captured.set(request);
            return new AiProvider.Output("fake", "fake-v1", "{\"summary\":\"ok\"}");
        };
        AiOrchestrator orchestrator = new AiOrchestrator(true, provider, new AiInputRedactor());

        AiOrchestrator.Result result = orchestrator.generate(
                AiProvider.Purpose.PLAN_EXPLANATION,
                Map.of("goal", "GENERAL_FITNESS", "phone", "13800000000"),
                "模板解释");

        assertThat(result.status()).isEqualTo(AiOrchestrator.Status.PENDING_VALIDATION);
        assertThat(captured.get().purpose()).isEqualTo(AiProvider.Purpose.PLAN_EXPLANATION);
        assertThat(captured.get().input()).containsEntry("goal", "GENERAL_FITNESS").doesNotContainKey("phone");
    }
}
