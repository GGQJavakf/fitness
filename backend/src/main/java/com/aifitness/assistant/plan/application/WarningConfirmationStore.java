package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Instant;

public interface WarningConfirmationStore {
    String issue(AuthenticatedUserId user, String fingerprint, Instant expiresAt);

    boolean consume(AuthenticatedUserId user, String token, String fingerprint, Instant now);
}
