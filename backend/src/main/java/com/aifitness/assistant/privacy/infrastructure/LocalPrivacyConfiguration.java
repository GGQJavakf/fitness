package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.identity.application.UserAccessRevocation;
import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import com.aifitness.assistant.privacy.application.PrivacyExportRepository;
import com.aifitness.assistant.privacy.application.PrivacyRateLimitPort;
import com.aifitness.assistant.privacy.application.PrivacyRepository;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
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
    PrivacyExportRepository privacyExportRepository(Clock identityClock) {
        return new InMemoryPrivacyExportRepository(identityClock);
    }

    @Bean
    LocalReauthenticationProofStore localReauthenticationProofStore(
            Clock identityClock, WechatIdentityResolver identities) {
        return new LocalReauthenticationProofStore(
                identityClock, Duration.ofMinutes(5), identities);
    }

    @Bean
    PrivacyRequestService.AuditPort privacyAudit(Clock identityClock) {
        return new InMemoryPrivacyAudit(identityClock);
    }

    @Bean
    LocalPrivacyDataFixture privacyDataFixture(UserAccessRevocation accessRevocation) {
        return new LocalPrivacyDataFixture(accessRevocation);
    }

    @Bean
    PrivacyRateLimitPort privacyRateLimit() {
        return new InMemoryPrivacyRateLimiter(20, Duration.ofMinutes(1));
    }

    @Bean
    PrivacyRequestService privacyRequestService(
            PrivacyRepository repository,
            PrivacyExportRepository exportRepository,
            LocalReauthenticationProofStore reauthentication,
            PrivacyRequestService.AuditPort audit,
            Clock identityClock,
            PrivacyDataPort data,
            PrivacyRateLimitPort rateLimit) {
        return new PrivacyRequestService(
                repository,
                exportRepository,
                reauthentication,
                reauthentication,
                audit,
                identityClock,
                data,
                rateLimit);
    }

    @Bean
    PrivacyDeletionWorker privacyDeletionWorker(
            PrivacyRepository repository,
            LocalPrivacyDataFixture data,
            PrivacyRequestService.AuditPort audit,
            Clock identityClock) {
        return new PrivacyDeletionWorker(repository, data, audit, identityClock);
    }
}
