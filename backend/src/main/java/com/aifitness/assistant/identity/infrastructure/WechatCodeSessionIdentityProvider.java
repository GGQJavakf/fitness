package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import java.util.Objects;
import org.springframework.web.client.RestClient;

final class WechatCodeSessionIdentityProvider implements WechatIdentityProvider {

    private static final String CODE_SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session"
                    + "?appid={appId}&secret={appSecret}&js_code={code}&grant_type=authorization_code";

    private final RestClient client;
    private final String appId;
    private final String appSecret;

    WechatCodeSessionIdentityProvider(RestClient client, String appId, String appSecret) {
        this.client = Objects.requireNonNull(client);
        this.appId = requireCredential(appId, "appId");
        this.appSecret = requireCredential(appSecret, "appSecret");
    }

    @Override
    public ProviderSubject exchange(String oneTimeCode) {
        CodeSessionResponse response = client.get()
                .uri(CODE_SESSION_URL, appId, appSecret, oneTimeCode)
                .retrieve()
                .body(CodeSessionResponse.class);
        if (response == null
                || (response.errcode() != null && response.errcode() != 0)
                || response.openid() == null
                || response.openid().isBlank()) {
            throw new ExchangeRejectedException();
        }
        return new ProviderSubject(response.openid());
    }

    private static String requireCredential(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record CodeSessionResponse(String openid, Integer errcode) {}
}
