package com.aifitness.assistant.identity.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.domain.UserIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class WechatLoginService {

    private final WechatIdentityProvider identityProvider;
    private final SubjectProtector subjectProtector;
    private final IdentityRepository identityRepository;
    private final SessionStore sessionStore;
    private final Clock clock;

    public WechatLoginService(
            WechatIdentityProvider identityProvider,
            SubjectProtector subjectProtector,
            IdentityRepository identityRepository,
            SessionStore sessionStore,
            Clock clock) {
        this.identityProvider = Objects.requireNonNull(identityProvider);
        this.subjectProtector = Objects.requireNonNull(subjectProtector);
        this.identityRepository = Objects.requireNonNull(identityRepository);
        this.sessionStore = Objects.requireNonNull(sessionStore);
        this.clock = Objects.requireNonNull(clock);
    }

    public SessionTokens login(String oneTimeCode) {
        requireCredential(oneTimeCode, 2048, "wechat code");
        WechatIdentityProvider.ProviderSubject providerSubject;
        try {
            providerSubject = identityProvider.exchange(oneTimeCode);
        } catch (WechatIdentityProvider.ExchangeRejectedException rejected) {
            throw new AuthenticationRequiredException();
        }
        byte[] protectedSubject = subjectProtector.protect(providerSubject.subject());
        if (protectedSubject == null || protectedSubject.length == 0) {
            throw new IllegalStateException("subject protection failed");
        }
        Instant now = clock.instant();
        AuthenticatedUserId userId = identityRepository.findOrCreate(
                UserIdentity.Provider.WECHAT_MINI_PROGRAM, protectedSubject.clone(), now);
        return sessionStore.issue(userId, now);
    }

    public SessionTokens refresh(String refreshToken) {
        requireCredential(refreshToken, 4096, "refresh token");
        return sessionStore.refresh(refreshToken, clock.instant());
    }

    public void logout(String accessToken) {
        requireCredential(accessToken, 4096, "access token");
        sessionStore.revoke(accessToken);
    }

    public AuthenticatedUserId authenticate(String accessToken) {
        requireCredential(accessToken, 4096, "access token");
        return sessionStore.authenticate(accessToken, clock.instant());
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireCredential(String value, int maxLength, String name) {
        requireNotBlank(value, name + " must not be blank");
        if (value.length() > maxLength
                || !value.equals(value.strip())
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    public record SessionTokens(
            AuthenticatedUserId userId, String accessToken, String refreshToken, Instant expiresAt) {
        public SessionTokens {
            Objects.requireNonNull(userId, "userId must not be null");
            requireNotBlank(accessToken, "accessToken must not be blank");
            requireNotBlank(refreshToken, "refreshToken must not be blank");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    public static final class AuthenticationRequiredException extends RuntimeException {
        public AuthenticationRequiredException() {
            super("authentication required");
        }
    }
}
