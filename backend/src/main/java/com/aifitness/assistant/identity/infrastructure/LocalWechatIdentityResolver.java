package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.IdentityRepository;
import com.aifitness.assistant.identity.application.SubjectProtector;
import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.domain.UserIdentity;
import java.time.Clock;
import java.util.Objects;

final class LocalWechatIdentityResolver implements WechatIdentityResolver {

    private final WechatIdentityProvider provider;
    private final SubjectProtector protector;
    private final IdentityRepository identities;
    private final Clock clock;

    LocalWechatIdentityResolver(
            WechatIdentityProvider provider,
            SubjectProtector protector,
            IdentityRepository identities,
            Clock clock) {
        this.provider = Objects.requireNonNull(provider);
        this.protector = Objects.requireNonNull(protector);
        this.identities = Objects.requireNonNull(identities);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AuthenticatedUserId resolve(String oneTimeCode) {
        var subject = provider.exchange(oneTimeCode);
        byte[] protectedSubject = protector.protect(subject.subject());
        return identities.findOrCreate(
                UserIdentity.Provider.WECHAT_MINI_PROGRAM, protectedSubject, clock.instant());
    }
}
