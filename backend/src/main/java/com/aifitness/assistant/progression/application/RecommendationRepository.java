package com.aifitness.assistant.progression.application;

import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface RecommendationRepository {
    ProgressionRecommendation save(ProgressionRecommendation recommendation);

    Optional<ProgressionRecommendation> findByIdAndUser(UUID id, UUID userId);

    List<ProgressionRecommendation> listByUser(UUID userId, Optional<ProgressionRecommendation.Status> status);

    ProgressionRecommendation updatePending(
            ProgressionRecommendation recommendation, Optional<String> decisionIdempotencyKey);

    void appendOutbox(UUID aggregateId, String eventType);

    <T> T inTransaction(Supplier<T> operation);
}
