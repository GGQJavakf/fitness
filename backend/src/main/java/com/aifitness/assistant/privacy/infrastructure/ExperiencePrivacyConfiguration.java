package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import com.aifitness.assistant.privacy.application.PrivacyExportRepository;
import com.aifitness.assistant.privacy.application.PrivacyRateLimitPort;
import com.aifitness.assistant.privacy.application.PrivacyRepository;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("staging-experience")
public class ExperiencePrivacyConfiguration {

    @Bean
    PrivacyRepository privacyRepository(DataSource dataSource) {
        return new JdbcPrivacyRepository(dataSource);
    }

    @Bean
    PrivacyExportRepository privacyExportRepository(
            DataSource dataSource, ObjectMapper objectMapper, Clock clock) {
        return new JdbcPrivacyExportRepository(dataSource, objectMapper, clock);
    }

    @Bean
    LocalReauthenticationProofStore privacyReauthenticationProofStore(
            Clock clock, WechatIdentityResolver identities) {
        return new LocalReauthenticationProofStore(clock, Duration.ofMinutes(5), identities);
    }

    @Bean
    PrivacyRequestService.AuditPort privacyAudit(DataSource dataSource, Clock clock) {
        return new JdbcPrivacyAudit(dataSource, clock);
    }

    @Bean
    PrivacyDataPort privacyData(DataSource dataSource) {
        return new JdbcPrivacyDataReader(dataSource);
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
            Clock clock,
            PrivacyDataPort data,
            PrivacyRateLimitPort rateLimit) {
        return new PrivacyRequestService(
                repository, exportRepository, reauthentication, reauthentication,
                audit, clock, data, rateLimit);
    }
}
