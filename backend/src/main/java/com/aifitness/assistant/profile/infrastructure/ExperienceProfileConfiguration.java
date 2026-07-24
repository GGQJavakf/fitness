package com.aifitness.assistant.profile.infrastructure;

import com.aifitness.assistant.profile.application.ProfileRepository;
import com.aifitness.assistant.profile.application.ProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("staging-experience")
public class ExperienceProfileConfiguration {

    @Bean
    ProfileRepository experienceProfileRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcProfileRepository(dataSource, objectMapper);
    }

    @Bean
    ProfileService experienceProfileService(ProfileRepository repository) {
        return new ProfileService(repository);
    }
}
