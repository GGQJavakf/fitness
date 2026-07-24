package com.aifitness.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.ai.application.AiContentService;
import com.aifitness.assistant.ai.application.AiInputRedactor;
import com.aifitness.assistant.ai.application.AiOrchestrator;
import com.aifitness.assistant.ai.application.AiOutputValidator;
import com.aifitness.assistant.ai.application.AiProvider;
import com.aifitness.assistant.ai.application.DecisionConsistencyGuard;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.workout.application.WorkoutHistoryQueryService;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkoutSummaryTest {

    @Test
    void disabledAiReturnsAnAuthoritativeTemplateSummary() {
        UUID sessionId = UUID.randomUUID();
        AiContentService service = service(sessionId, AiProvider.disabled(), false);

        AiContentService.GeneratedContent result = service.summarizeWorkout(
                new AuthenticatedUserId(UUID.randomUUID()), sessionId);

        assertThat(result.status()).isEqualTo(AiContentService.Status.DEGRADED);
        assertThat(result.validationStatus()).isEqualTo("AI_DISABLED");
        assertThat(result.content()).isEqualTo("完成 3 组，容量 1200。规则结论保持权威。");
    }

    @Test
    void rejectsAnInventedProgressionDecisionWhenRulesProducedNone() {
        UUID sessionId = UUID.randomUUID();
        AiProvider provider = request -> new AiProvider.Output("fake", "fake-v1", """
                {"summary":"已完成训练","highlights":[],"issues":[],"nextActions":[],
                 "explanation":"建议 INCREASE。","safetyNotice":null}
                """);

        AiContentService.GeneratedContent result = service(sessionId, provider, true).summarizeWorkout(
                new AuthenticatedUserId(UUID.randomUUID()), sessionId);

        assertThat(result.status()).isEqualTo(AiContentService.Status.DEGRADED);
        assertThat(result.validationStatus()).isEqualTo("DECISION_CONFLICT");
    }

    private static AiContentService service(UUID sessionId, AiProvider provider, boolean enabled) {
        WorkoutHistoryQueryService workouts = mock(WorkoutHistoryQueryService.class);
        when(workouts.summary(any(), org.mockito.ArgumentMatchers.eq(sessionId))).thenReturn(
                new WorkoutHistoryQueryService.Summary(
                        sessionId, WorkoutStatus.COMPLETED, 3, new BigDecimal("1200")));
        RecommendationService recommendations = mock(RecommendationService.class);
        when(recommendations.list(any(), any(Optional.class))).thenReturn(List.of());
        return new AiContentService(
                mock(PlanCandidateService.class),
                workouts,
                recommendations,
                new AiOrchestrator(enabled, provider, new AiInputRedactor()),
                new AiOutputValidator(new ObjectMapper(), new DecisionConsistencyGuard()),
                Map.of(
                        "PLAN_EXPLANATION_DEFAULT", "规则版本 {ruleVersion}。",
                        "WORKOUT_SUMMARY_DEFAULT", "完成 {completedWorkSets} 组，容量 {completedVolumeKg}。规则结论保持权威。"));
    }
}
