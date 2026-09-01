package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.plan.application.CandidateCommitReceiptStore;
import com.aifitness.assistant.plan.application.CandidateCommitService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded-lifetime local adapter; keys and semantic payloads are retained only as SHA-256 digests. */
public final class InMemoryCandidateCommitReceiptStore implements CandidateCommitReceiptStore {
    private final Map<Key, StoredReceipt> receipts = new LinkedHashMap<>();

    @Override
    public synchronized Optional<Receipt> find(UUID userId, String keyDigest, String payloadDigest) {
        StoredReceipt current = receipts.get(new Key(userId, keyDigest));
        if (current == null) {
            return Optional.empty();
        }
        requireSamePayload(current, payloadDigest);
        return current.receipt();
    }

    @Override
    public synchronized Claim claim(UUID userId, String keyDigest, String payloadDigest) {
        Key key = new Key(userId, keyDigest);
        StoredReceipt current = receipts.get(key);
        if (current == null) {
            receipts.put(key, new StoredReceipt(payloadDigest, Optional.empty()));
            return new Claim(Optional.empty());
        }
        requireSamePayload(current, payloadDigest);
        return new Claim(current.receipt());
    }

    @Override
    public synchronized void complete(
            UUID userId,
            String keyDigest,
            String payloadDigest,
            UUID planId,
            int versionNumber,
            UUID versionId) {
        Key key = new Key(userId, keyDigest);
        StoredReceipt current = receipts.get(key);
        if (current == null || current.receipt().isPresent()) {
            throw new IllegalStateException("candidate commit was not claimed");
        }
        requireSamePayload(current, payloadDigest);
        receipts.put(key, new StoredReceipt(
                payloadDigest, Optional.of(new Receipt(planId, versionNumber, versionId))));
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(receipts);
    }

    synchronized void restore(Snapshot snapshot) {
        receipts.clear();
        receipts.putAll(snapshot.receipts());
    }

    private static void requireSamePayload(StoredReceipt current, String payloadDigest) {
        if (!current.payloadDigest().equals(payloadDigest)) {
            throw new CandidateCommitService.IdempotencyKeyReusedException();
        }
    }

    private record Key(UUID userId, String keyDigest) {
        private Key {
            Objects.requireNonNull(userId, "userId must not be null");
            requireDigest(keyDigest);
        }
    }

    private record StoredReceipt(String payloadDigest, Optional<Receipt> receipt) {
        private StoredReceipt {
            requireDigest(payloadDigest);
            receipt = receipt == null ? Optional.empty() : receipt;
        }
    }

    record Snapshot(Map<Key, StoredReceipt> receipts) {
        Snapshot {
            receipts = new LinkedHashMap<>(receipts);
        }
    }

    static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be lowercase SHA-256 hex");
        }
    }
}
