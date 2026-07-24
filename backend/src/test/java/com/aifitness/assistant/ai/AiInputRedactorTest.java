package com.aifitness.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.ai.application.AiInputRedactor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiInputRedactorTest {

    private final AiInputRedactor redactor = new AiInputRedactor();

    @Test
    void keepsOnlyPurposeSpecificStructuredFieldsAndDropsSensitiveInputAtEveryLevel() {
        Map<String, Object> redacted = redactor.redact(Map.of(
                "interactionId", "anonymous-interaction",
                "experienceLevel", "BEGINNER",
                "goal", "GENERAL_FITNESS",
                "equipmentCategories", List.of("DUMBBELL"),
                "workoutFacts", Map.of(
                        "decision", "KEEP",
                        "reasonCodes", List.of("TARGET_REPS_PARTIAL"),
                        "phone", "13800000000",
                        "healthDescription", "free text that must never leave the service"),
                "wechatOpenId", "wx-secret-id",
                "credential", "provider-key",
                "age", 31,
                "gender", "irrelevant"));

        assertThat(redacted).containsEntry("interactionId", "anonymous-interaction")
                .containsEntry("experienceLevel", "BEGINNER")
                .containsEntry("goal", "GENERAL_FITNESS")
                .containsKey("equipmentCategories")
                .containsKey("workoutFacts")
                .doesNotContainKeys("wechatOpenId", "credential", "age", "gender");
        Map<?, ?> workoutFacts = (Map<?, ?>) redacted.get("workoutFacts");
        assertThat(workoutFacts.get("decision")).isEqualTo("KEEP");
        assertThat(workoutFacts.containsKey("reasonCodes")).isTrue();
        assertThat(workoutFacts.containsKey("phone")).isFalse();
        assertThat(workoutFacts.containsKey("healthDescription")).isFalse();
    }

    @Test
    void returnsAnImmutableDetachedRequest() {
        List<String> equipment = new java.util.ArrayList<>(List.of("BARBELL"));
        Map<String, Object> redacted = redactor.redact(Map.of("equipmentCategories", equipment));
        equipment.add("CABLE");

        assertThat(redacted.get("equipmentCategories")).isEqualTo(List.of("BARBELL"));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> redacted.put("goal", "MUSCLE_GAIN"));
    }
}
