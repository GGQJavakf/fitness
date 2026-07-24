package com.aifitness.assistant.progression.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import com.aifitness.assistant.progression.domain.ProgressionDecision;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class RecommendationService {
    private final RecommendationRepository recommendations;
    private final PlanVersionService plans;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public RecommendationService(
            RecommendationRepository recommendations,
            PlanVersionService plans,
            Clock clock,
            Supplier<UUID> ids) {
        this.recommendations = Objects.requireNonNull(recommendations, "recommendations must not be null");
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    public ProgressionRecommendation save(
            AuthenticatedUserId user,
            UUID exerciseId,
            String exerciseCode,
            UUID sourceSessionId,
            ProgressionDecision decision,
            String inputSnapshotJson) {
        requireUser(user);
        Objects.requireNonNull(decision, "decision must not be null");
        ProgressionRecommendation recommendation = new ProgressionRecommendation(
                ids.get(), user.value(), exerciseId, exerciseCode, sourceSessionId, decision.decision(),
                decision.currentPrescription(), decision.recommendedPrescription(), decision.reasonCode().name(),
                inputSnapshotJson, decision.algorithmVersion(), roundingEvidence(decision),
                ProgressionRecommendation.Status.PENDING,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), clock.instant());
        return recommendations.inTransaction(() -> {
            ProgressionRecommendation saved = recommendations.save(recommendation);
            recommendations.appendOutbox(saved.id(), "PROGRESSION_RECOMMENDATION_CREATED");
            return saved;
        });
    }

    public ProgressionRecommendation get(AuthenticatedUserId user, UUID id) {
        requireUser(user);
        return recommendations.findByIdAndUser(id, user.value())
                .orElseThrow(RecommendationNotFoundException::new);
    }

    public List<ProgressionRecommendation> list(
            AuthenticatedUserId user, Optional<ProgressionRecommendation.Status> status) {
        requireUser(user);
        return recommendations.listByUser(user.value(), status == null ? Optional.empty() : status);
    }

    public ProgressionRecommendation apply(
            AuthenticatedUserId user,
            UUID id,
            int expectedVersion,
            BigDecimal acceptedWeightKg,
            String idempotencyKey) {
        requireUser(user);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        return recommendations.inTransaction(() -> {
            ProgressionRecommendation recommendation = pending(user, id);
            BigDecimal validatedWeight = recommendation.validateAcceptedWeight(acceptedWeightKg);
            try {
                TrainingPlanVersion version = plans.applyProgression(
                        user, recommendation.exerciseCode(), expectedVersion, validatedWeight);
                ProgressionRecommendation applied = recommendation.apply(
                        validatedWeight, version.planId(), version.id());
                ProgressionRecommendation saved = recommendations.updatePending(applied, Optional.of(idempotencyKey));
                recommendations.appendOutbox(saved.id(), "PROGRESSION_RECOMMENDATION_" + saved.status().name());
                return saved;
            } catch (PlanVersionService.LockedProgressionFieldException exception) {
                throw new LockedWeightException();
            }
        });
    }

    public ProgressionRecommendation dismiss(AuthenticatedUserId user, UUID id, String reasonCode) {
        requireUser(user);
        return recommendations.inTransaction(() -> {
            ProgressionRecommendation dismissed = pending(user, id).dismiss(reasonCode);
            ProgressionRecommendation saved = recommendations.updatePending(dismissed, Optional.empty());
            recommendations.appendOutbox(saved.id(), "PROGRESSION_RECOMMENDATION_DISMISSED");
            return saved;
        });
    }

    private ProgressionRecommendation pending(AuthenticatedUserId user, UUID id) {
        ProgressionRecommendation recommendation = get(user, id);
        if (recommendation.status() != ProgressionRecommendation.Status.PENDING) {
            throw new RecommendationAlreadyDecidedException();
        }
        return recommendation;
    }

    private static void requireUser(AuthenticatedUserId user) {
        Objects.requireNonNull(user, "authenticated user must not be null");
    }

    private static Optional<ProgressionRecommendation.RoundingEvidence> roundingEvidence(
            ProgressionDecision decision) {
        if (decision.rawRecommendedWeight().isEmpty()) return Optional.empty();
        return Optional.of(new ProgressionRecommendation.RoundingEvidence(
                decision.rawRecommendedWeight().orElseThrow(), decision.roundedWeight().orElseThrow(),
                decision.roundingRule().orElseThrow(), decision.availableEquipmentSteps()));
    }

    public static final class RecommendationNotFoundException extends RuntimeException {}
    public static final class RecommendationAlreadyDecidedException extends RuntimeException {}
    public static final class LockedWeightException extends RuntimeException {}
}
