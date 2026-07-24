package com.aifitness.assistant.privacy.application;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface PrivacyRateLimitPort {

    boolean allow(UUID userId, Action action, Instant now);

    enum Action { EXPORT, EXPORT_READ, DELETE_REQUEST, DELETE_STATUS, DELETE_PROCESS }
}
