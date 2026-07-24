package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
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
}
