package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.application.PlanRepository;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.rules.domain.PlanGenerationEngine;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.rules.domain.PlanValidationEngine;
import com.aifitness.assistant.rules.infrastructure.ClasspathPlanRulePolicyLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Configuration
@Profile({"local", "test"})
public class LocalPlanConfiguration {

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
            @Value("${fitness.ai.enabled:false}") boolean aiEnabled) {
        return new PlanCandidateService(
                profiles, templates, exercises, generator, validator, policy, clock, aiEnabled);
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
            PlanRepository repository, PlanCandidateService candidates, Clock clock) {
        return new PlanVersionService(repository, new RulesPlanPolicy(candidates), clock);
    }
}
