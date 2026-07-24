package com.aifitness.assistant.identity.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.util.UUID;

/** Public identity capability used by other modules without exposing session infrastructure. */
@FunctionalInterface
public interface UserAccessRevocation {

    void revokeAllSessionsAndBlockLogin(AuthenticatedUserId userId, UUID requestId);
}
