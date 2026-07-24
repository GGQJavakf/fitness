package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutCompletionService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkoutCompletionTest {
    @Test
    void earlyEndPreservesCompletedFactsButDisablesAutomaticProgression() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                "set-complete-0001", 1,
                new WorkoutSetService.Command(
                        WorkoutSetTestFixture.EXERCISE_ID, 1, WorkoutSet.SetType.WORK, 1,
                        new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10),
                        new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10), null,
                        WorkoutSet.CompletionStatus.COMPLETED,
                        Optional.of(Instant.parse("2026-07-24T08:00:00Z")), false));
        WorkoutCompletionService service = new WorkoutCompletionService(
                fixture.sessions(), fixture.repository(),
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC));

        WorkoutCompletionService.Result result = service.complete(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                2, WorkoutCompletionService.CompletionType.EARLY_END);

        assertThat(result.session().status()).isEqualTo(WorkoutStatus.ABORTED);
        assertThat(result.completedWorkSets()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
        assertThat(result.automaticProgressionEligible()).isFalse();
        assertThat(fixture.repository().findBySession(
                WorkoutSetTestFixture.USER_ID, WorkoutSetTestFixture.SESSION_ID)).hasSize(1);
    }

    @Test
    void fullCompletionRejectsIncompleteFactsWithoutChangingTheSession() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        WorkoutCompletionService service = new WorkoutCompletionService(
                fixture.sessions(), fixture.repository(),
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                1, WorkoutCompletionService.CompletionType.FULL))
                .isInstanceOf(WorkoutCompletionService.IncompleteWorkoutException.class);
        assertThat(fixture.sessions().findByIdAndUser(
                WorkoutSetTestFixture.SESSION_ID, WorkoutSetTestFixture.USER_ID).orElseThrow().status())
                .isEqualTo(WorkoutStatus.IN_PROGRESS);
    }

    @Test
    void fullCompletionIsStableWhenTheSuccessfulRequestIsRetried() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        saveCompleted(fixture, "set-full-0001", 1, 1);
        saveCompleted(fixture, "set-full-0002", 2, 2);
        saveCompleted(fixture, "set-full-0003", 3, 3);
        WorkoutCompletionService service = new WorkoutCompletionService(
                fixture.sessions(), fixture.repository(),
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC));

        WorkoutCompletionService.Result first = service.complete(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                4, WorkoutCompletionService.CompletionType.FULL);
        WorkoutCompletionService.Result retry = service.complete(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                4, WorkoutCompletionService.CompletionType.FULL);

        assertThat(first.session().status()).isEqualTo(WorkoutStatus.COMPLETED);
        assertThat(first.completedWorkSets()).isEqualTo(3);
        assertThat(first.automaticProgressionEligible()).isTrue();
        assertThat(retry).isEqualTo(first);
    }

    @Test
    void duplicateFactsForOneSetPositionCannotFakeFullCompletion() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        saveCompleted(fixture, "set-duplicate-0001", 1, 1);
        fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                "set-duplicate-0002", 2,
                new WorkoutSetService.Command(
                        WorkoutSetTestFixture.EXERCISE_ID, 2, WorkoutSet.SetType.WORK, 1,
                        new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10),
                        new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10), null,
                        WorkoutSet.CompletionStatus.COMPLETED,
                        Optional.of(Instant.parse("2026-07-24T08:00:02Z")), false));
        WorkoutCompletionService service = new WorkoutCompletionService(
                fixture.sessions(), fixture.repository(),
                Clock.fixed(Instant.parse("2026-07-24T08:05:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                3, WorkoutCompletionService.CompletionType.FULL))
                .isInstanceOf(WorkoutCompletionService.IncompleteWorkoutException.class);
    }

    private static void saveCompleted(
            WorkoutSetTestFixture.Fixture fixture, String key, long sequence, long expectedVersion) {
        fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                key, expectedVersion,
                new WorkoutSetService.Command(
                        WorkoutSetTestFixture.EXERCISE_ID, sequence, WorkoutSet.SetType.WORK, (int) sequence,
                        new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10),
                        new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10), null,
                        WorkoutSet.CompletionStatus.COMPLETED,
                        Optional.of(Instant.parse("2026-07-24T08:00:00Z").plusSeconds(sequence)), false));
    }
}
