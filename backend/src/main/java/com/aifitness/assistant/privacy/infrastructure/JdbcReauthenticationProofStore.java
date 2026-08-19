package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import com.aifitness.assistant.privacy.application.ReauthenticationProofIssuer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared, digest-only, single-use proof registry for multi-instance experience deployments. */
final class JdbcReauthenticationProofStore
        implements ReauthenticationProofIssuer, PrivacyRequestService.ReauthenticationPort {

    private static final String INSERT_PROOF = """
            INSERT INTO privacy_reauthentication_proof
                (proof_digest, user_id, issued_at, expires_at, consumed_at)
            VALUES (?, ?, ?, ?, NULL)
            """;
    private static final String CONSUME_PROOF = """
            UPDATE privacy_reauthentication_proof
            SET consumed_at = ?
            WHERE proof_digest = ?
              AND user_id = ?
              AND consumed_at IS NULL
              AND issued_at <= ?
              AND expires_at > ?
            """;
    private static final String REMOVE_STALE = """
            DELETE FROM privacy_reauthentication_proof
            WHERE expires_at <= ? OR consumed_at IS NOT NULL
            LIMIT 128
            """;

    private final JdbcOperations jdbc;
    private final Clock clock;
    private final Duration timeToLive;
    private final SecureRandom secureRandom;
    private final WechatIdentityResolver identities;

    JdbcReauthenticationProofStore(
            DataSource dataSource,
            Clock clock,
            Duration timeToLive,
            WechatIdentityResolver identities) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null")),
                clock, timeToLive, new SecureRandom(), identities);
    }

    JdbcReauthenticationProofStore(
            JdbcOperations jdbc,
            Clock clock,
            Duration timeToLive,
            SecureRandom secureRandom,
            WechatIdentityResolver identities) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeToLive = Objects.requireNonNull(timeToLive, "timeToLive must not be null");
        if (timeToLive.isNegative() || timeToLive.isZero()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
    }

    @Override
    public IssuedProof issue(AuthenticatedUserId userId, String oneTimeCredential) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (!credentialBelongsTo(userId, oneTimeCredential)) {
            throw new PrivacyRequestService.ReauthenticationRequiredException();
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(timeToLive);
        String proof = randomProof();
        int inserted = jdbc.update(INSERT_PROOF, digest(proof), bytes(userId.value()),
                Timestamp.from(issuedAt), Timestamp.from(expiresAt));
        if (inserted != 1) {
            throw new IllegalStateException("Unable to persist reauthentication proof");
        }
        removeStale(issuedAt);
        return new IssuedProof(proof, issuedAt, expiresAt);
    }

    @Override
    public boolean verify(AuthenticatedUserId userId, String oneTimeProof) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (oneTimeProof == null || oneTimeProof.isBlank()) {
            return false;
        }
        Instant now = clock.instant();
        int consumed = jdbc.update(CONSUME_PROOF, Timestamp.from(now), digest(oneTimeProof),
                bytes(userId.value()), Timestamp.from(now), Timestamp.from(now));
        removeStale(now);
        return consumed == 1;
    }

    private boolean credentialBelongsTo(AuthenticatedUserId userId, String credential) {
        try {
            return identities.resolveExisting(credential)
                    .map(identity -> identity.userId().equals(userId))
                    .orElse(false);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void removeStale(Instant now) {
        jdbc.update(REMOVE_STALE, Timestamp.from(now));
    }

    private String randomProof() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] digest(String proof) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(proof.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
