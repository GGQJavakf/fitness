package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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
