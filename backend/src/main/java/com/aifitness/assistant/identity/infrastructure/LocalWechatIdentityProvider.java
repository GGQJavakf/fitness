package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityProvider;

/** Local/test substitute only. A real adapter must exchange the code server-side with WeChat. */
public final class LocalWechatIdentityProvider implements WechatIdentityProvider {

    @Override
    public ProviderSubject exchange(String oneTimeCode) {
        if (oneTimeCode == null || oneTimeCode.isBlank()) {
            throw new IllegalArgumentException("wechat code must not be blank");
        }
        return new ProviderSubject("local-wechat-user");
    }
}
