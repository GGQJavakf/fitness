package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.privacy.application.PrivacyRepository;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import java.time.Clock;
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
            WechatIdentityResolver identities) {
        return new WechatCodeReauthenticationAdapter(identities);
    }

    @Bean
    PrivacyRequestService.AuditPort privacyAudit(Clock identityClock) {
        return new InMemoryPrivacyAudit(identityClock);
    }

    @Bean
    PrivacyRequestService privacyRequestService(
            PrivacyRepository repository,
            PrivacyRequestService.ReauthenticationPort reauthentication,
            PrivacyRequestService.AuditPort audit,
            Clock identityClock) {
        return new PrivacyRequestService(repository, reauthentication, audit, identityClock);
    }
}
