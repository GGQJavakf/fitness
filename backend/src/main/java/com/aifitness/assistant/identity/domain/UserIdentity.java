package com.aifitness.assistant.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserIdentity(
        UUID id,
        AuthenticatedUserId userId,
        Provider provider,
        byte[] protectedSubject,
        Status status,
        Instant createdAt) {

    public UserIdentity {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        protectedSubject = Objects.requireNonNull(protectedSubject, "protectedSubject must not be null").clone();
        if (protectedSubject.length == 0) {
            throw new IllegalArgumentException("protectedSubject must not be empty");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    @Override
    public byte[] protectedSubject() {
        return protectedSubject.clone();
    }

    public enum Provider {
        WECHAT_MINI_PROGRAM
    }

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
