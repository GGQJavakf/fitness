package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.progression.application.RecommendationRepository;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class InMemoryRecommendationRepository implements RecommendationRepository {
    private final Map<UUID, ProgressionRecommendation> recommendations = new LinkedHashMap<>();
    private final List<OutboxEvent> outboxEvents = new ArrayList<>();

    @Override
    public synchronized ProgressionRecommendation save(ProgressionRecommendation recommendation) {
        if (recommendations.putIfAbsent(recommendation.id(), recommendation) != null) {
            throw new IllegalStateException("recommendation already exists");
        }
        return recommendation;
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
    public synchronized void appendOutbox(UUID aggregateId, String eventType) {
        outboxEvents.add(new OutboxEvent(aggregateId, eventType));
    }

    @Override
    public synchronized <T> T inTransaction(Supplier<T> operation) {
        return operation.get();
    }

    public synchronized List<OutboxEvent> outboxEvents() {
        return List.copyOf(outboxEvents);
    }

    public record OutboxEvent(UUID aggregateId, String type) {}
}
