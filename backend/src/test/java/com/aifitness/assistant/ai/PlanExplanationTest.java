package com.aifitness.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.ai.application.AiContentService;
import com.aifitness.assistant.ai.application.AiInputRedactor;
import com.aifitness.assistant.ai.application.AiOrchestrator;
import com.aifitness.assistant.ai.application.AiOutputValidator;
import com.aifitness.assistant.ai.application.AiProvider;
import com.aifitness.assistant.ai.application.DecisionConsistencyGuard;
import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.workout.application.WorkoutHistoryQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanExplanationTest {

    @Test
    void exposesValidatedExplanationWithoutChangingCandidateNumbers() {
        AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());
        PlanCandidateService candidates = mock(PlanCandidateService.class);
        when(candidates.candidate(user, "candidate-1")).thenReturn(candidate());
        AiProvider provider = request -> new AiProvider.Output("fake", "fake-v1", """
                {"summary":"计划结构清晰","highlights":[],"issues":[],"nextActions":[],
                 "explanation":"可先熟悉动作，再按规则记录。","safetyNotice":null}
                """);

        AiContentService.GeneratedContent result = service(candidates, provider, true)
                .explainPlan(user, "candidate-1");

        assertThat(result.status()).isEqualTo(AiContentService.Status.READY);
        assertThat(result.validationStatus()).isEqualTo("VALID");
        assertThat(result.structured()).isPresent();
        assertThat(candidate().plan().days().getFirst().exercises().getFirst().workSets()).isEqualTo(3);
    }

    @Test
    void discardsAPlanExplanationThatInventsANewNumber() {
        AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());
        PlanCandidateService candidates = mock(PlanCandidateService.class);
        when(candidates.candidate(user, "candidate-1")).thenReturn(candidate());
        AiProvider provider = request -> new AiProvider.Output("fake", "fake-v1", """
                {"summary":"计划结构清晰","highlights":[],"issues":[],"nextActions":[],
                 "explanation":"建议改成 99 组。","safetyNotice":null}
                """);

        AiContentService.GeneratedContent result = service(candidates, provider, true)
                .explainPlan(user, "candidate-1");

        assertThat(result.status()).isEqualTo(AiContentService.Status.DEGRADED);
        assertThat(result.validationStatus()).isEqualTo("NUMERIC_CONFLICT");
        assertThat(result.content()).contains("rule-v1");
    }

    private static AiContentService service(PlanCandidateService candidates, AiProvider provider, boolean enabled) {
        ObjectMapper json = new ObjectMapper();
        return new AiContentService(
                candidates,
                mock(WorkoutHistoryQueryService.class),
                mock(RecommendationService.class),
                new AiOrchestrator(enabled, provider, new AiInputRedactor()),
                new AiOutputValidator(json, new DecisionConsistencyGuard()),
                Map.of(
                        "PLAN_EXPLANATION_DEFAULT", "规则版本 {ruleVersion} 已生成计划。",
                        "WORKOUT_SUMMARY_DEFAULT", "完成 {completedWorkSets} 组，容量 {completedVolumeKg}。"));
    }

    private static PlanCandidateService.CandidateEnvelope candidate() {
        PlanDraft plan = new PlanDraft("template", "入门计划", List.of(new PlanDraft.Day(
                "day-a", "训练日", List.of(new PlanDraft.Exercise(
                        "goblet-squat", 3, 8, 12, 90, PlanDraft.WeightStatus.NEEDS_CALIBRATION)))), Map.of());
        return new PlanCandidateService.CandidateEnvelope(
                "candidate-1", plan, new RuleReference("rule-v1", "template-v1", "content-v1"),
                PlanCandidateService.ExplanationStatus.PENDING, "模板", Instant.now().plusSeconds(60));
    }
}
