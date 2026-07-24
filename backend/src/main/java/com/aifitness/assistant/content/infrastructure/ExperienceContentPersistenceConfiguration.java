package com.aifitness.assistant.content.infrastructure;

import com.aifitness.assistant.content.application.ContentCatalogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("staging-experience")
public class ExperienceContentPersistenceConfiguration {

    @Bean
    JdbcContentCatalogPublisher jdbcContentCatalogPublisher(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcContentCatalogPublisher(dataSource, objectMapper);
    }

    @Bean
    ApplicationRunner publishValidatedContent(
            JdbcContentCatalogPublisher publisher, ContentCatalogRepository catalogs) {
        return ignored -> publisher.publish(catalogs.exercises(), catalogs.templates());
    }
}
