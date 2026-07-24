package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.progression.application.RecommendationRepository;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.application.ExerciseTrendQuery;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.workout.application.WorkoutCompletionObserver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
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

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.workout", name = "repository", havingValue = "memory", matchIfMissing = true)
    CompletedWorkoutProgressionObserver.HistoricalFactProvider inMemoryProgressionHistory() {
        return (user, exercise, currentFacts) -> currentFacts;
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "mysql")
    CompletedWorkoutProgressionObserver.HistoricalFactProvider jdbcProgressionHistory(DataSource dataSource) {
        return new JdbcHistoricalProgressionFactProvider(dataSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.progression", name = "enabled", havingValue = "true", matchIfMissing = true)
    WorkoutCompletionObserver progressionOnWorkoutCompletion(
            RecommendationService recommendations,
            PlanVersionService plans,
            ProfileService profiles,
            CompletedWorkoutProgressionObserver.HistoricalFactProvider history,
            ObjectMapper objectMapper,
            Clock clock) {
        return new CompletedWorkoutProgressionObserver(recommendations, (user, exerciseEquipment) -> {
            try {
                return profiles.getEquipment(user).items().stream()
                        .filter(item -> exerciseEquipment.contains(item.equipmentType()))
                        .map(item -> item.minIncrement().stripTrailingZeros()).distinct().sorted().toList();
            } catch (ProfileService.ProfileNotFoundException exception) {
                return List.of();
            }
        }, history, (user, session, exercise) -> plans
                        .getVersion(user, session.planId(), session.planVersionNumber())
                        .plan().isTargetWeightLocked(exercise.exerciseCode()),
                objectMapper, clock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.progression", name = "repository", havingValue = "memory", matchIfMissing = true)
    ExerciseTrendQuery exerciseTrendQuery() {
        return (user, exerciseCode) -> new ExerciseTrendQuery.Trend(exerciseCode, "KG", java.util.List.of());
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.progression", name = "repository", havingValue = "mysql")
    ExerciseTrendQuery jdbcExerciseTrendQuery(DataSource dataSource) {
        return new JdbcExerciseTrendQuery(dataSource);
    }
}
