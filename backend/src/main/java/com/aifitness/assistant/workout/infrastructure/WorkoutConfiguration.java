package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutCompletionService;
import com.aifitness.assistant.workout.application.WorkoutHistoryQueryService;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.application.SyncConflictRepository;
import com.aifitness.assistant.workout.application.WorkoutSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.profile.application.ProfileService;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test", "staging-experience"})
public class WorkoutConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.workout", name = "repository", havingValue = "memory", matchIfMissing = true)
    WorkoutSessionRepository workoutSessionRepository() {
        return new InMemoryWorkoutSessionRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "mysql")
    WorkoutSessionRepository jdbcWorkoutSessionRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcWorkoutSessionRepository(dataSource, objectMapper);
    }

    @Bean
    WorkoutSessionService workoutSessionService(
            WorkoutSessionRepository sessions,
            PlanWorkoutSnapshotQuery plans,
            Clock clock) {
        return new WorkoutSessionService(sessions, plans, clock, UUID::randomUUID);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.workout", name = "repository", havingValue = "memory", matchIfMissing = true)
    WorkoutSetRepository workoutSetRepository(WorkoutSessionRepository sessions) {
        return new InMemoryWorkoutSetRepository(sessions);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "mysql")
    WorkoutSetRepository jdbcWorkoutSetRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcWorkoutSetRepository(dataSource, objectMapper);
    }

    @Bean
    WorkoutSetService.InputPolicy workoutInputPolicy(
            @Value("${fitness.workout.input-policy.max-weight-kg:1000}") BigDecimal maxWeightKg,
            @Value("${fitness.workout.input-policy.max-reps:500}") int maxReps,
            @Value("${fitness.workout.input-policy.large-change-ratio:2.0}") BigDecimal largeChangeRatio,
            @Value("${fitness.workout.input-policy.large-change-kg:50}") BigDecimal largeChangeKg) {
        return new WorkoutSetService.InputPolicy(maxWeightKg, maxReps, largeChangeRatio, largeChangeKg);
    }

    @Bean
    WorkoutSetService workoutSetService(
            WorkoutSetRepository sets, WorkoutSetService.InputPolicy policy, Clock clock) {
        return new WorkoutSetService(sets, policy, clock, UUID::randomUUID);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "memory", matchIfMissing = true)
    SyncConflictRepository syncConflictRepository(Clock clock) {
        return new InMemorySyncConflictRepository(clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "mysql")
    SyncConflictRepository jdbcSyncConflictRepository(DataSource dataSource, ObjectMapper objectMapper, Clock clock) {
        return new JdbcSyncConflictRepository(dataSource, objectMapper, clock);
    }

    @Bean
    WorkoutSyncService workoutSyncService(
            WorkoutSetService sets, WorkoutSetRepository repository, SyncConflictRepository conflicts, Clock clock) {
        return new WorkoutSyncService(sets, repository, conflicts, clock, UUID::randomUUID);
    }

    @Bean
    WorkoutCompletionService workoutCompletionService(
            WorkoutSessionRepository sessions, WorkoutSetRepository sets, Clock clock) {
        return new WorkoutCompletionService(sessions, sets, clock);
    }

    @Bean
    WorkoutHistoryQueryService workoutHistoryQueryService(
            WorkoutSessionRepository sessions, WorkoutSetRepository sets) {
        return new WorkoutHistoryQueryService(sessions, sets);
    }

    @Bean
    ExerciseReplacementService exerciseReplacementService(
            ExerciseQueryService exercises, ProfileService profiles, WorkoutSessionRepository sessions) {
        return new ExerciseReplacementService(exercises, profiles, sessions);
    }
}
