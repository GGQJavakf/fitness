package com.aifitness.assistant.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.content.api.ContentExceptionHandler;
import com.aifitness.assistant.content.api.ExerciseController;
import com.aifitness.assistant.content.api.TemplateController;
import com.aifitness.assistant.content.application.ContentCatalogRepository;
import com.aifitness.assistant.content.infrastructure.ContentConfiguration;
import com.aifitness.assistant.content.infrastructure.ExperienceContentPersistenceConfiguration;
import com.aifitness.assistant.content.infrastructure.JdbcContentCatalogPublisher;
import com.aifitness.assistant.plan.api.PlanCandidateController;
import com.aifitness.assistant.plan.api.PlanController;
import com.aifitness.assistant.plan.api.PlanExceptionHandler;
import com.aifitness.assistant.plan.application.PlanRepository;
import com.aifitness.assistant.plan.infrastructure.JdbcPlanRepository;
import com.aifitness.assistant.plan.infrastructure.PlanConfiguration;
import com.aifitness.assistant.profile.application.ProfileRepository;
import com.aifitness.assistant.profile.application.ProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

class ExperienceContentPlanConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ContentConfiguration.class,
                    ExperienceContentPersistenceConfiguration.class,
                    PlanConfiguration.class,
                    TestDependencies.class)
            .withPropertyValues(
                    "spring.profiles.active=staging-experience",
                    "fitness.content.environment=staging-experience",
                    "fitness.plan.repository=mysql",
                    "fitness.ai.enabled=false");

    @Test
    void experienceProfilePublishesValidatedContentAndUsesMysqlPlans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ContentCatalogRepository.class);
            assertThat(context).hasSingleBean(JdbcContentCatalogPublisher.class);
            assertThat(context).hasSingleBean(ApplicationRunner.class);
            assertThat(context).hasSingleBean(PlanRepository.class);
            assertThat(context.getBean(PlanRepository.class)).isInstanceOf(JdbcPlanRepository.class);
        });
    }

    @Test
    void contentAndPlanHttpBoundariesAreEnabledForTheExperienceProfile() {
        assertThat(java.util.List.of(
                        ExerciseController.class,
                        TemplateController.class,
                        ContentExceptionHandler.class,
                        PlanCandidateController.class,
                        PlanController.class,
                        PlanExceptionHandler.class))
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

        @Bean
        ProfileRepository profileRepository() {
            return org.mockito.Mockito.mock(ProfileRepository.class);
        }

        @Bean
        ProfileService profileService(ProfileRepository profiles) {
            return new ProfileService(profiles);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
