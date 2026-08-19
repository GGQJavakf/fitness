package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutRecoveryAssessmentQuery;
import com.aifitness.assistant.workout.application.WorkoutRecoveryConfirmationStore;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSessionStartService;
import com.aifitness.assistant.workout.application.WorkoutSessionStartTransaction;
import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test", "staging-experience"})
public class WorkoutSessionStartConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.workout", name = "repository", havingValue = "memory", matchIfMissing = true)
    WorkoutRecoveryConfirmationStore inMemoryWorkoutRecoveryConfirmationStore() {
        return new InMemoryWorkoutRecoveryConfirmationStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "mysql")
    WorkoutRecoveryConfirmationStore jdbcWorkoutRecoveryConfirmationStore(DataSource dataSource) {
        return new JdbcWorkoutRecoveryConfirmationStore(dataSource);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.workout", name = "repository", havingValue = "memory", matchIfMissing = true)
    WorkoutSessionStartTransaction inMemoryWorkoutSessionStartTransaction() {
        return new InMemoryWorkoutSessionStartTransaction();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "mysql")
    WorkoutSessionStartTransaction jdbcWorkoutSessionStartTransaction(DataSource dataSource) {
        return new JdbcWorkoutSessionStartTransaction(dataSource);
    }

    @Bean
    WorkoutSessionStartService workoutSessionStartService(
            WorkoutSessionService sessions,
            WorkoutSessionRepository repository,
            WorkoutSetRepository sets,
            WorkoutRecoveryAssessmentQuery recovery,
            WorkoutRecoveryConfirmationStore confirmations,
            WorkoutSessionStartTransaction transactions,
            Clock clock,
            @Value("${fitness.workout.recovery.confirmation-ttl-seconds:300}") long confirmationTtlSeconds) {
        return new WorkoutSessionStartService(
                sessions, repository, sets, recovery, confirmations, transactions, clock,
                Duration.ofSeconds(confirmationTtlSeconds));
    }
}
