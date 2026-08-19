package com.aifitness.assistant.progression.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.application.RecommendationRepository;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<ApiResponse<List<RecommendationData>>> list(
            AuthenticatedUserId user,
            @RequestParam Optional<ProgressionRecommendation.Status> status,
            @RequestParam Optional<String> cursor,
            @RequestParam(defaultValue = "20") int limit) {
        RecommendationRepository.Page page = recommendations.page(
                user, status, cursor.map(RecommendationController::decodeCursor), limit);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header("X-Has-More", Boolean.toString(page.hasMore()));
        page.nextCursor().map(RecommendationController::encodeCursor)
                .ifPresent(value -> builder.header("X-Next-Cursor", value));
        return builder.body(response(page.items().stream().map(RecommendationData::from).toList()));
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

    private static String encodeCursor(RecommendationRepository.Cursor cursor) {
        String value = cursor.createdAt() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static RecommendationRepository.Cursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 256) {
            throw new IllegalArgumentException("cursor is invalid");
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException("cursor is invalid");
            RecommendationRepository.Cursor cursor = new RecommendationRepository.Cursor(
                    Instant.parse(parts[0]), UUID.fromString(parts[1]));
            if (!encodeCursor(cursor).equals(encoded)) {
                throw new IllegalArgumentException("cursor is not canonical");
            }
            return cursor;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor is invalid");
        }
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
