package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.application.PlanExerciseOptionService;
import com.aifitness.assistant.plan.application.PlanRepository;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.plan.application.WarningConfirmationStore;
import com.aifitness.assistant.plan.application.InMemoryWarningConfirmationStore;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.rules.domain.PlanGenerationEngine;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.rules.domain.PlanValidationEngine;
import com.aifitness.assistant.rules.infrastructure.ClasspathPlanRulePolicyLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Configuration
@Profile({"local", "test", "staging-experience"})
public class PlanConfiguration {

    @Bean
    PlanRulePolicy planRulePolicy(ObjectMapper objectMapper) {
        return ClasspathPlanRulePolicyLoader.load(objectMapper);
    }

    @Bean
    PlanValidationEngine planValidationEngine(PlanRulePolicy policy) {
        return new PlanValidationEngine(policy);
    }

    @Bean
    PlanGenerationEngine planGenerationEngine(PlanValidationEngine validator) {
        return new PlanGenerationEngine(validator);
    }

    @Bean
    PlanCandidateService planCandidateService(
            ProfileService profiles,
            TemplateQueryService templates,
            ExerciseQueryService exercises,
            PlanGenerationEngine generator,
            PlanValidationEngine validator,
            PlanRulePolicy policy,
            Clock clock,
            @Value("${fitness.plan.candidate-cache-capacity:512}") int candidateCacheCapacity) {
        return new PlanCandidateService(
                profiles, templates, exercises, generator, validator, policy, clock, candidateCacheCapacity);
    }

    @Bean
    @Profile({"local", "test"})
    WarningConfirmationStore localWarningConfirmationStore(Clock clock) {
        return new InMemoryWarningConfirmationStore(clock);
    }

    @Bean
    @Profile("staging-experience")
    WarningConfirmationStore sharedWarningConfirmationStore(DataSource dataSource, Clock clock) {
        return new JdbcWarningConfirmationStore(dataSource, clock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.plan", name = "repository", havingValue = "memory", matchIfMissing = true)
    PlanRepository planRepository() {
        return new InMemoryPlanRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.plan", name = "repository", havingValue = "mysql")
    PlanRepository mysqlPlanRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcPlanRepository(dataSource, objectMapper, code -> UUID.nameUUIDFromBytes(
                ("ai-fitness-exercise:" + code).getBytes(StandardCharsets.UTF_8)));
    }

    @Bean
    PlanVersionService planVersionService(
            PlanRepository repository,
            PlanCandidateService candidates,
            Clock clock,
            WarningConfirmationStore warningConfirmations) {
        return new PlanVersionService(
                repository, new RulesPlanPolicy(candidates), clock, warningConfirmations);
    }

    @Bean
    PlanExerciseOptionService planExerciseOptionService(
            PlanVersionService plans,
            TemplateQueryService templates,
            ExerciseQueryService exercises,
            ProfileService profiles) {
        return new PlanExerciseOptionService(plans, templates, exercises, profiles);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.plan", name = "repository", havingValue = "memory", matchIfMissing = true)
    PlanWorkoutSnapshotQuery domainPlanWorkoutSnapshotQuery(
            PlanVersionService plans, ExerciseQueryService exercises) {
        return new DomainPlanWorkoutSnapshotQuery(plans, exercises);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.plan", name = "repository", havingValue = "mysql")
    PlanWorkoutSnapshotQuery jdbcPlanWorkoutSnapshotQuery(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcPlanWorkoutSnapshotQuery(dataSource, objectMapper);
    }
}
