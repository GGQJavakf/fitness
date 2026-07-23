package com.aifitness.assistant.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserAccount(UUID id, Status status, Instant createdAt) {

    public UserAccount {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public enum Status {
        ACTIVE,
        SUSPENDED,
        DELETED
    }
}
