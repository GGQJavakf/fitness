package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import java.util.Objects;

/**
 * Local/test adapter. The production adapter must use a non-creating identity lookup and the
 * configured WeChat code exchange service before public release.
 */
final class WechatCodeReauthenticationAdapter implements PrivacyRequestService.ReauthenticationPort {

    private final WechatIdentityResolver identities;

    WechatCodeReauthenticationAdapter(WechatIdentityResolver identities) {
        this.identities = Objects.requireNonNull(identities);
    }

    @Override
    public boolean verify(AuthenticatedUserId user, String oneTimeProof) {
        try {
            AuthenticatedUserId resolved = identities.resolve(oneTimeProof);
            return resolved.equals(user);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
