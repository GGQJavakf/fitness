package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.content.application.ContentCatalogRepository;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.plan.application.CandidateCommitReceiptStore;
import com.aifitness.assistant.plan.application.CandidateCommitService;
import com.aifitness.assistant.plan.application.CandidateCommitTransaction;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.application.PlanExerciseOptionService;
import com.aifitness.assistant.plan.application.PlanRepository;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.plan.application.WarningConfirmationStore;
import com.aifitness.assistant.plan.application.InMemoryWarningConfirmationStore;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.SystemPlanPresetCatalog;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
            SystemPlanPresetCatalog presets,
            Clock clock,
            @Value("${fitness.plan.candidate-cache-capacity:512}") int candidateCacheCapacity) {
        return new PlanCandidateService(
                profiles, templates, exercises, generator, validator, policy, presets, clock, candidateCacheCapacity);
    }

    @Bean
    SystemPlanPresetCatalog systemPlanPresetCatalog(
            ObjectMapper objectMapper,
            ContentCatalogRepository contentCatalogRepository,
            @Value("${fitness.content.environment:local}") String environment) {
        SystemPlanPresetCatalog presets = ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.fromExternalName(environment));
        return validateNoJumpPresetExercises(presets, contentCatalogRepository.exercises());
    }

    static SystemPlanPresetCatalog validateNoJumpPresetExercises(
            SystemPlanPresetCatalog presets,
            ExerciseCatalog exerciseCatalog) {
        Objects.requireNonNull(presets, "system plan preset catalog must not be null");
        Objects.requireNonNull(exerciseCatalog, "exercise catalog must not be null");

        Map<String, ExerciseCatalog.Exercise> exercisesByCode = new HashMap<>();
        for (ExerciseCatalog.Exercise exercise : exerciseCatalog.exercises()) {
            if (exercisesByCode.putIfAbsent(exercise.code(), exercise) != null) {
                throw new IllegalArgumentException(
                        "ExerciseCatalog contains duplicate exercise code: " + exercise.code());
            }
        }

        presets.presets().stream()
                .filter(preset -> preset.plan().movementImpactConstraint()
                        == PlanDraft.MovementImpactConstraint.NO_JUMP)
                .forEach(preset -> preset.plan().days().forEach(day -> day.exercises().forEach(reference -> {
                    ExerciseCatalog.Exercise exercise = exercisesByCode.get(reference.exerciseCode());
                    if (exercise == null) {
                        throw new IllegalArgumentException(
                                "NO_JUMP system preset " + preset.code()
                                        + " references exercise missing from ExerciseCatalog: "
                                        + reference.exerciseCode());
                    }
                    if (exercise.impactClass() != ExerciseCatalog.ImpactClass.NO_JUMP) {
                        String actualImpactClass = exercise.impactClass() == null
                                ? "UNKNOWN"
                                : exercise.impactClass().name();
                        throw new IllegalArgumentException(
                                "NO_JUMP system preset " + preset.code()
                                        + " requires ExerciseCatalog impactClass=NO_JUMP for exercise "
                                        + reference.exerciseCode() + ", but was " + actualImpactClass);
                    }
                })));
        return presets;
    }

    @Bean
    @Profile({"local", "test"})
    InMemoryWarningConfirmationStore localWarningConfirmationStore(Clock clock) {
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
    InMemoryPlanRepository planRepository() {
        return new InMemoryPlanRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.plan", name = "repository", havingValue = "mysql")
    PlanRepository mysqlPlanRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcPlanRepository(dataSource, objectMapper, code -> UUID.nameUUIDFromBytes(
                ("ai-fitness-exercise:" + code).getBytes(StandardCharsets.UTF_8)));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.plan", name = "repository", havingValue = "memory", matchIfMissing = true)
    InMemoryCandidateCommitReceiptStore inMemoryCandidateCommitReceiptStore() {
        return new InMemoryCandidateCommitReceiptStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.plan", name = "repository", havingValue = "mysql")
    CandidateCommitReceiptStore jdbcCandidateCommitReceiptStore(DataSource dataSource) {
        return new JdbcCandidateCommitReceiptStore(dataSource);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "fitness.plan", name = "repository", havingValue = "memory", matchIfMissing = true)
    CandidateCommitTransaction inMemoryCandidateCommitTransaction(
            InMemoryPlanRepository plans,
            InMemoryWarningConfirmationStore warnings,
            InMemoryCandidateCommitReceiptStore receipts) {
        return new InMemoryCandidateCommitTransaction(plans, warnings, receipts);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fitness.plan", name = "repository", havingValue = "mysql")
    CandidateCommitTransaction jdbcCandidateCommitTransaction(DataSource dataSource) {
        return new JdbcCandidateCommitTransaction(dataSource);
    }

    @Bean
    PlanVersionService.PlanPolicy planPolicy(PlanCandidateService candidates) {
        return new RulesPlanPolicy(candidates);
    }

    @Bean
    PlanVersionService planVersionService(
            PlanRepository repository,
            PlanVersionService.PlanPolicy policy,
            Clock clock,
            WarningConfirmationStore warningConfirmations) {
        return new PlanVersionService(
                repository, policy, clock, warningConfirmations);
    }

    @Bean
    CandidateCommitService candidateCommitService(
            PlanRepository repository,
            PlanVersionService.PlanPolicy policy,
            WarningConfirmationStore warningConfirmations,
            CandidateCommitReceiptStore receipts,
            CandidateCommitTransaction transactions,
            Clock clock) {
        return new CandidateCommitService(
                repository, policy, warningConfirmations, receipts, transactions, clock);
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
