package com.aifitness.assistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.application.IdentityRepository;
import com.aifitness.assistant.identity.application.SessionStore;
import com.aifitness.assistant.identity.application.SubjectProtector;
import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.domain.UserIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class WechatLoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T01:00:00Z");
    private final RecordingIdentityRepository identities = new RecordingIdentityRepository();
    private final TestSessionStore sessions = new TestSessionStore();
    private final AtomicReference<String> exchangedCode = new AtomicReference<>();
    private final Map<String, String> subjects = new HashMap<>();
    private final WechatIdentityProvider provider = code -> {
        exchangedCode.set(code);
        return new WechatIdentityProvider.ProviderSubject(subjects.getOrDefault(code, code));
    };
    private final SubjectProtector protector = subject ->
            ("protected:" + subject).getBytes(StandardCharsets.UTF_8);
    private final WechatLoginService service = new WechatLoginService(
            provider, protector, identities, sessions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void sameProviderSubjectReusesTheInternalAccountWithoutPersistingTheWechatCode() {
        subjects.put("one-time-code-a", "wechat-subject-1");
        subjects.put("one-time-code-b", "wechat-subject-1");

        WechatLoginService.SessionTokens first = service.login("one-time-code-a");
        WechatLoginService.SessionTokens second = service.login("one-time-code-b");

        assertThat(exchangedCode).hasValue("one-time-code-b");
        assertThat(first.userId()).isEqualTo(second.userId());
        assertThat(identities.createdAccounts).isEqualTo(1);
        assertThat(new String(identities.lastProtectedSubject, StandardCharsets.UTF_8))
                .isEqualTo("protected:wechat-subject-1")
                .doesNotContain("one-time-code");
        assertThat(first.accessToken()).doesNotContain("wechat-subject", "one-time-code");
        assertThat(first.refreshToken()).doesNotContain("wechat-subject", "one-time-code");
    }

    @Test
    void differentSubjectsCreateIsolatedInternalAccounts() {
        WechatLoginService.SessionTokens first = service.login("subject-a");
        WechatLoginService.SessionTokens second = service.login("subject-b");

        assertThat(first.userId()).isNotEqualTo(second.userId());
    }

    @Test
    void trustedCloudBaseSubjectIssuesASessionWithoutExchangingAWechatCode() {
        WechatLoginService.SessionTokens tokens =
                service.loginTrustedWechatSubject("cloudbase-openid");

        assertThat(exchangedCode).hasValue(null);
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(new String(identities.lastProtectedSubject, StandardCharsets.UTF_8))
                .isEqualTo("protected:cloudbase-openid");
    }

    @Test
    void rejectsMalformedTrustedCloudBaseSubjects() {
        assertThatThrownBy(() -> service.loginTrustedWechatSubject("openid\r\nforged"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wechat subject is invalid");
        assertThatThrownBy(() -> service.loginTrustedWechatSubject("x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wechat subject is invalid");
    }

    @Test
    void refreshRotatesTheRefreshTokenAndLogoutRevokesTheWholeSession() {
        WechatLoginService.SessionTokens login = service.login("subject-a");

        WechatLoginService.SessionTokens refreshed = service.refresh(login.refreshToken());

        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThatThrownBy(() -> service.refresh(login.refreshToken()))
                .isInstanceOf(WechatLoginService.AuthenticationRequiredException.class);

        service.logout(refreshed.accessToken());

        assertThatThrownBy(() -> service.authenticate(refreshed.accessToken()))
                .isInstanceOf(WechatLoginService.AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.refresh(refreshed.refreshToken()))
                .isInstanceOf(WechatLoginService.AuthenticationRequiredException.class);
    }

    @Test
    void rejectsBlankCredentialsWithoutIncludingThemInAnException() {
        assertThatThrownBy(() -> service.login(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wechat code must not be blank");
        assertThatThrownBy(() -> service.refresh(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refresh token must not be blank");
        assertThatThrownBy(() -> service.login("code\r\nforged"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wechat code is invalid");
        assertThatThrownBy(() -> service.login("x".repeat(2049)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wechat code is invalid");
    }

    @Test
    void mapsRejectedWechatCodesToTheChannelIndependentAuthenticationFailure() {
        WechatIdentityProvider rejectedProvider = code -> {
            throw new WechatIdentityProvider.ExchangeRejectedException();
        };
        var rejectedService = new WechatLoginService(
                rejectedProvider,
                protector,
                identities,
                sessions,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> rejectedService.login("expired-wechat-code"))
                .isInstanceOf(WechatLoginService.AuthenticationRequiredException.class)
                .hasMessage("authentication required")
                .hasMessageNotContaining("expired-wechat-code");
    }

    @Test
    void neverWritesTheOneTimeCodeToApplicationLogs() {
        String secretCode = "temporary-wechat-code-never-log";
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            service.login(secretCode);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(secretCode));
    }

    private static final class RecordingIdentityRepository implements IdentityRepository {
        private final Map<String, AuthenticatedUserId> users = new HashMap<>();
        private int createdAccounts;
        private byte[] lastProtectedSubject;

        @Override
        public synchronized AuthenticatedUserId findOrCreate(
                UserIdentity.Provider provider, byte[] protectedSubject, Instant now) {
            lastProtectedSubject = protectedSubject.clone();
            String key = provider + ":" + Arrays.toString(protectedSubject);
            return users.computeIfAbsent(key, ignored -> {
                createdAccounts++;
                return new AuthenticatedUserId(UUID.randomUUID());
            });
        }
    }

    private static final class TestSessionStore implements SessionStore {
        private final Map<String, State> byAccess = new HashMap<>();
        private final Map<String, State> byRefresh = new HashMap<>();
        private int sequence;

        @Override
        public WechatLoginService.SessionTokens issue(AuthenticatedUserId userId, Instant now) {
            State state = new State(userId);
            return rotate(state, now);
        }

        @Override
        public WechatLoginService.SessionTokens refresh(String refreshToken, Instant now) {
            State state = byRefresh.remove(refreshToken);
            if (state == null || state.revoked) {
                throw new WechatLoginService.AuthenticationRequiredException();
            }
            byAccess.remove(state.accessToken);
            return rotate(state, now);
        }

        @Override
        public void revoke(String accessToken) {
            State state = byAccess.get(accessToken);
            if (state == null) {
                throw new WechatLoginService.AuthenticationRequiredException();
            }
            state.revoked = true;
            byAccess.remove(state.accessToken);
            byRefresh.remove(state.refreshToken);
        }

        @Override
        public void revokeAllSessionsAndBlockLogin(AuthenticatedUserId userId, UUID requestId) {
            byAccess.values().stream()
                    .filter(state -> state.userId.equals(userId))
                    .toList()
                    .forEach(state -> revoke(state.accessToken));
        }

        @Override
        public AuthenticatedUserId authenticate(String accessToken, Instant now) {
            State state = byAccess.get(accessToken);
            if (state == null || state.revoked) {
                throw new WechatLoginService.AuthenticationRequiredException();
            }
            return state.userId;
        }

        private WechatLoginService.SessionTokens rotate(State state, Instant now) {
            state.accessToken = "access-" + ++sequence;
            state.refreshToken = "refresh-" + sequence;
            byAccess.put(state.accessToken, state);
            byRefresh.put(state.refreshToken, state);
            return new WechatLoginService.SessionTokens(
                    state.userId, state.accessToken, state.refreshToken, now.plusSeconds(900));
        }

        private static final class State {
            private final AuthenticatedUserId userId;
            private String accessToken;
            private String refreshToken;
            private boolean revoked;

            private State(AuthenticatedUserId userId) {
                this.userId = userId;
            }
        }
    }
}
