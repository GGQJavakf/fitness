package com.aifitness.assistant.identity.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface WechatIdentityResolver {

    Optional<ResolvedIdentity> resolveExisting(String oneTimeCode);

    record ResolvedIdentity(AuthenticatedUserId userId, Instant verifiedAt) {
        public ResolvedIdentity {
            Objects.requireNonNull(userId);
            Objects.requireNonNull(verifiedAt);
        }
    }
}
