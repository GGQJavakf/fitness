package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.IdentityRepository;
import com.aifitness.assistant.identity.application.SessionStore;
import com.aifitness.assistant.identity.application.SubjectProtector;
import com.aifitness.assistant.identity.application.UserAccessRevocation;
import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.application.WechatLoginService;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@Profile("staging-experience")
public class ExperienceIdentityConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    WechatIdentityProvider experienceWechatIdentityProvider(
            @Value("${fitness.auth.wechat.app-id}") String appId,
            @Value("${fitness.auth.wechat.app-secret}") String appSecret) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();
        return new WechatCodeSessionIdentityProvider(client, appId, appSecret);
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
            Clock experienceIdentityClock) {
        return new WechatLoginService(
                provider, protector, identities, sessions, experienceIdentityClock);
    }
}
