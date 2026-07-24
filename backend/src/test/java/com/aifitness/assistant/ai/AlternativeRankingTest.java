package com.aifitness.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.ai.application.AlternativeRankingGuard;
import com.aifitness.assistant.ai.application.AlternativeRankingService;
import com.aifitness.assistant.ai.application.AiInputRedactor;
import com.aifitness.assistant.ai.application.AiOrchestrator;
import com.aifitness.assistant.ai.application.AiProvider;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class AlternativeRankingTest {
    private final AlternativeRankingGuard guard = new AlternativeRankingGuard();

    @Test
    void acceptsOnlyAPermutationOfAllRuleApprovedCandidates() {
        List<String> legal = List.of("split-squat", "step-up", "leg-press");

        assertThat(guard.validatedOrder(legal, List.of("step-up", "leg-press", "split-squat")))
                .containsExactly("step-up", "leg-press", "split-squat");
        assertThat(guard.validatedOrder(legal, List.of("step-up", "invented", "split-squat")))
                .containsExactlyElementsOf(legal);
        assertThat(guard.validatedOrder(legal, List.of("step-up", "step-up", "split-squat")))
                .containsExactlyElementsOf(legal);
    }

    @Test
    void providerCanOnlyRerankTheLegalCandidateSet() {
        List<AlternativeRankingService.Candidate> legal = List.of(
                candidate("split-squat"), candidate("step-up"), candidate("leg-press"));
        AiProvider provider = request -> new AiProvider.Output(
                "fake", "fake-v1", "{\"orderedCandidateCodes\":[\"step-up\",\"invented\",\"split-squat\"]}");
        AlternativeRankingService service = new AlternativeRankingService(
                (user, source) -> legal,
                new AiOrchestrator(true, provider, new AiInputRedactor()),
                guard,
                new ObjectMapper());

        AlternativeRankingService.Ranking result = service.rank(
                new AuthenticatedUserId(UUID.randomUUID()), "back-squat");

        assertThat(result.status()).isEqualTo(AlternativeRankingService.Status.DEGRADED);
        assertThat(result.validationStatus()).isEqualTo("INVALID_RANKING");
        assertThat(result.candidates()).extracting(AlternativeRankingService.Candidate::exerciseCode)
                .containsExactly("split-squat", "step-up", "leg-press");
    }

    private static AlternativeRankingService.Candidate candidate(String code) {
        return new AlternativeRankingService.Candidate(
                code, "SQUAT", "BEGINNER", List.of("DUMBBELL"), List.of("QUADRICEPS"));
    }
}
