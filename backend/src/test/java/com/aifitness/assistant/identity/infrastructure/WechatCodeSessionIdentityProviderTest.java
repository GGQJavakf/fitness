package com.aifitness.assistant.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WechatCodeSessionIdentityProviderTest {

    @Test
    void exchangesTheTemporaryCodeForTheWechatOpenId() {
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
        var provider = new WechatCodeSessionIdentityProvider(
                clientBuilder.build(), "test-app-id", "test-app-secret");
        server.expect(requestTo(
                        "https://api.weixin.qq.com/sns/jscode2session"
                                + "?appid=test-app-id"
                                + "&secret=test-app-secret"
                                + "&js_code=temporary-code"
                                + "&grant_type=authorization_code"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"openid\":\"wechat-open-id\",\"session_key\":\"not-a-business-subject\"}",
                        MediaType.TEXT_PLAIN));

        var subject = provider.exchange("temporary-code");

        assertThat(subject.subject()).isEqualTo("wechat-open-id");
        server.verify();
    }

    @Test
    void rejectsWechatErrorResponsesWithoutExposingProviderDetails() {
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
        var provider = new WechatCodeSessionIdentityProvider(
                clientBuilder.build(), "test-app-id", "test-app-secret");
        server.expect(requestTo(
                        "https://api.weixin.qq.com/sns/jscode2session"
                                + "?appid=test-app-id"
                                + "&secret=test-app-secret"
                                + "&js_code=expired-code"
                                + "&grant_type=authorization_code"))
                .andRespond(withSuccess(
                        "{\"errcode\":40029,\"errmsg\":\"invalid code contains provider detail\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.exchange("expired-code"))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatIdentityProvider
                        .ExchangeRejectedException.class)
                .hasMessage("wechat credential exchange rejected")
                .hasMessageNotContaining("40029")
                .hasMessageNotContaining("provider detail")
                .hasMessageNotContaining("expired-code");
        server.verify();
    }

    @Test
    void sanitizesTransportFailuresThatMayContainTheExpandedCredentialUrl() {
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
        var provider = new WechatCodeSessionIdentityProvider(
                clientBuilder.build(), "test-app-id", "transport-secret");
        server.expect(requestTo(
                        "https://api.weixin.qq.com/sns/jscode2session"
                                + "?appid=test-app-id"
                                + "&secret=transport-secret"
                                + "&js_code=transport-code"
                                + "&grant_type=authorization_code"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("provider body contains transport-secret and transport-code"));

        assertThatThrownBy(() -> provider.exchange("transport-code"))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatIdentityProvider
                        .ProviderUnavailableException.class)
                .hasMessage("wechat identity provider unavailable")
                .hasMessageNotContaining("transport-secret")
                .hasMessageNotContaining("transport-code")
                .hasNoCause();
        server.verify();
    }

    @Test
    void sanitizesConnectionFailuresAndMalformedProviderJson() {
        RestClient connectionFailureClient = RestClient.builder()
                .requestFactory((uri, method) -> {
                    throw new IOException("expanded URI contained connection-secret and connection-code");
                })
                .build();
        var connectionFailureProvider = new WechatCodeSessionIdentityProvider(
                connectionFailureClient, "test-app-id", "connection-secret");

        assertThatThrownBy(() -> connectionFailureProvider.exchange("connection-code"))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatIdentityProvider
                        .ProviderUnavailableException.class)
                .hasMessage("wechat identity provider unavailable")
                .hasMessageNotContaining("connection-secret")
                .hasMessageNotContaining("connection-code")
                .hasNoCause();

        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
        var malformedJsonProvider = new WechatCodeSessionIdentityProvider(
                clientBuilder.build(), "test-app-id", "json-secret");
        server.expect(requestTo(
                        "https://api.weixin.qq.com/sns/jscode2session"
                                + "?appid=test-app-id"
                                + "&secret=json-secret"
                                + "&js_code=json-code"
                                + "&grant_type=authorization_code"))
                .andRespond(withSuccess("{not-json-containing-json-secret}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> malformedJsonProvider.exchange("json-code"))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatIdentityProvider
                        .ProviderUnavailableException.class)
                .hasMessage("wechat identity provider unavailable")
                .hasMessageNotContaining("json-secret")
                .hasMessageNotContaining("json-code")
                .hasNoCause();
        server.verify();
    }
}
