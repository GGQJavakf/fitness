package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.IdentityRepository;
import com.aifitness.assistant.identity.application.AuthenticationAttemptLimiter;
import com.aifitness.assistant.identity.application.SessionStore;
import com.aifitness.assistant.identity.application.SubjectProtector;
import com.aifitness.assistant.identity.application.UserAccessRevocation;
import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.application.WechatLoginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@Profile("staging-experience")
public class ExperienceIdentityConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    RestClient experienceWechatRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new NoRedirectClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    WechatIdentityProvider experienceWechatIdentityProvider(
            @Qualifier("experienceWechatRestClient") RestClient client,
            ObjectMapper objectMapper,
            @Value("${fitness.auth.wechat.app-id}") String appId,
            @Value("${fitness.auth.wechat.app-secret}") String appSecret) {
        return new WechatCodeSessionIdentityProvider(client, objectMapper, appId, appSecret);
    }

    @Bean
    Clock experienceIdentityClock() {
        return Clock.systemUTC();
    }

    @Bean
    SubjectProtector experienceSubjectProtector() {
        return new Sha256SubjectProtector();
    }

    @Bean
    IdentityRepository experienceIdentityRepository(DataSource dataSource) {
        return new JdbcIdentityRepository(dataSource);
    }

    @Bean
    SessionStore experienceSessionStore(DataSource dataSource) {
        return new JdbcSessionStore(dataSource);
    }

    @Bean
    AuthenticationAttemptLimiter experienceAuthenticationAttemptLimiter(DataSource dataSource) {
        return new JdbcAuthenticationAttemptLimiter(dataSource, 10, 600, Duration.ofMinutes(1));
    }

    @Bean
    WechatIdentityResolver experienceWechatIdentityResolver(
            WechatIdentityProvider provider,
            SubjectProtector protector,
            IdentityRepository identities,
            Clock experienceIdentityClock) {
        return new LocalWechatIdentityResolver(
                provider, protector, identities, experienceIdentityClock);
    }

    @Bean
    UserAccessRevocation experienceUserAccessRevocation(SessionStore sessions) {
        return sessions::revokeAllSessionsAndBlockLogin;
    }

    @Bean
    WechatLoginService experienceWechatLoginService(
            WechatIdentityProvider provider,
            SubjectProtector protector,
            IdentityRepository identities,
            SessionStore sessions,
            Clock experienceIdentityClock,
            AuthenticationAttemptLimiter attemptLimiter) {
        return new WechatLoginService(
                provider, protector, identities, sessions, experienceIdentityClock, attemptLimiter);
    }

    private static final class NoRedirectClientHttpRequestFactory
            extends SimpleClientHttpRequestFactory {

        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
