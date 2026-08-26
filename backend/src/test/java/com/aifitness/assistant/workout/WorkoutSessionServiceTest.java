package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutWarmupPrescriptionService;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkoutSessionServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final UUID DAY_ID = UUID.fromString("00000000-0000-0000-0000-000000000204");
    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");

    @Test
    void sameUserAndClientKeyReturnsOriginalSessionWithoutRebuildingSnapshot() {
        AtomicInteger loads = new AtomicInteger();
        WorkoutSessionService service = service((userId, planId, versionNo, dayCode) -> {
            loads.incrementAndGet();
            return planSnapshot();
        });
        WorkoutSessionService.StartCommand command =
                new WorkoutSessionService.StartCommand("client-session-001", PLAN_ID, 1, "DAY_1");

        WorkoutSession first = service.start(new AuthenticatedUserId(USER_ID), command);
        WorkoutSession duplicate = service.start(new AuthenticatedUserId(USER_ID), command);

        assertThat(duplicate).isEqualTo(first);
        assertThat(loads).hasValue(1);
    }

    @Test
    void sameClientKeyWithDifferentSourceIsAnExplicitIdempotencyConflict() {
        WorkoutSessionService service = service((userId, planId, versionNo, dayCode) -> planSnapshot());
        WorkoutSessionService.StartCommand original =
                new WorkoutSessionService.StartCommand("client-session-001", PLAN_ID, 1, "DAY_1");
        service.start(new AuthenticatedUserId(USER_ID), original);

        WorkoutSessionService.StartCommand changed =
                new WorkoutSessionService.StartCommand("client-session-001", PLAN_ID, 1, "DAY_2");
        assertThatThrownBy(() -> service.start(new AuthenticatedUserId(USER_ID), changed))
                .isInstanceOf(WorkoutSessionService.IdempotencyConflictException.class);
    }

    @Test
    void statusUpdateUsesOptimisticVersionAndUserOwnership() {
        WorkoutSessionService service = service((userId, planId, versionNo, dayCode) -> planSnapshot());
        WorkoutSession created = service.start(
                new AuthenticatedUserId(USER_ID),
                new WorkoutSessionService.StartCommand("client-session-001", PLAN_ID, 1, "DAY_1"));

        WorkoutSession active = service.transition(
                new AuthenticatedUserId(USER_ID), created.id(), WorkoutStatus.IN_PROGRESS, 0);

        assertThat(active.status()).isEqualTo(WorkoutStatus.IN_PROGRESS);
        assertThat(active.version()).isEqualTo(1);
        assertThatThrownBy(() -> service.transition(
                new AuthenticatedUserId(USER_ID), created.id(), WorkoutStatus.PAUSED, 0))
                .isInstanceOf(WorkoutSessionService.VersionConflictException.class);
        assertThatThrownBy(() -> service.get(
                new AuthenticatedUserId(UUID.randomUUID()), created.id()))
                .isInstanceOf(WorkoutSessionService.SessionNotFoundException.class);
    }

    @Test
    void genericStatusUpdateCannotBypassAuthoritativeCompletion() {
        WorkoutSessionService service = service((userId, planId, versionNo, dayCode) -> planSnapshot());
        WorkoutSession created = service.start(
                new AuthenticatedUserId(USER_ID),
                new WorkoutSessionService.StartCommand("client-session-001", PLAN_ID, 1, "DAY_1"));
        WorkoutSession active = service.transition(
                new AuthenticatedUserId(USER_ID), created.id(), WorkoutStatus.IN_PROGRESS, 0);

        assertThatThrownBy(() -> service.transition(
                new AuthenticatedUserId(USER_ID), active.id(), WorkoutStatus.COMPLETING, active.version()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transition(
                new AuthenticatedUserId(USER_ID), active.id(), WorkoutStatus.COMPLETED, active.version()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(new AuthenticatedUserId(USER_ID), active.id()).status())
                .isEqualTo(WorkoutStatus.IN_PROGRESS);
    }

    @Test
    void storesVersionedWarmupPrescriptionAgainstTheSelectedSnapshotExercise() {
        PlanWorkoutSnapshotQuery plans = (userId, planId, versionNo, dayCode) -> new PlanWorkoutSnapshotQuery.PlanDaySource(
                PLAN_ID, VERSION_ID, 1, DAY_ID, "DAY_1",
                List.of(new PlanWorkoutSnapshotQuery.WarmupStepSource(
                        "跑步机快走或慢跑", Optional.of("1 分钟"), false)),
                List.of(
                        new PlanWorkoutSnapshotQuery.ExerciseSource(
                                UUID.fromString("00000000-0000-0000-0000-000000000205"),
                                1, "DEAD_BUG", "死虫式", "content-v1", java.util.Set.of("BODYWEIGHT"),
                                3, 8, 12, 90, "BODYWEIGHT", "KG"),
                        new PlanWorkoutSnapshotQuery.ExerciseSource(
                                UUID.fromString("00000000-0000-0000-0000-000000000206"),
                                2, "DB_SQUAT", "哑铃深蹲", "content-v1", java.util.Set.of("DUMBBELL"),
                                3, 8, 12, 90, "KNOWN", Optional.of(new BigDecimal("20")), "KG",
                                Optional.of(2), Optional.of(2), Optional.of(2), true,
                                Optional.of("LEG_FINISHER"), Optional.of(1),
                                Optional.of(new PlanWorkoutSnapshotQuery.OptionalSetRuleSource(
                                        "UNDER_42_GOOD_STATE", "TUESDAY_BONUS", 1, Optional.empty())))));
        WorkoutWarmupPrescriptionService warmups = mock(WorkoutWarmupPrescriptionService.class);
        when(warmups.prescribe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine.Prescription(
                        "workout-warmup-prescription-v1",
                        "1.3.0",
                        new com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine.GeneralWarmup(1, 180),
                        Optional.of(new com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine.RampWarmup(
                                2,
                                com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine.RampStatus.READY,
                                Optional.of("DUMBBELL"),
                                List.of(new com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine.RampSet(
                                        new BigDecimal("10"), 10)),
                                Optional.empty(),
                                Optional.empty())),
                        false,
                        false));
        AtomicInteger sequence = new AtomicInteger(300);
        WorkoutSessionService service = new WorkoutSessionService(
                new InMemoryWorkoutSessionRepository(), plans, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new UUID(0, sequence.getAndIncrement()), warmups);

        WorkoutSession created = service.start(
                new AuthenticatedUserId(USER_ID),
                new WorkoutSessionService.StartCommand("client-session-001", PLAN_ID, 1, "DAY_1"));

        assertThat(created.warmupPrescription()).get().satisfies(prescription -> {
            assertThat(prescription.schemaVersion()).isEqualTo("workout-warmup-prescription-v1");
            assertThat(prescription.generalWarmup().occurrences()).isEqualTo(1);
            assertThat(prescription.instructions()).singleElement().satisfies(step -> {
                assertThat(step.instruction()).isEqualTo("跑步机快走或慢跑");
                assertThat(step.prescription()).contains("1 分钟");
            });
            assertThat(prescription.rampWarmup()).get().satisfies(ramp -> {
                assertThat(ramp.exerciseOrder()).isEqualTo(2);
                assertThat(ramp.exerciseId()).isEqualTo(created.exercises().get(1).id());
                assertThat(ramp.sets()).extracting(set -> set.weightKg()).containsExactly(new BigDecimal("10"));
            });
            assertThat(prescription.countsTowardTrainingVolume()).isFalse();
            assertThat(prescription.countsTowardProgression()).isFalse();
        });
        assertThat(created.exercises().get(1).prescription()).satisfies(prescription -> {
            assertThat(prescription.targetRirMin()).contains(2);
            assertThat(prescription.eccentricSeconds()).contains(2);
            assertThat(prescription.perSide()).isTrue();
            assertThat(prescription.executionGroup()).contains("LEG_FINISHER");
            assertThat(prescription.executionOrder()).contains(1);
            assertThat(prescription.optionalSetRule()).isPresent();
        });
    }

    private static WorkoutSessionService service(PlanWorkoutSnapshotQuery plans) {
        WorkoutSessionRepository sessions = new InMemoryWorkoutSessionRepository();
        return new WorkoutSessionService(
                sessions, plans, Clock.fixed(NOW, ZoneOffset.UTC),
                new java.util.function.Supplier<>() {
                    private long next = 300;
                    @Override public UUID get() {
                        return new UUID(0, next++);
                    }
                });
    }

    private static PlanWorkoutSnapshotQuery.PlanDaySource planSnapshot() {
        return new PlanWorkoutSnapshotQuery.PlanDaySource(
                PLAN_ID, VERSION_ID, 1, DAY_ID, "DAY_1",
                List.of(new PlanWorkoutSnapshotQuery.ExerciseSource(
                        UUID.fromString("00000000-0000-0000-0000-000000000205"),
                        1, "DB_SQUAT", "哑铃深蹲", "content-v1", java.util.Set.of("DUMBBELL"),
                        3, 8, 12, 90, "NEEDS_CALIBRATION", "KG")));
    }
}
