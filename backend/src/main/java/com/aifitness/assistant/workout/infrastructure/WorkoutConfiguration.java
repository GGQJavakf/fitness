package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutCompletionService;
import com.aifitness.assistant.workout.application.WorkoutCompletionObserver;
import com.aifitness.assistant.workout.application.WorkoutCompletionOutbox;
import com.aifitness.assistant.workout.application.WorkoutCompletionOutboxProcessor;
import com.aifitness.assistant.workout.application.WorkoutHistoryQueryService;
import com.aifitness.assistant.workout.application.WorkoutHistoryRepository;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.application.SyncConflictRepository;
import com.aifitness.assistant.workout.application.WorkoutSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine;
import com.aifitness.assistant.workout.application.WorkoutWarmupPrescriptionService;
import java.time.Clock;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test", "staging-experience"})
@EnableScheduling
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
            Clock clock,
            WorkoutWarmupPrescriptionService warmups) {
        return new WorkoutSessionService(sessions, plans, clock, UUID::randomUUID, warmups);
    }

    @Bean
    WorkoutWarmupPrescriptionEngine workoutWarmupPrescriptionEngine(PlanRulePolicy policy) {
        return new WorkoutWarmupPrescriptionEngine(policy);
    }

    @Bean
    WorkoutWarmupPrescriptionService workoutWarmupPrescriptionService(
            ExerciseQueryService exercises,
            ProfileService profiles,
            WorkoutWarmupPrescriptionEngine engine) {
        return new WorkoutWarmupPrescriptionService(exercises, profiles, engine);
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
            WorkoutSetService sets,
            WorkoutSetRepository repository,
            WorkoutSessionRepository sessions,
            SyncConflictRepository conflicts,
            Clock clock) {
        return new WorkoutSyncService(sets, repository, sessions, conflicts, clock, UUID::randomUUID);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.workout", name = "repository", havingValue = "memory", matchIfMissing = true)
    WorkoutCompletionOutbox workoutCompletionOutbox() {
        return new InMemoryWorkoutCompletionOutbox();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "mysql")
    WorkoutCompletionOutbox jdbcWorkoutCompletionOutbox(DataSource dataSource) {
        return new JdbcWorkoutCompletionOutbox(dataSource);
    }

    @Bean
    WorkoutCompletionService workoutCompletionService(
            WorkoutSessionRepository sessions, WorkoutSetRepository sets, Clock clock,
            WorkoutCompletionOutbox outbox) {
        return new WorkoutCompletionService(sessions, sets, clock, outbox);
    }

    @Bean
    WorkoutCompletionOutboxProcessor workoutCompletionOutboxProcessor(
            WorkoutCompletionOutbox outbox, WorkoutSessionRepository sessions, WorkoutSetRepository sets,
            List<WorkoutCompletionObserver> observers, Clock clock,
            @Value("${fitness.workout.outbox.lease-seconds:30}") long leaseSeconds,
            @Value("${fitness.workout.outbox.retry-seconds:5}") long retrySeconds) {
        return new WorkoutCompletionOutboxProcessor(outbox, sessions, sets, observers, clock,
                Duration.ofSeconds(leaseSeconds), Duration.ofSeconds(retrySeconds));
    }

    @Bean
    @Profile("!test")
    WorkoutCompletionOutboxWorker workoutCompletionOutboxWorker(WorkoutCompletionOutboxProcessor processor) {
        return new WorkoutCompletionOutboxWorker(processor);
    }

    @Bean
    WorkoutHistoryQueryService workoutHistoryQueryService(
            WorkoutSessionRepository sessions, WorkoutSetRepository sets, WorkoutHistoryRepository history) {
        return new WorkoutHistoryQueryService(sessions, sets, history);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.workout", name = "repository", havingValue = "memory", matchIfMissing = true)
    WorkoutHistoryRepository workoutHistoryRepository(
            WorkoutSessionRepository sessions, WorkoutSetRepository sets) {
        return new InMemoryWorkoutHistoryRepository(sessions, sets);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "mysql")
    WorkoutHistoryRepository jdbcWorkoutHistoryRepository(DataSource dataSource) {
        return new JdbcWorkoutHistoryRepository(dataSource);
    }

    @Bean
    ExerciseReplacementService exerciseReplacementService(
            ExerciseQueryService exercises, ProfileService profiles, WorkoutSessionRepository sessions,
            PlanRulePolicy policy) {
        return new ExerciseReplacementService(exercises, profiles, sessions, policy);
    }
}
