package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.SessionStore;
import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class InMemorySessionStore implements SessionStore {

    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private final SecureRandom secureRandom;
    private final Map<String, SessionState> byAccessHash = new HashMap<>();
    private final Map<String, SessionState> byRefreshHash = new HashMap<>();
    private final Set<AuthenticatedUserId> blockedUsers = new HashSet<>();

    public InMemorySessionStore() {
        this(new SecureRandom());
    }

    InMemorySessionStore(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public synchronized WechatLoginService.SessionTokens issue(AuthenticatedUserId userId, Instant now) {
        if (blockedUsers.contains(userId)) {
            throw new WechatLoginService.AuthenticationRequiredException();
        }
        return rotate(new SessionState(userId), now);
    }

    @Override
    public synchronized WechatLoginService.SessionTokens refresh(String refreshToken, Instant now) {
        String refreshHash = hash(refreshToken);
        SessionState state = byRefreshHash.remove(refreshHash);
        if (state == null || state.revoked || !now.isBefore(state.refreshExpiresAt)) {
            if (state != null) {
                byAccessHash.remove(state.accessHash);
                state.revoked = true;
            }
            throw new WechatLoginService.AuthenticationRequiredException();
        }
        byAccessHash.remove(state.accessHash);
        return rotate(state, now);
    }

    @Override
    public synchronized void revoke(String accessToken) {
        SessionState state = byAccessHash.remove(hash(accessToken));
        if (state == null) {
            throw new WechatLoginService.AuthenticationRequiredException();
        }
        state.revoked = true;
        byRefreshHash.remove(state.refreshHash);
    }

    @Override
    public synchronized void revokeAllSessionsAndBlockLogin(AuthenticatedUserId userId) {
        blockedUsers.add(userId);
        Set<SessionState> sessions = new HashSet<>(byAccessHash.values());
        sessions.addAll(byRefreshHash.values());
        sessions.stream()
                .filter(state -> state.userId.equals(userId))
                .toList()
                .forEach(state -> {
                    state.revoked = true;
                    byAccessHash.remove(state.accessHash);
                    byRefreshHash.remove(state.refreshHash);
                });
    }

    @Override
    public synchronized AuthenticatedUserId authenticate(String accessToken, Instant now) {
        SessionState state = byAccessHash.get(hash(accessToken));
        if (state == null || state.revoked || !now.isBefore(state.accessExpiresAt)) {
            if (state != null && !now.isBefore(state.accessExpiresAt)) {
                byAccessHash.remove(state.accessHash);
            }
            throw new WechatLoginService.AuthenticationRequiredException();
        }
        return state.userId;
    }

    private WechatLoginService.SessionTokens rotate(SessionState state, Instant now) {
        if (state.accessHash != null) {
            byAccessHash.remove(state.accessHash);
        }
        if (state.refreshHash != null) {
            byRefreshHash.remove(state.refreshHash);
        }

        String accessToken = randomToken();
        String refreshToken = randomToken();
        state.accessHash = hash(accessToken);
        state.refreshHash = hash(refreshToken);
        state.accessExpiresAt = now.plus(ACCESS_TTL);
        state.refreshExpiresAt = now.plus(REFRESH_TTL);
        byAccessHash.put(state.accessHash, state);
        byRefreshHash.put(state.refreshHash, state);
        return new WechatLoginService.SessionTokens(
                state.userId, accessToken, refreshToken, state.accessExpiresAt);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("required token hashing algorithm is unavailable");
        }
    }

    private static final class SessionState {
        private final AuthenticatedUserId userId;
        private String accessHash;
        private String refreshHash;
        private Instant accessExpiresAt;
        private Instant refreshExpiresAt;
        private boolean revoked;

        private SessionState(AuthenticatedUserId userId) {
            this.userId = userId;
        }
    }
}
