package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.workout.application.WorkoutRecoveryCheckService;
import com.aifitness.assistant.workout.application.WorkoutRecoveryFactQuery;
import com.aifitness.assistant.workout.application.WorkoutMuscleCatalog;
import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkoutRecoveryCheckServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Test
    void bindsBothPlanAndActualFactQueriesToTheAuthenticatedUser() {
        AtomicReference<UUID> planUser = new AtomicReference<>();
        AtomicReference<UUID> factUser = new AtomicReference<>();
        AtomicReference<Instant> completedAfter = new AtomicReference<>();
        PlanWorkoutSnapshotQuery plans = (userId, planId, versionNumber, trainingDayCode) -> {
            planUser.set(userId);
            return planDay("DUMBBELL_BENCH_PRESS");
        };
        WorkoutRecoveryFactQuery facts = (userId, after) -> {
            factUser.set(userId);
            completedAfter.set(after);
            return List.of(new WorkoutRecoveryFactQuery.CompletedExerciseFact(
                    UUID.randomUUID(), NOW.minusSeconds(20 * 3600L), "INCLINE_PUSH_UP", "content-v1"));
        };
        WorkoutMuscleCatalog muscles = (code, contentVersion) -> switch (code) {
            case "DUMBBELL_BENCH_PRESS", "INCLINE_PUSH_UP" -> Optional.of(Set.of("CHEST", "TRICEPS"));
            default -> Optional.empty();
        };
        WorkoutRecoveryCheckService service = new WorkoutRecoveryCheckService(
                plans, facts, muscles, policy(), Clock.fixed(NOW, ZoneOffset.UTC));

        WorkoutRecoveryAssessment result = service.check(
                new AuthenticatedUserId(USER_ID), PLAN_ID, 1, "DAY_1");

        assertThat(planUser).hasValue(USER_ID);
        assertThat(factUser).hasValue(USER_ID);
        assertThat(completedAfter).hasValue(NOW.minusSeconds(48 * 3600L));
        assertThat(result.decision()).isEqualTo(WorkoutRecoveryAssessment.Decision.CONFIRMATION_REQUIRED);
        assertThat(result.affectedMuscles()).extracting(
                WorkoutRecoveryAssessment.AffectedMuscle::muscleGroup)
                .containsExactly("CHEST", "TRICEPS");
    }

    @Test
    void failsClosedWhenARecentActualExerciseCannotBeResolvedToMuscles() {
        WorkoutRecoveryCheckService service = new WorkoutRecoveryCheckService(
                (userId, planId, versionNumber, trainingDayCode) -> planDay("DUMBBELL_BENCH_PRESS"),
                (userId, after) -> List.of(new WorkoutRecoveryFactQuery.CompletedExerciseFact(
                        UUID.randomUUID(), NOW.minusSeconds(2 * 3600L), "RETIRED_EXERCISE", "content-v1")),
                (code, contentVersion) -> "DUMBBELL_BENCH_PRESS".equals(code)
                        ? Optional.of(Set.of("CHEST"))
                        : Optional.empty(),
                policy(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.check(
                new AuthenticatedUserId(USER_ID), PLAN_ID, 1, "DAY_1"))
                .isInstanceOf(WorkoutRecoveryCheckService.RecoveryFactsUnavailableException.class);
    }

    @Test
    void failsClosedInsteadOfReinterpretingAnOlderContentVersionWithTheCurrentCatalog() {
        WorkoutRecoveryCheckService service = new WorkoutRecoveryCheckService(
                (userId, planId, versionNumber, trainingDayCode) -> planDay("DUMBBELL_BENCH_PRESS"),
                (userId, after) -> List.of(new WorkoutRecoveryFactQuery.CompletedExerciseFact(
                        UUID.randomUUID(), NOW.minusSeconds(2 * 3600L),
                        "DUMBBELL_BENCH_PRESS", "content-v0")),
                (code, contentVersion) -> "content-v1".equals(contentVersion)
                        ? Optional.of(Set.of("CHEST"))
                        : Optional.empty(),
                policy(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.check(
                new AuthenticatedUserId(USER_ID), PLAN_ID, 1, "DAY_1"))
                .isInstanceOf(WorkoutRecoveryCheckService.RecoveryFactsUnavailableException.class);
    }

    private static PlanWorkoutSnapshotQuery.PlanDaySource planDay(String exerciseCode) {
        UUID versionId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID dayId = UUID.fromString("00000000-0000-4000-8000-000000000004");
        return new PlanWorkoutSnapshotQuery.PlanDaySource(
                PLAN_ID,
                versionId,
                1,
                dayId,
                "DAY_1",
                List.of(new PlanWorkoutSnapshotQuery.ExerciseSource(
                        UUID.fromString("00000000-0000-4000-8000-000000000005"),
                        1,
                        exerciseCode,
                        "训练动作",
                        "content-v1",
                        Set.of("DUMBBELL"),
                        3,
                        8,
                        12,
                        90,
                        "KNOWN",
                        "KG")));
    }

    private static PlanRulePolicy policy() {
        return new PlanRulePolicy(
                "rules-v1",
                new PlanRulePolicy.PlanLimits(2, 5, 6, 90),
                new PlanRulePolicy.Prescription(1, 5, 1, 30),
                new PlanRulePolicy.Rest(30, 180),
                new PlanRulePolicy.Duration(30, 20),
                new PlanRulePolicy.Balance(2, 12, 48));
    }
}
