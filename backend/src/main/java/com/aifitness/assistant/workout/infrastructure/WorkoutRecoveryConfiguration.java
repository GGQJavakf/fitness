package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.workout.application.WorkoutRecoveryCheckService;
import com.aifitness.assistant.workout.application.WorkoutRecoveryFactQuery;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test", "staging-experience"})
public class WorkoutRecoveryConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.workout", name = "repository", havingValue = "memory", matchIfMissing = true)
    WorkoutRecoveryFactQuery repositoryWorkoutRecoveryFactQuery(
            WorkoutSessionRepository sessions, WorkoutSetRepository sets) {
        return new RepositoryWorkoutRecoveryFactQuery(sessions, sets);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.workout", name = "repository", havingValue = "mysql")
    WorkoutRecoveryFactQuery jdbcWorkoutRecoveryFactQuery(DataSource dataSource) {
        return new JdbcWorkoutRecoveryFactQuery(dataSource);
    }

    @Bean
    ContentWorkoutMuscleCatalog contentWorkoutMuscleCatalog(
            ExerciseQueryService exercises, ObjectMapper objectMapper) {
        return new ContentWorkoutMuscleCatalog(exercises, objectMapper);
    }

    @Bean
    WorkoutRecoveryCheckService workoutRecoveryCheckService(
            PlanWorkoutSnapshotQuery plans,
            WorkoutRecoveryFactQuery facts,
            ContentWorkoutMuscleCatalog muscles,
            PlanRulePolicy policy,
            Clock clock) {
        return new WorkoutRecoveryCheckService(plans, facts, muscles, policy, clock);
    }
}
