package com.aifitness.assistant.ai.infrastructure;

import com.aifitness.assistant.ai.application.AiInputRedactor;
import com.aifitness.assistant.ai.application.AiContentService;
import com.aifitness.assistant.ai.application.AlternativeRankingGuard;
import com.aifitness.assistant.ai.application.AlternativeRankingService;
import com.aifitness.assistant.ai.application.AiOrchestrator;
import com.aifitness.assistant.ai.application.AiOutputValidator;
import com.aifitness.assistant.ai.application.AiProvider;
import com.aifitness.assistant.ai.application.DecisionConsistencyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.workout.application.WorkoutHistoryQueryService;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test", "staging-experience"})
public class AiConfiguration {

    @Bean
    AiInputRedactor aiInputRedactor() {
        return new AiInputRedactor();
    }

    @Bean
    @ConditionalOnMissingBean(AiProvider.class)
    AiProvider disabledAiProvider() {
        return AiProvider.disabled();
    }

    @Bean
    AiOrchestrator aiOrchestrator(
            @Value("${fitness.ai.enabled:false}") boolean enabled,
            AiProvider provider,
            AiInputRedactor redactor) {
        return new AiOrchestrator(enabled, provider, redactor);
    }

    @Bean
    DecisionConsistencyGuard decisionConsistencyGuard() {
        return new DecisionConsistencyGuard();
    }

    @Bean
    AiOutputValidator aiOutputValidator(ObjectMapper objectMapper, DecisionConsistencyGuard decisionGuard) {
        return new AiOutputValidator(objectMapper, decisionGuard);
    }

    @Bean("aiReasonTemplates")
    Map<String, String> aiReasonTemplates(ObjectMapper objectMapper) throws IOException {
        try (InputStream input = new ClassPathResource(
                "templates/reason-messages/zh-CN-v1.json").getInputStream()) {
            var messages = objectMapper.readTree(input).path("messages");
            if (!messages.isObject()) throw new IllegalStateException("AI reason templates are invalid");
            Map<String, String> result = new LinkedHashMap<>();
            messages.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
            return Map.copyOf(result);
        }
    }

    @Bean
    AiContentService aiContentService(
            PlanCandidateService candidates,
            WorkoutHistoryQueryService workouts,
            RecommendationService recommendations,
            AiOrchestrator orchestrator,
            AiOutputValidator validator,
            @Qualifier("aiReasonTemplates") Map<String, String> templates) {
        return new AiContentService(candidates, workouts, recommendations, orchestrator, validator, templates);
    }

    @Bean
    AlternativeRankingGuard alternativeRankingGuard() {
        return new AlternativeRankingGuard();
    }

    @Bean
    AlternativeRankingService.LegalAlternativeProvider legalAlternativeProvider(
            ExerciseReplacementService replacements) {
        return (user, sourceCode) -> replacements.candidates(user, sourceCode).stream()
                .map(candidate -> new AlternativeRankingService.Candidate(
                        candidate.code(), candidate.movementPattern(), candidate.difficulty(),
                        candidate.equipment().stream().sorted().toList(),
                        candidate.primaryMuscles().stream().sorted().toList()))
                .toList();
    }

    @Bean
    AlternativeRankingService alternativeRankingService(
            AlternativeRankingService.LegalAlternativeProvider alternatives,
            AiOrchestrator orchestrator,
            AlternativeRankingGuard guard,
            ObjectMapper objectMapper) {
        return new AlternativeRankingService(alternatives, orchestrator, guard, objectMapper);
    }
}
