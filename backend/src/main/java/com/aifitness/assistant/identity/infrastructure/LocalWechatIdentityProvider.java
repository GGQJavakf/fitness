package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Local/test substitute only. A real adapter must exchange the code server-side with WeChat. */
public final class LocalWechatIdentityProvider implements WechatIdentityProvider {

    @Override
    public ProviderSubject exchange(String oneTimeCode) {
        if (oneTimeCode == null || oneTimeCode.isBlank()) {
            throw new IllegalArgumentException("wechat code must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(oneTimeCode.getBytes(StandardCharsets.UTF_8));
            return new ProviderSubject("local:" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
