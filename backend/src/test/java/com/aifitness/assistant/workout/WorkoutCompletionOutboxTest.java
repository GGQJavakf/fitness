package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutCompletionOutbox;
import com.aifitness.assistant.workout.application.WorkoutCompletionOutboxProcessor;
import com.aifitness.assistant.workout.application.WorkoutCompletionService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutCompletionOutbox;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkoutCompletionOutboxTest {
    @Test
    void completionPersistsOneEventAndAConsumerRecoversAfterACrashedClaim() {
        WorkoutSetTestFixture.Fixture fixture = WorkoutSetTestFixture.fixture();
        saveCompleted(fixture, "outbox-set-1", 1, 1);
        saveCompleted(fixture, "outbox-set-2", 2, 2);
        saveCompleted(fixture, "outbox-set-3", 3, 3);
        Instant completedAt = Instant.parse("2026-07-24T08:05:00Z");
        InMemoryWorkoutCompletionOutbox outbox = new InMemoryWorkoutCompletionOutbox();
        WorkoutCompletionService service = new WorkoutCompletionService(
                fixture.sessions(), fixture.repository(), Clock.fixed(completedAt, ZoneOffset.UTC), outbox);

        service.complete(new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID),
                WorkoutSetTestFixture.SESSION_ID, 4, WorkoutCompletionService.CompletionType.FULL);
        service.complete(new AuthenticatedUserId(WorkoutSetTestFixture.USER_ID),
                WorkoutSetTestFixture.SESSION_ID, 4, WorkoutCompletionService.CompletionType.FULL);

        assertThat(outbox.eventCount()).isEqualTo(1);
        WorkoutCompletionOutbox.ClaimedEvent abandoned = outbox.claimNext(
                completedAt, completedAt.plusSeconds(30)).orElseThrow();
        assertThat(abandoned.sessionId()).isEqualTo(WorkoutSetTestFixture.SESSION_ID);

        AtomicInteger invoked = new AtomicInteger();
        Clock restartedClock = Clock.fixed(completedAt.plusSeconds(31), ZoneOffset.UTC);
        WorkoutCompletionOutboxProcessor restarted = new WorkoutCompletionOutboxProcessor(
                outbox, fixture.sessions(), fixture.repository(),
                List.of((user, session, facts) -> invoked.incrementAndGet()), restartedClock,
                Duration.ofSeconds(30), Duration.ofSeconds(5));

        assertThat(restarted.processNext()).isTrue();
        assertThat(restarted.processNext()).isFalse();
        assertThat(invoked).hasValue(1);
        assertThat(outbox.processedCount()).isEqualTo(1);
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
