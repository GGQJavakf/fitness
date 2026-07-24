package com.aifitness.assistant.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import com.aifitness.assistant.identity.domain.UserIdentity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LocalWechatIdentityResolverTest {

    @Test
    void resolvesOnlyExistingIdentityAndNonceDoesNotChangeFixtureSubject() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-24T08:00:00Z"), ZoneOffset.UTC);
        LocalWechatIdentityProvider provider = new LocalWechatIdentityProvider();
        Sha256SubjectProtector protector = new Sha256SubjectProtector();
        InMemoryIdentityRepository repository = new InMemoryIdentityRepository();
        var subject = provider.exchange("alice");
        var user = repository.findOrCreate(
                UserIdentity.Provider.WECHAT_MINI_PROGRAM,
                protector.protect(subject.subject()),
                clock.instant());
        LocalWechatIdentityResolver resolver = new LocalWechatIdentityResolver(
                provider, protector, repository, clock);

        assertThat(resolver.resolveExisting("alice|fresh")).get()
                .extracting(result -> result.userId()).isEqualTo(user);
        assertThat(resolver.resolveExisting("unknown|fresh")).isEmpty();
        assertThat(repository.identityCount()).isEqualTo(1);
    }
}
