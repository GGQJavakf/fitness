package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.progression.application.RecommendationRepository;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test", "staging-experience"})
public class RecommendationConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.progression", name = "repository", havingValue = "memory", matchIfMissing = true)
    RecommendationRepository recommendationRepository() {
        return new InMemoryRecommendationRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.progression", name = "repository", havingValue = "mysql")
    RecommendationRepository jdbcRecommendationRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcRecommendationRepository(dataSource, objectMapper);
    }

    @Bean
    RecommendationService recommendationService(
            RecommendationRepository recommendations, PlanVersionService plans, Clock clock) {
        return new RecommendationService(recommendations, plans, clock, UUID::randomUUID);
    }
}
