package com.aifitness.assistant.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedUserId(UUID value) {

    public AuthenticatedUserId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
