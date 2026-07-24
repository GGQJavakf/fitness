package com.aifitness.assistant.identity.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.domain.UserIdentity;
import java.time.Instant;
import java.util.Optional;

public interface IdentityRepository {

    AuthenticatedUserId findOrCreate(
            UserIdentity.Provider provider, byte[] protectedSubject, Instant now);

    default Optional<AuthenticatedUserId> findExisting(
            UserIdentity.Provider provider, byte[] protectedSubject) {
        return Optional.empty();
    }
}
