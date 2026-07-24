package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyRepository;
import com.aifitness.assistant.privacy.domain.DeletionRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryPrivacyRepository implements PrivacyRepository {

    private final Map<UUID, DeletionRequest> requests = new HashMap<>();

    @Override
    public synchronized Optional<DeletionRequest> findById(UUID id) {
        return Optional.ofNullable(requests.get(id));
    }

    @Override
    public synchronized Optional<DeletionRequest> findActiveByUser(UUID userId) {
        return requests.values().stream()
                .filter(request -> request.userId().equals(userId) && request.active())
                .findFirst();
    }

    @Override
    public synchronized DeletionRequest save(DeletionRequest request) {
        requests.put(request.id(), request);
        return request;
    }

    public synchronized int count() {
        return requests.size();
    }
}
