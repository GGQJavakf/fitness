package com.aifitness.assistant.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.privacy.api.LocalPrivacyDeletionProcessingController;
import com.aifitness.assistant.privacy.api.PrivacyController;
import com.aifitness.assistant.privacy.api.PrivacyExceptionHandler;
import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import com.aifitness.assistant.privacy.application.PrivacyExportRepository;
import com.aifitness.assistant.privacy.application.PrivacyRepository;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import com.aifitness.assistant.privacy.infrastructure.ExperiencePrivacyConfiguration;
import com.aifitness.assistant.privacy.infrastructure.JdbcPrivacyAudit;
import com.aifitness.assistant.privacy.infrastructure.JdbcPrivacyDataReader;
import com.aifitness.assistant.privacy.infrastructure.JdbcPrivacyExportRepository;
import com.aifitness.assistant.privacy.infrastructure.JdbcPrivacyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

class ExperiencePrivacyConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ExperiencePrivacyConfiguration.class, TestDependencies.class)
            .withPropertyValues("spring.profiles.active=staging-experience");

    @Test
    void experienceProfileUsesPersistentOwnerScopedPrivacyAdapters() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PrivacyRepository.class)).isInstanceOf(JdbcPrivacyRepository.class);
            assertThat(context.getBean(PrivacyExportRepository.class))
                    .isInstanceOf(JdbcPrivacyExportRepository.class);
            assertThat(context.getBean(PrivacyDataPort.class)).isInstanceOf(JdbcPrivacyDataReader.class);
            assertThat(context.getBean(PrivacyRequestService.AuditPort.class))
                    .isInstanceOf(JdbcPrivacyAudit.class);
            assertThat(context).hasSingleBean(PrivacyRequestService.class);
            assertThat(context).doesNotHaveBean(PrivacyDeletionWorker.class);
        });
    }

    @Test
    void userPrivacyEndpointsAreEnabledButLocalProcessingHookIsNot() {
        assertThat(PrivacyController.class.getAnnotation(Profile.class).value())
                .contains("staging-experience");
        assertThat(PrivacyExceptionHandler.class.getAnnotation(Profile.class).value())
                .contains("staging-experience");
        assertThat(LocalPrivacyDeletionProcessingController.class.getAnnotation(Profile.class).value())
                .containsExactlyInAnyOrder("local", "test");
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean DataSource dataSource() { return org.mockito.Mockito.mock(DataSource.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean WechatIdentityResolver identities() {
            return org.mockito.Mockito.mock(WechatIdentityResolver.class);
        }
    }
}
