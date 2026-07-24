package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import com.aifitness.assistant.privacy.application.ReauthenticationProofIssuer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Local/test-only server-side proof registry; raw proofs are never stored. */
final class LocalReauthenticationProofStore
        implements ReauthenticationProofIssuer, PrivacyRequestService.ReauthenticationPort {

    private final Clock clock;
    private final Duration timeToLive;
    private final SecureRandom secureRandom;
    private final WechatIdentityResolver identities;
    private final Map<String, ProofRecord> records = new HashMap<>();

    LocalReauthenticationProofStore(
            Clock clock, Duration timeToLive, WechatIdentityResolver identities) {
        this(clock, timeToLive, new SecureRandom(), identities);
    }

    LocalReauthenticationProofStore(
            Clock clock,
            Duration timeToLive,
            SecureRandom secureRandom,
            WechatIdentityResolver identities) {
        this.clock = Objects.requireNonNull(clock);
        this.timeToLive = Objects.requireNonNull(timeToLive);
        this.secureRandom = Objects.requireNonNull(secureRandom);
        this.identities = Objects.requireNonNull(identities);
    }

    @Override
    public synchronized IssuedProof issue(
            AuthenticatedUserId userId, String oneTimeCredential) {
        final boolean matches;
        try {
            matches = identities.resolveExisting(oneTimeCredential)
                    .map(identity -> identity.userId().equals(userId))
                    .orElse(false);
        } catch (IllegalArgumentException exception) {
            throw new PrivacyRequestService.ReauthenticationRequiredException();
        }
        if (!matches) {
            throw new PrivacyRequestService.ReauthenticationRequiredException();
        }
        Instant issuedAt = clock.instant();
        removeStaleRecords(issuedAt);
        Instant expiresAt = issuedAt.plus(timeToLive);
        String proof = randomProof();
        records.put(digest(proof), new ProofRecord(userId, issuedAt, expiresAt, false));
        return new IssuedProof(proof, issuedAt, expiresAt);
    }

    @Override
    public synchronized boolean verify(AuthenticatedUserId user, String oneTimeProof) {
        if (oneTimeProof == null || oneTimeProof.isBlank()) {
            return false;
        }
        String proofDigest = digest(oneTimeProof);
        Instant now = clock.instant();
        removeStaleRecords(now);
        ProofRecord record = records.get(proofDigest);
        if (record == null || record.consumed || !record.userId.equals(user)
                || now.isBefore(record.issuedAt) || !now.isBefore(record.expiresAt)) {
            return false;
        }
        records.put(proofDigest, record.markConsumed());
        removeStaleRecords(now);
        return true;
    }

    private void removeStaleRecords(Instant now) {
        records.values().removeIf(record -> record.consumed
                || !now.isBefore(record.expiresAt));
    }

    private String randomProof() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String proof) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(proof.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ProofRecord(
            AuthenticatedUserId userId, Instant issuedAt, Instant expiresAt, boolean consumed) {
        ProofRecord markConsumed() {
            return new ProofRecord(userId, issuedAt, expiresAt, true);
        }
    }
}
