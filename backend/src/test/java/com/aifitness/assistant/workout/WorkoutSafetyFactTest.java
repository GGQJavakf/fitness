package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.application.WorkoutSyncService;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.infrastructure.InMemorySyncConflictRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkoutSafetyFactTest {

    @Test
    void safetyFlagParticipatesInIdempotencyAndReplay() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        AuthenticatedUserId user = new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID);
        WorkoutSetService.Command pain = command(WorkoutSet.SafetyFlag.PAIN);

        var created = fixture.service().upsert(
                user, WorkoutSetTestFixture.SESSION_ID, "safety-set-0001", 1, pain);
        var replayed = fixture.service().upsert(
                user, WorkoutSetTestFixture.SESSION_ID, "safety-set-0001", 1, pain);

        assertThat(created.set().safetyFlag()).contains(WorkoutSet.SafetyFlag.PAIN);
        assertThat(replayed.duplicate()).isTrue();
        assertThat(replayed.set().payloadDigest()).isEqualTo(created.set().payloadDigest());
        assertThatThrownBy(() -> fixture.service().upsert(
                user, WorkoutSetTestFixture.SESSION_ID, "safety-set-0001", 1,
                command(WorkoutSet.SafetyFlag.DIZZINESS)))
                .isInstanceOf(WorkoutSessionService.IdempotencyConflictException.class);
    }

    @Test
    void batchSyncPersistsAndReplaysTheTypedSafetyFlag() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        AuthenticatedUserId user = new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID);
        WorkoutSyncService sync = new WorkoutSyncService(
                fixture.service(), fixture.repository(), fixture.sessions(),
                new InMemorySyncConflictRepository(),
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC),
                () -> new UUID(0, 401));
        WorkoutSyncService.Operation operation = WorkoutSyncService.Operation.upsert(
                1, WorkoutSetTestFixture.SESSION_ID, "safety-sync-set-0001", 1,
                command(WorkoutSet.SafetyFlag.CHEST_DISCOMFORT));

        var applied = sync.apply(user, List.of(operation)).getFirst();
        var replayed = sync.apply(user, List.of(operation)).getFirst();

        assertThat(applied.status()).isEqualTo(WorkoutSyncService.ItemStatus.APPLIED);
        assertThat(replayed.status()).isEqualTo(WorkoutSyncService.ItemStatus.DUPLICATE);
        assertThat(fixture.repository().find(
                WorkoutSetTestFixture.USER_ID, WorkoutSetTestFixture.SESSION_ID,
                WorkoutSetTestFixture.EXERCISE_ID, "safety-sync-set-0001").orElseThrow().safetyFlag())
                .contains(WorkoutSet.SafetyFlag.CHEST_DISCOMFORT);
    }

    private static WorkoutSetService.Command command(WorkoutSet.SafetyFlag safetyFlag) {
        WorkoutSet.Performance performance =
                new WorkoutSet.Performance(new BigDecimal("40"), "KG", 8);
        return new WorkoutSetService.Command(
                WorkoutSetTestFixture.EXERCISE_ID, 1, WorkoutSet.SetType.WORK, 1,
                performance, performance, 0, WorkoutSet.CompletionStatus.COMPLETED,
                Optional.of(Instant.parse("2026-07-24T08:00:00Z")), Optional.of(safetyFlag), false);
    }
}
