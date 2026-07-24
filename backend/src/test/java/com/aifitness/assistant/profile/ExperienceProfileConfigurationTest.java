package com.aifitness.assistant.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.profile.api.ProfileController;
import com.aifitness.assistant.profile.api.ProfileExceptionHandler;
import com.aifitness.assistant.profile.application.ProfileRepository;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.infrastructure.ExperienceProfileConfiguration;
import com.aifitness.assistant.profile.infrastructure.JdbcProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

class ExperienceProfileConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ExperienceProfileConfiguration.class, TestDependencies.class)
            .withPropertyValues("spring.profiles.active=staging-experience");

    @Test
    void experienceProfileUsesTheMysqlRepository() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProfileRepository.class);
            assertThat(context).hasSingleBean(ProfileService.class);
            assertThat(context.getBean(ProfileRepository.class))
                    .isInstanceOf(JdbcProfileRepository.class);
        });
    }

    @Test
    void profileHttpBoundaryIsEnabledForTheExperienceProfile() {
        assertThat(java.util.List.of(ProfileController.class, ProfileExceptionHandler.class))
                .allSatisfy(type -> assertThat(type.getAnnotation(Profile.class).value())
                        .contains("staging-experience"));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        DataSource dataSource() {
            return org.mockito.Mockito.mock(DataSource.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
