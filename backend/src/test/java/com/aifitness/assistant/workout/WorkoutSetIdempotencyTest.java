package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkoutSetIdempotencyTest {
    @Test
    void sameKeyAndPayloadReturnsTheFirstResultButDifferentPayloadIsRejected() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        WorkoutSetService.Command command = command(new BigDecimal("40.000"), false);

        var first = fixture.service().upsert(new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 1, command);
        var retry = fixture.service().upsert(new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 1, command);

        assertThat(retry.set()).isEqualTo(first.set());
        assertThat(retry.sessionVersion()).isEqualTo(first.sessionVersion());
        assertThat(first.duplicate()).isFalse();
        assertThat(retry.duplicate()).isTrue();
        assertThat(first.sessionVersion()).isEqualTo(2);
        assertThat(fixture.repository().count()).isEqualTo(1);
        assertThatThrownBy(() -> fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 2,
                command(new BigDecimal("42.000"), false)))
                .isInstanceOf(WorkoutSessionService.IdempotencyConflictException.class);
    }

    @Test
    void userOwnershipAndExpectedSessionVersionAreEnforced() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();

        assertThatThrownBy(() -> fixture.service().upsert(
                new AuthenticatedUserId(new UUID(0, 99)), WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 1,
                command(new BigDecimal("40.000"), false)))
                .isInstanceOf(WorkoutSessionService.SessionNotFoundException.class);
        assertThatThrownBy(() -> fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID, "set-key-0001", 0,
                command(new BigDecimal("40.000"), false)))
                .isInstanceOf(WorkoutSessionService.VersionConflictException.class);
    }

    @Test
    void concurrentRetriesCreateOnlyOneSet() throws Exception {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        WorkoutSetService.Command command = command(new BigDecimal("40.000"), false);
        var tasks = IntStream.range(0, 16)
                .mapToObj(ignored -> (Callable<WorkoutSetRepository.SaveResult>) () -> fixture.service().upsert(
                        new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID),
                        WorkoutSetTestFixture.SESSION_ID, "set-key-concurrent", 1, command))
                .toList();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(results).extracting(WorkoutSetRepository.SaveResult::set).containsOnly(results.getFirst().set());
            assertThat(results).extracting(WorkoutSetRepository.SaveResult::sessionVersion).containsOnly(2L);
            assertThat(fixture.repository().count()).isEqualTo(1);
        }
    }

    @Test
    void failedSetRemainsAFirstClassNonCompletedFact() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        WorkoutSetService.Command failed = new WorkoutSetService.Command(
                WorkoutSetTestFixture.EXERCISE_ID, 1, WorkoutSet.SetType.WORK, 1,
                new WorkoutSet.Performance(new BigDecimal("40.000"), "KG", 10),
                new WorkoutSet.Performance(new BigDecimal("40.000"), "KG", 4), null,
                WorkoutSet.CompletionStatus.FAILED, Optional.empty(), false);

        WorkoutSet saved = fixture.service().upsert(
                new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID), WorkoutSetTestFixture.SESSION_ID,
                "set-key-failed-0001", 1, failed).set();

        assertThat(saved.completionStatus()).isEqualTo(WorkoutSet.CompletionStatus.FAILED);
        assertThat(saved.completedAt()).isEmpty();
    }

    private static WorkoutSetService.Command command(BigDecimal actualWeight, boolean confirmAnomaly) {
        return new WorkoutSetService.Command(
                WorkoutSetTestFixture.EXERCISE_ID, 1, WorkoutSet.SetType.WORK, 1,
                new WorkoutSet.Performance(new BigDecimal("40.000"), "KG", 10),
                new WorkoutSet.Performance(actualWeight, "KG", 9), 2,
                WorkoutSet.CompletionStatus.COMPLETED,
                Optional.of(Instant.parse("2026-07-24T08:00:00Z")), confirmAnomaly);
    }

}
