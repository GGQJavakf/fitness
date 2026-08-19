package com.aifitness.assistant.workout.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import com.aifitness.assistant.workout.application.WorkoutCompletionService;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSessionStartService;
import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutRecoveryConfirmationStore;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionStartTransaction;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSetRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WorkoutSessionRecoveryControllerTest {
    @Test
    void returnsTyped409ThenAcceptsTheOpaqueConfirmationOnTheSameIdempotentStart() {
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T08:00:00Z"), ZoneOffset.UTC);
        InMemoryWorkoutSessionRepository repository = new InMemoryWorkoutSessionRepository();
        AtomicInteger ids = new AtomicInteger(1);
        PlanWorkoutSnapshotQuery plans = (owner, selectedPlan, version, day) ->
                new PlanWorkoutSnapshotQuery.PlanDaySource(
                        planId, UUID.randomUUID(), 1, UUID.randomUUID(), "DAY_1",
                        List.of(new PlanWorkoutSnapshotQuery.ExerciseSource(
                                UUID.randomUUID(), 1, "BENCH_PRESS", "杠铃卧推", "content-v1",
                                Set.of("BARBELL"), 3, 8, 12, 90, "NEEDS_CALIBRATION", "KG")));
        WorkoutSessionService sessions = new WorkoutSessionService(
                repository, plans, clock, () -> new UUID(0, ids.getAndIncrement()));
        WorkoutRecoveryAssessment warning = WorkoutRecoveryAssessment.evaluate(
                "1.3.0", 48, clock.instant(), Set.of("CHEST"),
                List.of(new WorkoutRecoveryAssessment.CompletedMuscleFact(
                        clock.instant().minus(Duration.ofHours(18)), Set.of("CHEST"))));
        WorkoutSessionStartService starts = new WorkoutSessionStartService(
                sessions, repository, new InMemoryWorkoutSetRepository(repository),
                (user, selectedPlan, version, day) -> warning,
                new InMemoryWorkoutRecoveryConfirmationStore(),
                new InMemoryWorkoutSessionStartTransaction(), clock, Duration.ofMinutes(5));
        WorkoutSessionController controller = new WorkoutSessionController(
                sessions, starts, mock(WorkoutCompletionService.class),
                mock(ExerciseReplacementService.class), clock);
        String clientKey = "controller-recovery-client";
        WorkoutSessionController.StartRequest initial = new WorkoutSessionController.StartRequest(
                clientKey, planId, 1, "DAY_1", "DAY_1", null);

        var first = controller.start(new AuthenticatedUserId(userId), clientKey, initial);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(first.getBody()).isInstanceOf(ApiErrorResponse.class);
        ApiErrorResponse error = (ApiErrorResponse) first.getBody();
        assertThat(error.error().code()).isEqualTo(ErrorCode.RECOVERY_CONFIRMATION_REQUIRED);
        assertThat(error.error().details()).containsKeys(
                "assessment", "confirmationToken", "confirmationExpiresAt");
        assertThat(repository.findByUserAndClientKey(userId, clientKey)).isEmpty();

        String token = (String) error.error().details().get("confirmationToken");
        WorkoutSessionController.StartRequest confirmed = new WorkoutSessionController.StartRequest(
                clientKey, planId, 1, "DAY_1", "DAY_1", token);
        var second = controller.start(new AuthenticatedUserId(userId), clientKey, confirmed);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isInstanceOf(ApiResponse.class);
        assertThat(repository.findByUserAndClientKey(userId, clientKey)).isPresent();
    }

    @Test
    void returnsTheOwnedActiveSessionWhenAnotherClientKeyTriesToStart() {
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T08:00:00Z"), ZoneOffset.UTC);
        InMemoryWorkoutSessionRepository repository = new InMemoryWorkoutSessionRepository();
        AtomicInteger ids = new AtomicInteger(100);
        PlanWorkoutSnapshotQuery plans = (owner, selectedPlan, version, day) ->
                new PlanWorkoutSnapshotQuery.PlanDaySource(
                        planId, UUID.randomUUID(), 1, UUID.randomUUID(), "DAY_1",
                        List.of(new PlanWorkoutSnapshotQuery.ExerciseSource(
                                UUID.randomUUID(), 1, "BENCH_PRESS", "杠铃卧推", "content-v1",
                                Set.of("BARBELL"), 3, 8, 12, 90, "NEEDS_CALIBRATION", "KG")));
        WorkoutSessionService sessions = new WorkoutSessionService(
                repository, plans, clock, () -> new UUID(0, ids.getAndIncrement()));
        WorkoutRecoveryAssessment ready = WorkoutRecoveryAssessment.evaluate(
                "1.3.0", 48, clock.instant(), Set.of("CHEST"), List.of());
        WorkoutSessionStartService starts = new WorkoutSessionStartService(
                sessions, repository, new InMemoryWorkoutSetRepository(repository),
                (user, selectedPlan, version, day) -> ready,
                new InMemoryWorkoutRecoveryConfirmationStore(),
                new InMemoryWorkoutSessionStartTransaction(), clock, Duration.ofMinutes(5));
        WorkoutSessionController controller = new WorkoutSessionController(
                sessions, starts, mock(WorkoutCompletionService.class),
                mock(ExerciseReplacementService.class), clock);

        String firstKey = "controller-active-client-001";
        var first = controller.start(
                new AuthenticatedUserId(userId), firstKey,
                new WorkoutSessionController.StartRequest(firstKey, planId, 1, "DAY_1", "DAY_1", null));
        var created = (ApiResponse<WorkoutSessionController.SessionData>) first.getBody();
        sessions.transition(
                new AuthenticatedUserId(userId), created.data().id(),
                com.aifitness.assistant.workout.domain.WorkoutStatus.IN_PROGRESS,
                created.data().version());
        var exactReplay = controller.start(
                new AuthenticatedUserId(userId), firstKey,
                new WorkoutSessionController.StartRequest(firstKey, planId, 1, "DAY_1", "DAY_1", null));
        String secondKey = "controller-active-client-002";
        var second = controller.start(
                new AuthenticatedUserId(userId), secondKey,
                new WorkoutSessionController.StartRequest(secondKey, planId, 1, "DAY_1", "DAY_1", null));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(exactReplay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ApiErrorResponse) exactReplay.getBody()).error().code())
                .isEqualTo(ErrorCode.ACTIVE_WORKOUT_EXISTS);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiErrorResponse error = (ApiErrorResponse) second.getBody();
        assertThat(error.error().code()).isEqualTo(ErrorCode.ACTIVE_WORKOUT_EXISTS);
        assertThat(error.error().details()).containsKey("activeSession");
        assertThat(repository.findByUserAndClientKey(userId, secondKey)).isEmpty();
    }
}
