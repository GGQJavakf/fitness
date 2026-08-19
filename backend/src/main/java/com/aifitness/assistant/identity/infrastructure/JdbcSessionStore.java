package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.SessionStore;
import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Database-backed opaque sessions. Only SHA-256 token digests cross the persistence boundary. */
public final class JdbcSessionStore implements SessionStore {

    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SecureRandom secureRandom;

    public JdbcSessionStore(DataSource dataSource) {
        this(dataSource, new SecureRandom());
    }

    JdbcSessionStore(DataSource dataSource, SecureRandom secureRandom) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    @Override
    public WechatLoginService.SessionTokens issue(AuthenticatedUserId userId, Instant now) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return Objects.requireNonNull(transactions.execute(status -> {
            requireActiveAccount(userId);
            return insertSession(userId, now);
        }));
    }

    @Override
    public WechatLoginService.SessionTokens refresh(String refreshToken, Instant now) {
        byte[] refreshDigest = digest(refreshToken);
        Objects.requireNonNull(now, "now must not be null");
        return Objects.requireNonNull(transactions.execute(status -> {
            requireNotTerminalRefreshToken(refreshDigest);
            List<RefreshableSession> sessions = jdbc.query("""
                    SELECT id, user_id, refresh_expires_at
                    FROM auth_session
                    WHERE refresh_token_digest = ? AND status = 'ACTIVE'
                    FOR UPDATE
                    """, (row, ignored) -> new RefreshableSession(
                            JdbcBinaryUuid.uuid(row.getBytes(1)),
                            new AuthenticatedUserId(JdbcBinaryUuid.uuid(row.getBytes(2))),
                            row.getTimestamp(3).toInstant()), refreshDigest);
            if (sessions.isEmpty() || !now.isBefore(sessions.getFirst().refreshExpiresAt())) {
                throw new WechatLoginService.AuthenticationRequiredException();
            }
            RefreshableSession current = sessions.getFirst();
            requireActiveAccount(current.userId());
            int rotated = jdbc.update("""
                    UPDATE auth_session
                    SET status = 'ROTATED', updated_at = ?, revoked_at = ?
                    WHERE id = ? AND status = 'ACTIVE'
                    """, Timestamp.from(now), Timestamp.from(now), JdbcBinaryUuid.bytes(current.id()));
            if (rotated != 1) {
                throw new WechatLoginService.AuthenticationRequiredException();
            }
            return insertSession(current.userId(), now);
        }));
    }

    @Override
    public void revoke(String accessToken) {
        Instant now = Instant.now();
        int revoked = jdbc.update("""
                UPDATE auth_session
                SET status = 'REVOKED', updated_at = ?, revoked_at = ?
                WHERE access_token_digest = ? AND status = 'ACTIVE'
                """, Timestamp.from(now), Timestamp.from(now), digest(accessToken));
        if (revoked != 1) {
            throw new WechatLoginService.AuthenticationRequiredException();
        }
    }

    @Override
    public void revokeAllSessionsAndBlockLogin(AuthenticatedUserId userId, UUID requestId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Instant now = Instant.now();
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE user_account
                    SET status = 'DELETED', deleted_at = COALESCE(deleted_at, ?)
                    WHERE id = ? AND status <> 'DELETED'
                    """, Timestamp.from(now), JdbcBinaryUuid.bytes(userId.value()));
            jdbc.update("""
                    UPDATE auth_session
                    SET status = 'REVOKED', updated_at = ?, revoked_at = ?
                    WHERE user_id = ? AND status = 'ACTIVE'
                    """, Timestamp.from(now), Timestamp.from(now), JdbcBinaryUuid.bytes(userId.value()));
        });
    }

    @Override
    public AuthenticatedUserId authenticate(String accessToken, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        byte[] accessDigest = digest(accessToken);
        List<AuthenticatedUserId> users = jdbc.query("""
                SELECT s.user_id
                FROM auth_session s
                JOIN user_account u ON u.id = s.user_id
                WHERE s.access_token_digest = ?
                  AND s.status = 'ACTIVE'
                  AND s.access_expires_at > ?
                  AND u.status = 'ACTIVE'
                """, (row, ignored) -> new AuthenticatedUserId(
                        JdbcBinaryUuid.uuid(row.getBytes(1))), accessDigest, Timestamp.from(now));
        if (users.isEmpty()) {
            requireNotTerminalAccessToken(accessDigest);
            throw new WechatLoginService.AuthenticationRequiredException();
        }
        return users.getFirst();
    }

    private void requireActiveAccount(AuthenticatedUserId userId) {
        List<byte[]> accounts = jdbc.query("""
                SELECT id FROM user_account
                WHERE id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """, (row, ignored) -> row.getBytes(1), JdbcBinaryUuid.bytes(userId.value()));
        if (accounts.isEmpty()) {
            throw new WechatLoginService.AuthenticationRequiredException();
        }
    }

    private void requireNotTerminalAccessToken(byte[] accessDigest) {
        requireNotTerminalToken("access_token_digest", accessDigest);
    }

    private void requireNotTerminalRefreshToken(byte[] refreshDigest) {
        requireNotTerminalToken("refresh_token_digest", refreshDigest);
    }

    private void requireNotTerminalToken(String tokenColumn, byte[] tokenDigest) {
        String sql = """
                SELECT COUNT(*)
                FROM auth_session s
                JOIN user_account u ON u.id = s.user_id
                WHERE s.%s = ? AND u.status = 'DELETED'
                """.formatted(tokenColumn);
        Integer count = jdbc.queryForObject(sql, Integer.class, tokenDigest);
        if (count != null && count > 0) {
            throw new WechatLoginService.AccessRevokedException();
        }
    }

    private WechatLoginService.SessionTokens insertSession(AuthenticatedUserId userId, Instant now) {
        String accessToken = randomToken();
        String refreshToken = randomToken();
        Instant accessExpiresAt = now.plus(ACCESS_TTL);
        Instant refreshExpiresAt = now.plus(REFRESH_TTL);
        jdbc.update("""
                INSERT INTO auth_session
                    (id, user_id, access_token_digest, refresh_token_digest,
                     access_expires_at, refresh_expires_at, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, JdbcBinaryUuid.bytes(UUID.randomUUID()), JdbcBinaryUuid.bytes(userId.value()),
                digest(accessToken), digest(refreshToken), Timestamp.from(accessExpiresAt),
                Timestamp.from(refreshExpiresAt), Timestamp.from(now), Timestamp.from(now));
        return new WechatLoginService.SessionTokens(
                userId, accessToken, refreshToken, accessExpiresAt);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] digest(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("required token hashing algorithm is unavailable");
        }
    }

    private record RefreshableSession(
            UUID id, AuthenticatedUserId userId, Instant refreshExpiresAt) {}
}
