package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Checks a selected plan day against recent completed, effective workout facts. */
public final class WorkoutRecoveryCheckService implements WorkoutRecoveryAssessmentQuery {
    private final PlanWorkoutSnapshotQuery plans;
    private final WorkoutRecoveryFactQuery facts;
    private final WorkoutMuscleCatalog muscles;
    private final PlanRulePolicy policy;
    private final Clock clock;

    public WorkoutRecoveryCheckService(
            PlanWorkoutSnapshotQuery plans,
            WorkoutRecoveryFactQuery facts,
            WorkoutMuscleCatalog muscles,
            PlanRulePolicy policy,
            Clock clock) {
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.facts = Objects.requireNonNull(facts, "facts must not be null");
        this.muscles = Objects.requireNonNull(muscles, "muscles must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public WorkoutRecoveryAssessment check(
            AuthenticatedUserId authenticatedUser,
            UUID planId,
            int planVersionNumber,
            String trainingDayCode) {
        Objects.requireNonNull(authenticatedUser, "authenticated user must not be null");
        Objects.requireNonNull(planId, "plan id must not be null");
        if (planVersionNumber < 1) {
            throw new IllegalArgumentException("plan version number must be positive");
        }
        if (trainingDayCode == null || trainingDayCode.isBlank() || trainingDayCode.length() > 128) {
            throw new IllegalArgumentException("training day code must not be blank");
        }

        UUID userId = authenticatedUser.value();
        PlanWorkoutSnapshotQuery.PlanDaySource selected = plans.load(
                userId, planId, planVersionNumber, trainingDayCode.trim());
        assertSelectedReference(selected, planId, planVersionNumber, trainingDayCode.trim());
        Set<String> targetMuscles = resolveAll(selected.exercises());

        int minimumHours = policy.balance().minimumRecoveryHoursBetweenPrimaryMuscleSessions();
        Instant checkedAt = clock.instant();
        Instant completedAfter = checkedAt.minus(minimumHours, ChronoUnit.HOURS);
        List<WorkoutRecoveryAssessment.CompletedMuscleFact> completedFacts = facts
                .findCompletedExerciseFacts(userId, completedAfter)
                .stream()
                .map(fact -> new WorkoutRecoveryAssessment.CompletedMuscleFact(
                        fact.completedAt(), resolve(fact.exerciseCode(), fact.contentVersion())))
                .toList();

        return WorkoutRecoveryAssessment.evaluate(
                policy.version(), minimumHours, checkedAt, targetMuscles, completedFacts);
    }

    private Set<String> resolveAll(List<PlanWorkoutSnapshotQuery.ExerciseSource> exercises) {
        Set<String> result = new HashSet<>();
        exercises.forEach(exercise -> result.addAll(resolve(
                exercise.exerciseCode(), exercise.contentVersion())));
        return Set.copyOf(result);
    }

    private Set<String> resolve(String exerciseCode, String contentVersion) {
        return muscles.primaryMuscles(exerciseCode, contentVersion)
                .filter(resolved -> !resolved.isEmpty())
                .map(Set::copyOf)
                .orElseThrow(() -> new RecoveryFactsUnavailableException(exerciseCode, contentVersion));
    }

    private static void assertSelectedReference(
            PlanWorkoutSnapshotQuery.PlanDaySource selected,
            UUID planId,
            int planVersionNumber,
            String trainingDayCode) {
        Objects.requireNonNull(selected, "selected plan day must not be null");
        if (!planId.equals(selected.planId())
                || planVersionNumber != selected.versionNumber()
                || !trainingDayCode.equals(selected.trainingDayCode())) {
            throw new IllegalStateException("plan day query returned a mismatched reference");
        }
    }

    public static final class RecoveryFactsUnavailableException extends RuntimeException {
        public RecoveryFactsUnavailableException(String exerciseCode, String contentVersion) {
            super("primary muscles are unavailable for exercise " + exerciseCode
                    + " at content version " + contentVersion);
        }
    }
}
