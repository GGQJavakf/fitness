package com.aifitness.assistant.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
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
                        MediaType.APPLICATION_JSON));

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
}
