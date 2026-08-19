package com.aifitness.assistant.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.infrastructure.InMemoryPlanRepository;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.domain.ProgressionDecision;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import com.aifitness.assistant.progression.infrastructure.InMemoryRecommendationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationLifecycleTest {
    private static final AuthenticatedUserId USER = new AuthenticatedUserId(UUID.fromString(
            "10000000-0000-0000-0000-000000000001"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void savingRecommendationDoesNotApplyItAndRetainsReplayEvidence() {
        InMemoryRecommendationRepository repository = new InMemoryRecommendationRepository();
        RecommendationService service = service(repository);

        ProgressionRecommendation saved = service.save(USER, UUID.randomUUID(), "GOBLET_SQUAT",
                UUID.randomUUID(), increaseDecision(), "{\"schemaVersion\":\"1.0.0\",\"facts\":[\"set-1\"]}");

        assertThat(saved.status()).isEqualTo(ProgressionRecommendation.Status.PENDING);
        assertThat(saved.appliedPlanId()).isEmpty();
        assertThat(saved.reasonCode()).isEqualTo("ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR");
        assertThat(saved.algorithmVersion()).isEqualTo("double-progression-v1");
        assertThat(saved.inputSnapshotJson()).contains("set-1");
        assertThat(repository.outboxEvents()).extracting(InMemoryRecommendationRepository.OutboxEvent::type)
                .containsExactly("PROGRESSION_RECOMMENDATION_CREATED");
    }

    @Test
    void repeatedGenerationForTheSameSourceIsIdempotent() {
        InMemoryRecommendationRepository repository = new InMemoryRecommendationRepository();
        RecommendationService service = service(repository);
        UUID exerciseId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        ProgressionRecommendation first = service.save(USER, exerciseId, "GOBLET_SQUAT", sessionId,
                increaseDecision(), "{\"generation\":1}");
        ProgressionRecommendation replay = service.save(USER, exerciseId, "GOBLET_SQUAT", sessionId,
                increaseDecision(), "{\"generation\":2}");

        assertThat(replay).isEqualTo(first);
        assertThat(repository.outboxEvents()).hasSize(1);
    }

    @Test
    void dismissalIsTerminalAndAnotherUserCannotObserveTheRecommendation() {
        InMemoryRecommendationRepository repository = new InMemoryRecommendationRepository();
        RecommendationService service = service(repository);
        ProgressionRecommendation saved = service.save(USER, UUID.randomUUID(), "GOBLET_SQUAT",
                UUID.randomUUID(), increaseDecision(), "{\"schemaVersion\":\"1.0.0\"}");

        ProgressionRecommendation dismissed = service.dismiss(USER, saved.id(), "NOT_NOW");

        assertThat(dismissed.status()).isEqualTo(ProgressionRecommendation.Status.DISMISSED);
        assertThat(service.list(USER, Optional.empty())).containsExactly(dismissed);
        assertThat(service.list(new AuthenticatedUserId(UUID.randomUUID()), Optional.empty())).isEmpty();
        assertThatThrownBy(() -> service.dismiss(USER, saved.id(), "AGAIN"))
                .isInstanceOf(RecommendationService.RecommendationAlreadyDecidedException.class);
    }

    @Test
    void explanationOnlyRecommendationCannotBeAppliedAsAWeightChange() {
        InMemoryRecommendationRepository repository = new InMemoryRecommendationRepository();
        RecommendationService service = service(repository);
        ProgressionDecision.Prescription prescription = new ProgressionDecision.Prescription(
                new BigDecimal("40"), 8, 12);
        ProgressionDecision keep = new ProgressionDecision(
                ProgressionDecision.Decision.KEEP,
                ProgressionDecision.ReasonCode.WITHIN_TARGET_RANGE,
                ProgressionDecision.Application.NO_CHANGE,
                prescription,
                prescription,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                "double-progression-v1");
        ProgressionRecommendation saved = service.save(
                USER, UUID.randomUUID(), "GOBLET_SQUAT", UUID.randomUUID(), keep,
                "{\"schemaVersion\":\"1.0.0\"}");

        assertThatThrownBy(() -> service.apply(USER, saved.id(), 1, new BigDecimal("40"), "apply-keep"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not contain an applicable weight change");
        assertThat(service.get(USER, saved.id()).status()).isEqualTo(ProgressionRecommendation.Status.PENDING);
    }

    private static RecommendationService service(InMemoryRecommendationRepository repository) {
        PlanVersionService.PlanPolicy policy = new PlanVersionService.PlanPolicy() {
            @Override
            public PlanVersionService.CandidatePlan candidate(AuthenticatedUserId user, String candidateId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<PlanVersionService.ValidationIssue> validate(
                    AuthenticatedUserId user,
                    com.aifitness.assistant.plan.domain.PlanDraft plan,
                    com.aifitness.assistant.common.domain.RuleReference reference) {
                return List.of();
            }
        };
        return new RecommendationService(repository,
                new PlanVersionService(new InMemoryPlanRepository(), policy, CLOCK), CLOCK, UUID::randomUUID);
    }

    static ProgressionDecision increaseDecision() {
        ProgressionDecision.Prescription current = new ProgressionDecision.Prescription(
                new BigDecimal("40"), 8, 12);
        ProgressionDecision.Prescription recommended = new ProgressionDecision.Prescription(
                new BigDecimal("42.5"), 8, 12);
        return new ProgressionDecision(
                ProgressionDecision.Decision.INCREASE,
                ProgressionDecision.ReasonCode.ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR,
                ProgressionDecision.Application.RECOMMENDATION_PENDING,
                current,
                recommended,
                Optional.of(new BigDecimal("42.5")),
                Optional.of(new BigDecimal("42.5")),
                Optional.of("NEXT_AVAILABLE_LEVEL"),
                List.of(new BigDecimal("40"), new BigDecimal("42.5"), new BigDecimal("45")),
                "double-progression-v1");
    }
}
