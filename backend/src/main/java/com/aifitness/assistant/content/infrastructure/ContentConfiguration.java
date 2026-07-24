package com.aifitness.assistant.content.infrastructure;

import com.aifitness.assistant.content.application.ContentCatalogRepository;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.content.application.UserEquipmentProvider;
import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.profile.application.ProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"local", "test", "staging-experience"})
public class ContentConfiguration {

    @Bean
    ContentCatalogRepository contentCatalogRepository(ObjectMapper objectMapper) {
        return new ClasspathContentCatalogRepository(objectMapper);
    }

    @Bean
    UserEquipmentProvider userEquipmentProvider(ProfileRepository profiles) {
        return new ProfileUserEquipmentProvider(profiles);
    }

    @Bean
    ExerciseQueryService exerciseQueryService(
            ContentCatalogRepository catalogs, UserEquipmentProvider equipment,
            @Value("${fitness.content.environment:local}") String environment) {
        return new ExerciseQueryService(catalogs, equipment, ContentEnvironment.fromExternalName(environment));
    }

    @Bean
    TemplateQueryService templateQueryService(
            ContentCatalogRepository catalogs, ExerciseQueryService exercises,
            @Value("${fitness.content.environment:local}") String environment) {
        return new TemplateQueryService(catalogs, exercises, ContentEnvironment.fromExternalName(environment));
    }
}
