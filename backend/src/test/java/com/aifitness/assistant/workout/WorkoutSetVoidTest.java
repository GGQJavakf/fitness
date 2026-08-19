package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkoutSetVoidTest {

    @Test
    void appendsOneAuditableVoidFactAndExcludesTheSetFromActiveReads() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        WorkoutSet saved = save(fixture, "set-void-source-0001", 1, 1);

        var first = fixture.service().voidSet(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                saved.id(), "void set 0001", 2);
        var retry = fixture.service().voidSet(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                saved.id(), "void set 0001", 2);

        assertThat(first.duplicate()).isFalse();
        assertThat(first.sessionVersion()).isEqualTo(3);
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.voidFact()).isEqualTo(first.voidFact());
        assertThat(retry.sessionVersion()).isEqualTo(3);
        assertThat(fixture.repository().findBySession(
                WorkoutSetTestFixture.USER_ID, WorkoutSetTestFixture.SESSION_ID)).isEmpty();
        assertThat(fixture.repository().findById(
                WorkoutSetTestFixture.USER_ID, WorkoutSetTestFixture.SESSION_ID, saved.id()))
                .contains(saved);
        assertThat(fixture.repository().findVoid(
                WorkoutSetTestFixture.USER_ID, WorkoutSetTestFixture.SESSION_ID, saved.id()))
                .contains(first.voidFact());
        assertThat(fixture.sessions().findByIdAndUser(
                WorkoutSetTestFixture.SESSION_ID, WorkoutSetTestFixture.USER_ID).orElseThrow().version())
                .isEqualTo(3);
    }

    @Test
    void enforcesOwnerTargetVersionActiveSessionAndIdempotencyPayload() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        WorkoutSet first = save(fixture, "set-void-source-0001", 1, 1);
        WorkoutSet second = save(fixture, "set-void-source-0002", 2, 2);

        assertThatThrownBy(() -> fixture.service().voidSet(
                new AuthenticatedUserId(new UUID(0, 99)), WorkoutSetTestFixture.SESSION_ID,
                first.id(), "void-set-owner", 3))
                .isInstanceOf(WorkoutSessionService.SessionNotFoundException.class);
        assertThatThrownBy(() -> fixture.service().voidSet(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                new UUID(0, 404), "void-set-missing", 3))
                .isInstanceOf(WorkoutSessionService.SessionNotFoundException.class);
        assertThatThrownBy(() -> fixture.service().voidSet(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                first.id(), "void-set-version", 2))
                .isInstanceOf(WorkoutSessionService.VersionConflictException.class);

        fixture.service().voidSet(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                first.id(), "void-set-reused", 3);
        assertThatThrownBy(() -> fixture.service().voidSet(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                second.id(), "void-set-reused", 4))
                .isInstanceOf(WorkoutSessionService.IdempotencyConflictException.class);

        var active = fixture.sessions().findByIdAndUser(
                WorkoutSetTestFixture.SESSION_ID, WorkoutSetTestFixture.USER_ID).orElseThrow();
        var terminal = active.transitionTo(WorkoutStatus.COMPLETING, Instant.parse("2026-07-24T08:05:00Z"))
                .transitionTo(WorkoutStatus.ABORTED, Instant.parse("2026-07-24T08:06:00Z"));
        fixture.sessions().complete(terminal, active.version());
        assertThatThrownBy(() -> fixture.service().voidSet(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                second.id(), "void-set-terminal", terminal.version()))
                .isInstanceOf(WorkoutSetService.SessionNotAcceptingSetsException.class);
    }

    private static WorkoutSet save(
            WorkoutSetTestFixture.Fixture fixture, String key, long sequence, long expectedVersion) {
        return fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                key, expectedVersion,
                new WorkoutSetService.Command(
                        WorkoutSetTestFixture.EXERCISE_ID, sequence, WorkoutSet.SetType.WORK, (int) sequence,
                        new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10),
                        new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10), null,
                        WorkoutSet.CompletionStatus.COMPLETED,
                        Optional.of(Instant.parse("2026-07-24T08:00:00Z").plusSeconds(sequence)), false))
                .set();
    }
}
