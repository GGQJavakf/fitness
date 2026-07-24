package com.aifitness.assistant.privacy.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Instant;
import java.util.Objects;

public interface ReauthenticationProofIssuer {

    IssuedProof issue(AuthenticatedUserId userId, String oneTimeCredential);

    record IssuedProof(String proof, Instant issuedAt, Instant expiresAt) {
        public IssuedProof {
            if (proof == null || proof.isBlank()) {
                throw new IllegalArgumentException("proof must not be blank");
            }
            Objects.requireNonNull(issuedAt);
            Objects.requireNonNull(expiresAt);
        }
    }
}
