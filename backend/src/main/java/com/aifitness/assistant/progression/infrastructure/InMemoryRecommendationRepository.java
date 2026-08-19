package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.progression.application.RecommendationRepository;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class InMemoryRecommendationRepository implements RecommendationRepository {
    private final Map<UUID, ProgressionRecommendation> recommendations = new LinkedHashMap<>();
    private final List<OutboxEvent> outboxEvents = new ArrayList<>();
    private final Map<DecisionKey, DecisionRecord> decisions = new LinkedHashMap<>();

    @Override
    public synchronized SaveResult saveIfAbsent(ProgressionRecommendation recommendation) {
        Optional<ProgressionRecommendation> existing = findBySource(
                recommendation.userId(), recommendation.sourceSessionId(), recommendation.exerciseId(),
                recommendation.algorithmVersion());
        if (existing.isPresent()) return new SaveResult(existing.orElseThrow(), false);
        recommendations.put(recommendation.id(), recommendation);
        return new SaveResult(recommendation, true);
    }

    @Override
    public synchronized Optional<ProgressionRecommendation> findBySource(
            UUID userId, UUID sourceSessionId, UUID exerciseId, String algorithmVersion) {
        return recommendations.values().stream().filter(value -> value.userId().equals(userId))
                .filter(value -> value.sourceSessionId().equals(sourceSessionId))
                .filter(value -> value.exerciseId().equals(exerciseId))
                .filter(value -> value.algorithmVersion().equals(algorithmVersion)).findFirst();
    }

    @Override
    public synchronized Optional<ProgressionRecommendation> findByIdAndUser(UUID id, UUID userId) {
        return Optional.ofNullable(recommendations.get(id)).filter(value -> value.userId().equals(userId));
    }

    @Override
    public synchronized List<ProgressionRecommendation> listByUser(
            UUID userId, Optional<ProgressionRecommendation.Status> status) {
        return recommendations.values().stream()
                .filter(value -> value.userId().equals(userId))
                .filter(value -> status.map(expected -> value.status() == expected).orElse(true))
                .sorted(java.util.Comparator.comparing(ProgressionRecommendation::createdAt).reversed())
                .toList();
    }

    @Override
    public synchronized Page pageByUser(
            UUID userId,
            Optional<ProgressionRecommendation.Status> status,
            Optional<Cursor> cursor,
            int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Comparator<ProgressionRecommendation> order = Comparator
                .comparing(ProgressionRecommendation::createdAt)
                .thenComparing(ProgressionRecommendation::id, InMemoryRecommendationRepository::compareUuidBytes)
                .reversed();
        List<ProgressionRecommendation> matches = recommendations.values().stream()
                .filter(value -> value.userId().equals(userId))
                .filter(value -> status.map(expected -> value.status() == expected).orElse(true))
                .filter(value -> cursor.map(boundary -> isAfterBoundary(value, boundary)).orElse(true))
                .sorted(order)
                .limit((long) limit + 1)
                .toList();
        boolean hasMore = matches.size() > limit;
        List<ProgressionRecommendation> items = hasMore ? matches.subList(0, limit) : matches;
        Optional<Cursor> next = hasMore
                ? Optional.of(cursor(items.getLast()))
                : Optional.empty();
        return new Page(items, hasMore, next);
    }

    private static boolean isAfterBoundary(ProgressionRecommendation value, Cursor boundary) {
        int time = value.createdAt().compareTo(boundary.createdAt());
        return time < 0 || (time == 0 && compareUuidBytes(value.id(), boundary.id()) < 0);
    }

    private static Cursor cursor(ProgressionRecommendation value) {
        return new Cursor(value.createdAt(), value.id());
    }

    private static int compareUuidBytes(UUID left, UUID right) {
        return Arrays.compareUnsigned(bytes(left), bytes(right));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    @Override
    public synchronized ProgressionRecommendation updatePending(
            ProgressionRecommendation recommendation, Optional<String> decisionIdempotencyKey) {
        ProgressionRecommendation current = recommendations.get(recommendation.id());
        if (current == null || current.status() != ProgressionRecommendation.Status.PENDING) {
            throw new IllegalStateException("recommendation is already decided");
        }
        recommendations.put(recommendation.id(), recommendation);
        return recommendation;
    }

    @Override
    public synchronized DecisionClaim claimDecision(
            UUID userId, String operation, String idempotencyKey, String payloadFingerprint) {
        DecisionKey key = new DecisionKey(userId, operation, idempotencyKey);
        DecisionRecord current = decisions.get(key);
        if (current == null) {
            decisions.put(key, new DecisionRecord(payloadFingerprint, null));
            return new DecisionClaim(Optional.empty());
        }
        if (!current.payloadFingerprint().equals(payloadFingerprint)) {
            throw new com.aifitness.assistant.progression.application.RecommendationService
                    .IdempotencyKeyReusedException();
        }
        return new DecisionClaim(Optional.ofNullable(current.recommendationId())
                .flatMap(id -> findByIdAndUser(id, userId)));
    }

    @Override
    public synchronized void completeDecision(
            UUID userId, String operation, String idempotencyKey, String payloadFingerprint, UUID recommendationId) {
        DecisionKey key = new DecisionKey(userId, operation, idempotencyKey);
        DecisionRecord current = decisions.get(key);
        if (current == null || !current.payloadFingerprint().equals(payloadFingerprint)) {
            throw new IllegalStateException("progression decision was not claimed");
        }
        decisions.put(key, new DecisionRecord(payloadFingerprint, recommendationId));
    }

    @Override
    public synchronized void appendOutbox(UUID aggregateId, String eventType) {
        outboxEvents.add(new OutboxEvent(aggregateId, eventType));
    }

    @Override
    public synchronized <T> T inTransaction(Supplier<T> operation) {
        Map<UUID, ProgressionRecommendation> recommendationSnapshot = new LinkedHashMap<>(recommendations);
        List<OutboxEvent> outboxSnapshot = new ArrayList<>(outboxEvents);
        Map<DecisionKey, DecisionRecord> decisionSnapshot = new LinkedHashMap<>(decisions);
        try {
            return operation.get();
        } catch (RuntimeException exception) {
            recommendations.clear();
            recommendations.putAll(recommendationSnapshot);
            outboxEvents.clear();
            outboxEvents.addAll(outboxSnapshot);
            decisions.clear();
            decisions.putAll(decisionSnapshot);
            throw exception;
        }
    }

    public synchronized List<OutboxEvent> outboxEvents() {
        return List.copyOf(outboxEvents);
    }

    public record OutboxEvent(UUID aggregateId, String type) {}
    private record DecisionKey(UUID userId, String operation, String idempotencyKey) {}
    private record DecisionRecord(String payloadFingerprint, UUID recommendationId) {}
}
