package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutSyncService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.SyncConflict;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemorySyncConflictRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class BatchSyncTest {
    @Test
    void conflictFixtureIsVersionedAndDocumentsTheExpectedDecision() throws Exception {
        var fixture = new ObjectMapper().readTree(
                Path.of("..", "test-fixtures", "sync", "conflict-cases-v1.json").toFile());
        assertThat(fixture.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(fixture.at("/cases/0/expectedStatus").asText()).isEqualTo("CONFLICT");
        assertThat(fixture.at("/cases/0/local/actualReps").asInt()).isEqualTo(8);
        assertThat(fixture.at("/cases/0/server/actualReps").asInt()).isEqualTo(9);
    }

    @Test
    void returnsPerItemResultsAndKeepsConflictEvidenceWithoutRollingBackAcceptedItems() {
        var fixture = WorkoutSetTestFixture.fixture();
        var conflicts = new InMemorySyncConflictRepository();
        var sync = new WorkoutSyncService(
                fixture.service(), fixture.repository(), fixture.sessions(), conflicts,
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC),
                () -> new UUID(0, 200));
        var user = new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID);
        var original = command(1, "40", 9);
        fixture.service().upsert(user, WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 1, original);

        var results = sync.apply(user, List.of(
                WorkoutSyncService.Operation.upsert(1, WorkoutSetTestFixture.SESSION_ID,
                        "set-key-0001", 1, original),
                WorkoutSyncService.Operation.upsert(2, WorkoutSetTestFixture.SESSION_ID,
                        "set-key-0001", 2, command(1, "40", 8)),
                WorkoutSyncService.Operation.upsert(3, WorkoutSetTestFixture.SESSION_ID,
                        "set-key-0002", 2, command(2, "40", 9)),
                WorkoutSyncService.Operation.rejected(4, "unsupported operation type")));

        assertThat(results).extracting(WorkoutSyncService.ItemResult::status)
                .containsExactly(
                        WorkoutSyncService.ItemStatus.DUPLICATE,
                        WorkoutSyncService.ItemStatus.CONFLICT,
                        WorkoutSyncService.ItemStatus.APPLIED,
                        WorkoutSyncService.ItemStatus.REJECTED);
        assertThat(fixture.repository().count()).isEqualTo(2);
        assertThat(conflicts.listOpen(WorkoutSetTestFixture.USER_ID)).singleElement().satisfies(conflict -> {
            assertThat(conflict.localEvidence()).containsEntry("actualReps", "8");
            assertThat(conflict.localEvidence())
                    .containsEntry("targetWeightKg", "40")
                    .containsEntry("targetReps", "10")
                    .containsEntry("remainingReps", "2")
                    .containsEntry("completedAt", "2026-07-24T08:00:00Z");
            assertThat(conflict.serverEvidence()).containsEntry("actualReps", "9");
            assertThat(conflict.localEvidence().toString()).doesNotContain("token", "userId");
        });
        assertThat(conflicts.listOpen(new UUID(0, 99))).isEmpty();

        var open = conflicts.listOpen(WorkoutSetTestFixture.USER_ID).getFirst();
        var resolved = sync.resolveConflict(user, open.id(), SyncConflict.Resolution.KEEP_BOTH, 0);
        assertThat(resolved.clientOperationSeq()).isEqualTo(2);
        assertThat(resolved.clientKey()).isEqualTo("set-key-0001");
        assertThat(resolved.resolution()).isEqualTo(SyncConflict.Resolution.KEEP_BOTH);
        assertThat(resolved.outcome()).isEqualTo(WorkoutSyncService.ConflictResolutionOutcome.ABANDONED);
        assertThat(resolved.authoritativeSessionVersion()).isEqualTo(3);
        assertThat(resolved.authoritativePayload()).containsEntry("kind", "WORKOUT_SET");
        assertThat(resolved.authoritativePayload().get("actual").toString()).contains("actualReps=9");
        assertThat(fixture.repository().count()).isEqualTo(2);
        assertThat(conflicts.listOpen(WorkoutSetTestFixture.USER_ID)).isEmpty();

        fixture.service().upsert(user, WorkoutSetTestFixture.SESSION_ID, "set-key-0003", 3,
                command(3, "40", 10));
        var replay = sync.resolveConflict(user, open.id(), SyncConflict.Resolution.KEEP_BOTH, 0);
        assertThat(replay).isEqualTo(resolved);
        assertThat(fixture.repository().count()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sync.resolveConflict(user, open.id(), SyncConflict.Resolution.KEEP_SERVER, 0))
                .isInstanceOf(com.aifitness.assistant.workout.application.WorkoutSessionService.VersionConflictException.class);
    }

    @Test
    void keepLocalCorrectsTheExistingSetWithOneRevisionAndReplaysWithoutDuplicates() {
        var fixture = WorkoutSetTestFixture.fixture();
        var conflicts = new InMemorySyncConflictRepository();
        var sync = new WorkoutSyncService(
                fixture.service(), fixture.repository(), fixture.sessions(), conflicts,
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC),
                () -> new UUID(0, 202));
        var user = new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID);
        fixture.service().upsert(user, WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 1,
                command(1, "40", 9));

        var conflictResult = sync.apply(user, List.of(WorkoutSyncService.Operation.upsert(
                2, WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 2,
                command(2, "40", 8)))).getFirst();

        var resolved = sync.resolveConflict(
                user, conflictResult.conflictId().orElseThrow(), SyncConflict.Resolution.KEEP_LOCAL, 0);
        assertThat(resolved.clientOperationSeq()).isEqualTo(2);
        assertThat(resolved.outcome()).isEqualTo(WorkoutSyncService.ConflictResolutionOutcome.ACKNOWLEDGED);
        assertThat(resolved.authoritativeSessionVersion()).isEqualTo(3);
        assertThat(resolved.authoritativePayload().get("actual").toString()).contains("actualReps=8");
        assertThat(fixture.repository().count()).isEqualTo(1);
        assertThat(fixture.repository().revisionCount()).isEqualTo(1);

        var replay = sync.resolveConflict(
                user, conflictResult.conflictId().orElseThrow(), SyncConflict.Resolution.KEEP_LOCAL, 0);
        assertThat(replay).isEqualTo(resolved);
        assertThat(fixture.repository().count()).isEqualTo(1);
        assertThat(fixture.repository().revisionCount()).isEqualTo(1);

        var duplicate = fixture.service().upsert(
                user, WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 3, command(1, "40", 8));
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.sessionVersion()).isEqualTo(3);
    }

    @Test
    void retainsCompletedOfflineSetAsConflictWhenServerSessionIsAlreadyTerminal() {
        var fixture = WorkoutSetTestFixture.fixture();
        var current = fixture.sessions().findByIdAndUser(
                WorkoutSetTestFixture.SESSION_ID, WorkoutSetTestFixture.USER_ID).orElseThrow();
        var completing = fixture.sessions().update(current.transitionTo(WorkoutStatus.COMPLETING,
                Instant.parse("2026-07-24T08:02:00Z")), 1);
        fixture.sessions().update(completing.transitionTo(WorkoutStatus.COMPLETED,
                Instant.parse("2026-07-24T08:03:00Z")), 2);
        var conflicts = new InMemorySyncConflictRepository();
        var sync = new WorkoutSyncService(fixture.service(), fixture.repository(), fixture.sessions(), conflicts,
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC), () -> new UUID(0, 201));

        var result = sync.apply(new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), List.of(
                WorkoutSyncService.Operation.upsert(1, WorkoutSetTestFixture.SESSION_ID,
                        "offline-set-0001", 3, command(1, "40", 9))));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(WorkoutSyncService.ItemStatus.CONFLICT);
            assertThat(item.reasonCode()).contains("SESSION_TERMINAL");
        });
        assertThat(conflicts.listOpen(WorkoutSetTestFixture.USER_ID)).singleElement().satisfies(conflict -> {
            assertThat(conflict.localEvidence()).containsEntry("actualReps", "9");
            assertThat(conflict.serverEvidence()).containsEntry("reasonCode", "SESSION_TERMINAL");
        });
    }

    @Test
    void terminalSessionRejectsKeepLocalWithoutChangingTheCompletedFact() {
        var fixture = WorkoutSetTestFixture.fixture();
        var user = new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID);
        fixture.service().upsert(user, WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 1,
                command(1, "40", 9));
        var current = fixture.sessions().findByIdAndUser(
                WorkoutSetTestFixture.SESSION_ID, WorkoutSetTestFixture.USER_ID).orElseThrow();
        var completing = fixture.sessions().update(current.transitionTo(WorkoutStatus.COMPLETING,
                Instant.parse("2026-07-24T08:02:00Z")), 2);
        fixture.sessions().update(completing.transitionTo(WorkoutStatus.COMPLETED,
                Instant.parse("2026-07-24T08:03:00Z")), 3);
        var conflicts = new InMemorySyncConflictRepository();
        var sync = new WorkoutSyncService(
                fixture.service(), fixture.repository(), fixture.sessions(), conflicts,
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC),
                () -> new UUID(0, 203));

        var conflict = sync.apply(user, List.of(WorkoutSyncService.Operation.upsert(
                2, WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 4,
                command(2, "40", 8)))).getFirst();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sync.resolveConflict(
                        user, conflict.conflictId().orElseThrow(), SyncConflict.Resolution.KEEP_LOCAL, 0))
                .isInstanceOf(WorkoutSetService.SessionNotAcceptingSetsException.class);
        assertThat(fixture.repository().find(
                WorkoutSetTestFixture.USER_ID, WorkoutSetTestFixture.SESSION_ID,
                WorkoutSetTestFixture.EXERCISE_ID, "set-key-0001").orElseThrow().actual().reps())
                .isEqualTo(9);
        assertThat(fixture.repository().revisionCount()).isZero();
        assertThat(fixture.sessions().findByIdAndUser(
                WorkoutSetTestFixture.SESSION_ID, WorkoutSetTestFixture.USER_ID).orElseThrow().version())
                .isEqualTo(4);
        assertThat(conflicts.listOpen(WorkoutSetTestFixture.USER_ID)).hasSize(1);
    }

    @Test
    void replayingTheSameFailedOperationReturnsOneOpenConflict() {
        var fixture = WorkoutSetTestFixture.fixture();
        var conflicts = new InMemorySyncConflictRepository();
        var sequence = new AtomicLong(300);
        var sync = new WorkoutSyncService(
                fixture.service(), fixture.repository(), fixture.sessions(), conflicts,
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC),
                () -> new UUID(0, sequence.getAndIncrement()));
        var user = new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID);
        fixture.service().upsert(user, WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 1,
                command(1, "40", 9));
        var operation = WorkoutSyncService.Operation.upsert(
                2, WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 2,
                command(2, "40", 8));

        var first = sync.apply(user, List.of(operation)).getFirst();
        var replay = sync.apply(user, List.of(operation)).getFirst();

        var openConflicts = conflicts.listOpen(WorkoutSetTestFixture.USER_ID);
        assertThat(openConflicts).extracting(SyncConflict::localEvidence).doesNotHaveDuplicates();
        assertThat(replay.conflictId()).isEqualTo(first.conflictId());
        assertThat(openConflicts).hasSize(1);
    }

    @Test
    void propagatesUnexpectedInfrastructureFailureSoTheClientCanRetryTheBatch() {
        var fixture = WorkoutSetTestFixture.fixture();
        var failingService = mock(WorkoutSetService.class);
        var failure = new IllegalStateException("database unavailable");
        when(failingService.upsert(
                        any(AuthenticatedUserId.class), any(UUID.class), anyString(), anyLong(),
                        any(WorkoutSetService.Command.class)))
                .thenThrow(failure);
        var sync = new WorkoutSyncService(
                failingService, fixture.repository(), fixture.sessions(), new InMemorySyncConflictRepository(),
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC),
                () -> new UUID(0, 204));

        assertThatThrownBy(() -> sync.apply(
                        new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID),
                        List.of(WorkoutSyncService.Operation.upsert(
                                1, WorkoutSetTestFixture.SESSION_ID, "offline-set-0001", 1,
                                command(1, "40", 9)))))
                .isSameAs(failure);
    }

    private static WorkoutSetService.Command command(long sequence, String weight, int reps) {
        return new WorkoutSetService.Command(
                WorkoutSetTestFixture.EXERCISE_ID, sequence, WorkoutSet.SetType.WORK, (int) sequence,
                new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10),
                new WorkoutSet.Performance(new BigDecimal(weight), "KG", reps), 2,
                WorkoutSet.CompletionStatus.COMPLETED,
                Optional.of(Instant.parse("2026-07-24T08:00:00Z")), false);
    }
}
