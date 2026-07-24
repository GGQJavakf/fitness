package com.aifitness.assistant.privacy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.application.ResourceOwnershipGuard;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import com.aifitness.assistant.privacy.infrastructure.InMemoryPrivacyRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrivacyAuthorizationTest {

    @Test
    void foreignAndUnknownDeletionIdsReturnTheSameNotFoundFailure() {
        AuthenticatedUserId owner = new AuthenticatedUserId(UUID.randomUUID());
        AuthenticatedUserId attacker = new AuthenticatedUserId(UUID.randomUUID());
        InMemoryPrivacyRepository repository = new InMemoryPrivacyRepository();
        PrivacyRequestService service = new PrivacyRequestService(
                repository, (user, proof) -> true, (user, action, requestId) -> {}, Clock.systemUTC());
        UUID ownedId = service.requestDeletion(owner, "proof", "DELETE").id();

        assertThatThrownBy(() -> service.getDeletionRequest(attacker, ownedId))
                .isInstanceOf(ResourceOwnershipGuard.ResourceNotFoundException.class)
                .hasMessage("resource not found");
        assertThatThrownBy(() -> service.getDeletionRequest(attacker, UUID.randomUUID()))
                .isInstanceOf(ResourceOwnershipGuard.ResourceNotFoundException.class)
                .hasMessage("resource not found");
    }
}
