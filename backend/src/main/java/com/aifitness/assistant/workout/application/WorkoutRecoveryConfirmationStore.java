package com.aifitness.assistant.workout.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface WorkoutRecoveryConfirmationStore {
    String issue(Binding binding, Instant issuedAt, Instant expiresAt);

    boolean consume(Binding binding, String token, Instant now);

    record Binding(
            UUID userId,
            UUID planId,
            int planVersionNumber,
            String trainingDayCode,
            String clientSessionKey,
            String assessmentFingerprint) {
        public Binding {
            Objects.requireNonNull(userId, "user id must not be null");
            Objects.requireNonNull(planId, "plan id must not be null");
            if (planVersionNumber < 1) throw new IllegalArgumentException("plan version must be positive");
            trainingDayCode = required(trainingDayCode, "training day code", 128);
            clientSessionKey = required(clientSessionKey, "client session key", 128);
            assessmentFingerprint = required(assessmentFingerprint, "assessment fingerprint", 64);
            if (!assessmentFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("assessment fingerprint must be a SHA-256 hex digest");
            }
        }

        private static String required(String value, String name, int maximumLength) {
            if (value == null || value.isBlank() || value.length() > maximumLength) {
                throw new IllegalArgumentException(name + " is invalid");
            }
            return value;
        }
    }
}
