package com.aifitness.assistant.progression.application;

import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import java.time.Instant;
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

    Page pageByUser(
            UUID userId,
            Optional<ProgressionRecommendation.Status> status,
            Optional<Cursor> cursor,
            int limit);

    ProgressionRecommendation updatePending(
            ProgressionRecommendation recommendation, Optional<String> decisionIdempotencyKey);

    DecisionClaim claimDecision(UUID userId, String operation, String idempotencyKey, String payloadFingerprint);

    void completeDecision(
            UUID userId, String operation, String idempotencyKey, String payloadFingerprint, UUID recommendationId);

    void appendOutbox(UUID aggregateId, String eventType);

    <T> T inTransaction(Supplier<T> operation);

    record SaveResult(ProgressionRecommendation recommendation, boolean created) {}

    record DecisionClaim(Optional<ProgressionRecommendation> replay) {
        public DecisionClaim {
            replay = replay == null ? Optional.empty() : replay;
        }
    }

    record Cursor(Instant createdAt, UUID id) {
        public Cursor {
            java.util.Objects.requireNonNull(createdAt, "createdAt must not be null");
            java.util.Objects.requireNonNull(id, "id must not be null");
        }
    }

    record Page(
            List<ProgressionRecommendation> items,
            boolean hasMore,
            Optional<Cursor> nextCursor) {
        public Page {
            items = List.copyOf(items);
            nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
            if (hasMore != nextCursor.isPresent()) {
                throw new IllegalArgumentException("next cursor must match hasMore");
            }
        }
    }
}
