package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.privacy.application.PrivacyRepository;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import com.aifitness.assistant.privacy.application.PrivacyRateLimitPort;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"local", "test"})
public class LocalPrivacyConfiguration {

    @Bean
    PrivacyRepository privacyRepository() {
        return new InMemoryPrivacyRepository();
    }

    @Bean
    PrivacyRequestService.ReauthenticationPort privacyReauthentication(
            WechatIdentityResolver identities, Clock identityClock) {
        return new WechatCodeReauthenticationAdapter(identities, identityClock, Duration.ofMinutes(5));
    }

    @Bean
    PrivacyRequestService.AuditPort privacyAudit(Clock identityClock) {
        return new InMemoryPrivacyAudit(identityClock);
    }

    @Bean
    LocalPrivacyDataFixture privacyDataFixture() {
        return new LocalPrivacyDataFixture();
    }

    @Bean
    PrivacyRateLimitPort privacyRateLimit() {
        return new InMemoryPrivacyRateLimiter(20, Duration.ofMinutes(1));
    }

    @Bean
    PrivacyRequestService privacyRequestService(
            PrivacyRepository repository,
            PrivacyRequestService.ReauthenticationPort reauthentication,
            PrivacyRequestService.AuditPort audit,
            Clock identityClock,
            PrivacyDataPort data,
            PrivacyRateLimitPort rateLimit) {
        return new PrivacyRequestService(
                repository, reauthentication, audit, identityClock, data, rateLimit);
    }

    @Bean
    PrivacyDeletionWorker privacyDeletionWorker(
            PrivacyRepository repository,
            LocalPrivacyDataFixture data,
            PrivacyRequestService.AuditPort audit,
            Clock identityClock) {
        return new PrivacyDeletionWorker(
                repository, data, audit, identityClock);
    }
}
