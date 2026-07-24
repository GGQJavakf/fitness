package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.IdentityRepository;
import com.aifitness.assistant.identity.application.SessionStore;
import com.aifitness.assistant.identity.application.SubjectProtector;
import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.application.WechatLoginService;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"local", "test"})
@ConditionalOnProperty(prefix = "fitness.auth", name = "local-substitute-enabled", havingValue = "true")
public class LocalIdentityConfiguration {

    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "localhost", "::1", "[::1]");

    @Bean
    LocalSubstituteBindingGuard localSubstituteBindingGuard(
            @Value("${server.address:}") String serverAddress) {
        String normalizedAddress = serverAddress == null
                ? ""
                : serverAddress.strip().toLowerCase(Locale.ROOT);
        if (!LOOPBACK_ADDRESSES.contains(normalizedAddress)) {
            throw new IllegalStateException(
                    "local identity substitute requires an explicit loopback server.address");
        }
        return new LocalSubstituteBindingGuard();
    }

    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    WechatIdentityProvider wechatIdentityProvider() {
        return new LocalWechatIdentityProvider();
    }

    @Bean
    SubjectProtector subjectProtector() {
        return new Sha256SubjectProtector();
    }

    @Bean
    IdentityRepository identityRepository() {
        return new InMemoryIdentityRepository();
    }

    @Bean
    WechatIdentityResolver wechatIdentityResolver(
            WechatIdentityProvider provider,
            SubjectProtector protector,
            IdentityRepository identities,
            Clock identityClock) {
        return new LocalWechatIdentityResolver(provider, protector, identities, identityClock);
    }

    @Bean
    SessionStore sessionStore() {
        return new InMemorySessionStore();
    }

    @Bean
    WechatLoginService wechatLoginService(
            WechatIdentityProvider provider,
            SubjectProtector protector,
            IdentityRepository identities,
            SessionStore sessions,
            Clock identityClock) {
        return new WechatLoginService(provider, protector, identities, sessions, identityClock);
    }

    static final class LocalSubstituteBindingGuard {}
}
