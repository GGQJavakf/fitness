package com.aifitness.assistant.progression.application;

import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface RecommendationRepository {
    SaveResult saveIfAbsent(ProgressionRecommendation recommendation);

    default ProgressionRecommendation save(ProgressionRecommendation recommendation) {
        SaveResult result = saveIfAbsent(recommendation);
        if (!result.created()) throw new IllegalStateException("recommendation already exists");
        return result.recommendation();
    }

    Optional<ProgressionRecommendation> findBySource(
            UUID userId, UUID sourceSessionId, UUID exerciseId, String algorithmVersion);

    Optional<ProgressionRecommendation> findByIdAndUser(UUID id, UUID userId);

    List<ProgressionRecommendation> listByUser(UUID userId, Optional<ProgressionRecommendation.Status> status);

    ProgressionRecommendation updatePending(
            ProgressionRecommendation recommendation, Optional<String> decisionIdempotencyKey);

    void appendOutbox(UUID aggregateId, String eventType);

    <T> T inTransaction(Supplier<T> operation);

    record SaveResult(ProgressionRecommendation recommendation, boolean created) {}
}
