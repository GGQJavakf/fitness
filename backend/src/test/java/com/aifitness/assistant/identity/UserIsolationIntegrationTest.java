package com.aifitness.assistant.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.application.ResourceOwnershipGuard;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserIsolationIntegrationTest {

    private final ResourceOwnershipGuard guard = new ResourceOwnershipGuard();

    @Test
    void randomResourceIdsNeverGrantHorizontalAccess() {
        AuthenticatedUserId owner = new AuthenticatedUserId(UUID.randomUUID());
        AuthenticatedUserId attacker = new AuthenticatedUserId(UUID.randomUUID());
        UUID ownedResourceId = UUID.randomUUID();
        Map<UUID, ResourceOwnershipGuard.OwnedResource<String>> resources = new HashMap<>();
        resources.put(ownedResourceId, new ResourceOwnershipGuard.OwnedResource<>(owner, "private-plan"));

        guard.requireOwnedResource(ownedResourceId, owner, resources::get);

        assertThatThrownBy(() -> guard.requireOwnedResource(ownedResourceId, attacker, resources::get))
                .isInstanceOf(ResourceOwnershipGuard.ResourceNotFoundException.class)
                .hasMessage("resource not found");

        for (int attempt = 0; attempt < 100; attempt++) {
            UUID guessedResourceId = UUID.randomUUID();
            assertThatThrownBy(() -> guard.requireOwnedResource(guessedResourceId, attacker, resources::get))
                    .isInstanceOf(ResourceOwnershipGuard.ResourceNotFoundException.class)
                    .hasMessage("resource not found");
        }
    }
}
