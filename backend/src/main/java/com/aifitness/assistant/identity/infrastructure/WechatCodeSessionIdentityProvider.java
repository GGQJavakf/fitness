package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class WechatCodeSessionIdentityProvider implements WechatIdentityProvider {

    private static final String CODE_SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session"
                    + "?appid={appId}&secret={appSecret}&js_code={code}&grant_type=authorization_code";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String appId;
    private final String appSecret;

    WechatCodeSessionIdentityProvider(RestClient client, String appId, String appSecret) {
        this(client, new ObjectMapper(), appId, appSecret);
    }

    WechatCodeSessionIdentityProvider(
            RestClient client, ObjectMapper objectMapper, String appId, String appSecret) {
        this.client = Objects.requireNonNull(client);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.appId = requireCredential(appId, "appId");
        this.appSecret = requireCredential(appSecret, "appSecret");
    }

    @Override
    public ProviderSubject exchange(String oneTimeCode) {
        CodeSessionResponse response;
        try {
            String responseBody = client.get()
                    .uri(CODE_SESSION_URL, appId, appSecret, oneTimeCode)
                    .retrieve()
                    .body(String.class);
            response = responseBody == null
                    ? null
                    : objectMapper.readValue(responseBody, CodeSessionResponse.class);
        } catch (RestClientException | JsonProcessingException providerFailure) {
            // RestClient exceptions may contain the fully expanded URI. Never let the
            // app secret, one-time code, or provider response escape this boundary.
            throw new ProviderUnavailableException();
        }
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CodeSessionResponse(String openid, Integer errcode) {}
}
