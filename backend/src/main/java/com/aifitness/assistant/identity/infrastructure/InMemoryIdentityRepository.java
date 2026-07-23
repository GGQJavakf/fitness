package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.IdentityRepository;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.domain.UserAccount;
import com.aifitness.assistant.identity.domain.UserIdentity;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class InMemoryIdentityRepository implements IdentityRepository {

    private final Map<String, UserIdentity> identities = new HashMap<>();
    private final Map<AuthenticatedUserId, UserAccount> accounts = new HashMap<>();

    @Override
    public synchronized AuthenticatedUserId findOrCreate(
            UserIdentity.Provider provider, byte[] protectedSubject, Instant now) {
        byte[] subjectCopy = protectedSubject.clone();
        String key = provider.name() + ':' + Base64.getEncoder().encodeToString(subjectCopy);
        UserIdentity existing = identities.get(key);
        if (existing != null) {
            return existing.userId();
        }

        AuthenticatedUserId userId = new AuthenticatedUserId(UUID.randomUUID());
        accounts.put(userId, new UserAccount(userId.value(), UserAccount.Status.ACTIVE, now));
        identities.put(key, new UserIdentity(
                UUID.randomUUID(),
                userId,
                provider,
                subjectCopy,
                UserIdentity.Status.ACTIVE,
                now));
        return userId;
    }
}
