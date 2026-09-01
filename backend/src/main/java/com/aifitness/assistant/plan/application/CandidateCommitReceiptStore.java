package com.aifitness.assistant.plan.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stores only digests and the immutable version pointer needed for response-loss replay. */
public interface CandidateCommitReceiptStore {
    Optional<Receipt> find(UUID userId, String keyDigest, String payloadDigest);

    Claim claim(UUID userId, String keyDigest, String payloadDigest);

    void complete(
            UUID userId,
            String keyDigest,
            String payloadDigest,
            UUID planId,
            int versionNumber,
            UUID versionId);

    record Claim(Optional<Receipt> replay) {
        public Claim {
            replay = replay == null ? Optional.empty() : replay;
        }
    }

    record Receipt(UUID planId, int versionNumber, UUID versionId) {
        public Receipt {
            Objects.requireNonNull(planId, "planId must not be null");
            Objects.requireNonNull(versionId, "versionId must not be null");
            if (versionNumber < 1) {
                throw new IllegalArgumentException("versionNumber must be positive");
            }
        }
    }
}
