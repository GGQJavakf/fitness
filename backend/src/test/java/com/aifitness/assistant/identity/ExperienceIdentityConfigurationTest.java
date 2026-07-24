package com.aifitness.assistant.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import com.aifitness.assistant.identity.application.IdentityRepository;
import com.aifitness.assistant.identity.application.SessionStore;
import com.aifitness.assistant.identity.application.SubjectProtector;
import com.aifitness.assistant.identity.application.UserAccessRevocation;
import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.infrastructure.ExperienceIdentityConfiguration;
import com.aifitness.assistant.identity.api.AuthController;
import com.aifitness.assistant.identity.api.AuthExceptionHandler;
import com.aifitness.assistant.identity.api.AuthenticationFilter;
import com.aifitness.assistant.identity.api.IdentityWebConfiguration;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

class ExperienceIdentityConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(
                    ExperienceIdentityConfiguration.class, TestDataSourceConfiguration.class)
            .withPropertyValues("spring.profiles.active=staging-experience");

    @Test
    void createsTheRealWechatProviderOnlyWhenCredentialsComeFromConfiguration() {
        runner.withPropertyValues(
                        "fitness.auth.wechat.app-id=test-app-id",
                        "fitness.auth.wechat.app-secret=test-app-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WechatIdentityProvider.class);
                    assertThat(context).hasSingleBean(SubjectProtector.class);
                    assertThat(context).hasSingleBean(IdentityRepository.class);
                    assertThat(context).hasSingleBean(SessionStore.class);
                    assertThat(context).hasSingleBean(WechatIdentityResolver.class);
                    assertThat(context).hasSingleBean(UserAccessRevocation.class);
                    assertThat(context).hasSingleBean(WechatLoginService.class);
                });
    }

    @Test
    void failsClosedWhenWechatCredentialsAreMissing() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void identityHttpBoundaryIsEnabledForTheExperienceProfile() {
        assertThat(java.util.List.of(
                        AuthController.class,
                        AuthenticationFilter.class,
                        AuthExceptionHandler.class,
                        IdentityWebConfiguration.class))
                .allSatisfy(type -> assertThat(type.getAnnotation(Profile.class).value())
                        .contains("staging-experience"));
    }

    @Test
    void experienceConfigurationRequiresDeploymentInjectedSecretsAndMysql() throws Exception {
        String configuration;
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/application-staging-experience.yaml"))) {
            configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(configuration).contains(
                "${FITNESS_DB_URL}",
                "${FITNESS_DB_USERNAME}",
                "${FITNESS_DB_PASSWORD}",
                "${WECHAT_APP_ID}",
                "${WECHAT_APP_SECRET}",
                "repository: mysql",
                "include-message: never",
                "include-stacktrace: never");
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDataSourceConfiguration {

        @Bean
        DataSource dataSource() {
            return org.mockito.Mockito.mock(DataSource.class);
        }
    }
}
