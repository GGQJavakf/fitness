package com.aifitness.assistant.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.infrastructure.LocalIdentityConfiguration;
import com.aifitness.assistant.identity.infrastructure.LocalWechatIdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalIdentityConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
            .withUserConfiguration(LocalIdentityConfiguration.class)
            .withPropertyValues("fitness.auth.local-substitute-enabled=true");

    @Test
    void startsLocalSubstituteOnlyOnLoopbackBinding() {
        contextRunner.withPropertyValues("server.address=127.0.0.1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WechatLoginService.class);
        });
    }

    @Test
    void failsClosedWhenLocalSubstituteWouldBindToAllInterfaces() {
        contextRunner.withPropertyValues("server.address=0.0.0.0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("local identity substitute requires an explicit loopback server.address");
        });
    }

    @Test
    void remainsDisabledWithoutExplicitFeatureFlag() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withUserConfiguration(LocalIdentityConfiguration.class)
                .withPropertyValues(
                        "fitness.auth.local-substitute-enabled=false", "server.address=127.0.0.1")
                .run(context -> assertThat(context).doesNotHaveBean(WechatLoginService.class));
    }

    @Test
    void localProviderKeepsSameCodeStableAndDifferentCodesIsolatedWithoutExposingCodes() {
        LocalWechatIdentityProvider provider = new LocalWechatIdentityProvider();

        String first = provider.exchange("test-code-a").subject();
        String repeated = provider.exchange("test-code-a").subject();
        String second = provider.exchange("test-code-b").subject();

        assertThat(first).isEqualTo(repeated).isNotEqualTo(second).doesNotContain("test-code-a");
    }
}
