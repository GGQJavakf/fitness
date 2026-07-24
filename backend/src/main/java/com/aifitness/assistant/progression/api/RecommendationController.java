package com.aifitness.assistant.progression.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/progression-recommendations")
@Profile({"local", "test", "staging-experience"})
public final class RecommendationController {
    private final RecommendationService recommendations;
    private final Clock clock;

    public RecommendationController(RecommendationService recommendations, Clock clock) {
        this.recommendations = recommendations;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<List<RecommendationData>> list(
            AuthenticatedUserId user,
            @RequestParam Optional<ProgressionRecommendation.Status> status) {
        return response(recommendations.list(user, status).stream().map(RecommendationData::from).toList());
    }

    @PostMapping("/{id}/apply")
    public ApiResponse<RecommendationData> apply(
            AuthenticatedUserId user,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ApplyRequest request) {
        if (request == null || request.expectedVersion() < 1) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        ProgressionRecommendation recommendation = recommendations.get(user, id);
        BigDecimal accepted = request.acceptedWeight() == null
                ? recommendation.recommendedPrescription().weightKg()
                : request.acceptedWeight().kg();
        return response(RecommendationData.from(
                recommendations.apply(user, id, request.expectedVersion(), accepted, idempotencyKey)));
    }

    @PostMapping("/{id}/dismiss")
    public ApiResponse<RecommendationData> dismiss(
            AuthenticatedUserId user, @PathVariable UUID id, @RequestBody DismissRequest request) {
        String reason = request == null || request.reasonCode() == null || request.reasonCode().isBlank()
                ? "USER_DISMISSED" : request.reasonCode();
        return response(RecommendationData.from(recommendations.dismiss(user, id, reason)));
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record ApplyRequest(int expectedVersion, WeightData acceptedWeight) {}
    public record DismissRequest(String reasonCode) {}

    public record WeightData(BigDecimal value, String unit) {
        BigDecimal kg() {
            if (value == null || value.signum() < 0 || !"KG".equals(unit)) {
                throw new IllegalArgumentException("P0 accepted weight must be non-negative KG");
            }
            return value;
        }
    }

    public record RecommendationData(
            UUID id,
            String exerciseCode,
            ProgressionRecommendation.Status status,
            String decision,
            String reasonCode,
            BigDecimal currentWeightKg,
            BigDecimal recommendedWeightKg,
            Optional<BigDecimal> acceptedWeightKg,
            String algorithmVersion,
            Optional<UUID> appliedPlanId,
            Optional<UUID> appliedPlanVersionId,
            Optional<ChangeSummaryData> changeSummary,
            Instant createdAt) {
        static RecommendationData from(ProgressionRecommendation value) {
            return new RecommendationData(
                    value.id(), value.exerciseCode(), value.status(), value.decision().name(), value.reasonCode(),
                    value.currentPrescription().weightKg(), value.recommendedPrescription().weightKg(),
                    value.acceptedWeightKg(), value.algorithmVersion(), value.appliedPlanId(),
                    value.appliedPlanVersionId(), ChangeSummaryData.from(value), value.createdAt());
        }
    }

    public record ChangeSummaryData(String fieldPath, BigDecimal previousWeightKg, BigDecimal appliedWeightKg) {
        static Optional<ChangeSummaryData> from(ProgressionRecommendation value) {
            return value.acceptedWeightKg().map(weight -> new ChangeSummaryData(
                    "exercises[" + value.exerciseCode() + "].targetWeightKg",
                    value.currentPrescription().weightKg(), weight));
        }
    }
}
