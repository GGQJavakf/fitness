package com.aifitness.assistant.privacy.application;

import com.aifitness.assistant.privacy.domain.DeletionRequest;
import java.util.Optional;
import java.util.UUID;

public interface PrivacyRepository {

    Optional<DeletionRequest> findById(UUID id);

    Optional<DeletionRequest> findActiveByUser(UUID userId);

    DeletionRequest save(DeletionRequest request);
}
