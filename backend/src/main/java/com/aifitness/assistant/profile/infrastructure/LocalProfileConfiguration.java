package com.aifitness.assistant.profile.infrastructure;

import com.aifitness.assistant.profile.application.ProfileRepository;
import com.aifitness.assistant.profile.application.ProfileService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"local", "test"})
public class LocalProfileConfiguration {

    @Bean
    ProfileRepository profileRepository() {
        return new InMemoryProfileRepository();
    }

    @Bean
    ProfileService profileService(ProfileRepository repository) {
        return new ProfileService(repository);
    }
}
