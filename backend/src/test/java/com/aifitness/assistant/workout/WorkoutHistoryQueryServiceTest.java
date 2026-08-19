package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutHistoryRepository;
import com.aifitness.assistant.workout.application.WorkoutHistoryQueryService;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSetRepository;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkoutHistoryQueryServiceTest {
    @Test
    void cursorPaginationIsStableAndOwnerScoped() {
        UUID user = new UUID(0, 1);
        var sessions = new InMemoryWorkoutSessionRepository();
        WorkoutSession newest = session(new UUID(0, 11), user, Instant.parse("2026-07-24T10:00:00Z"));
        WorkoutSession older = session(new UUID(0, 12), user, Instant.parse("2026-07-23T10:00:00Z"));
        sessions.create(older);
        sessions.create(newest);
        sessions.create(session(new UUID(0, 13), new UUID(0, 2), Instant.parse("2026-07-25T10:00:00Z")));
        WorkoutHistoryQueryService service = new WorkoutHistoryQueryService(
                sessions, new InMemoryWorkoutSetRepository(sessions),
                new com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutHistoryRepository(
                        sessions, new InMemoryWorkoutSetRepository(sessions)));

        WorkoutHistoryQueryService.Page first = service.list(new AuthenticatedUserId(user), Optional.empty(), 1);
        WorkoutHistoryQueryService.Page second = service.list(
                new AuthenticatedUserId(user), first.nextCursor(), 1);

        assertThat(first.items()).extracting(WorkoutHistoryQueryService.Item::sessionId).containsExactly(newest.id());
        assertThat(first.items()).extracting(WorkoutHistoryQueryService.Item::trainingDayName)
                .containsExactly("DAY_1");
        assertThat(first.nextCursor()).isPresent();
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).extracting(WorkoutHistoryQueryService.Item::sessionId).containsExactly(older.id());
        assertThat(second.nextCursor()).isEmpty();
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void historyUsesOneBoundedProjectionQueryRegardlessOfPageSize() {
        UUID user = new UUID(0, 1);
        CountingHistoryRepository history = new CountingHistoryRepository(List.of(
                projection(new UUID(0, 11), Instant.parse("2026-07-24T10:00:00Z")),
                projection(new UUID(0, 12), Instant.parse("2026-07-23T10:00:00Z")),
                projection(new UUID(0, 13), Instant.parse("2026-07-22T10:00:00Z"))));
        var sessions = new InMemoryWorkoutSessionRepository();
        WorkoutHistoryQueryService service = new WorkoutHistoryQueryService(
                sessions, new InMemoryWorkoutSetRepository(sessions), history);

        WorkoutHistoryQueryService.Page page = service.list(
                new AuthenticatedUserId(user), Optional.empty(), 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(history.calls).isEqualTo(1);
        assertThat(history.lastLimit).isEqualTo(3);
    }

    private static WorkoutHistoryRepository.Projection projection(UUID id, Instant startedAt) {
        return new WorkoutHistoryRepository.Projection(
                id, "DAY_1", "全身 A", WorkoutStatus.COMPLETED, startedAt, startedAt.plusSeconds(300),
                3, new BigDecimal("120.0"), 30, true);
    }

    private static final class CountingHistoryRepository implements WorkoutHistoryRepository {
        private final List<Projection> results;
        private int calls;
        private int lastLimit;

        private CountingHistoryRepository(List<Projection> results) {
            this.results = results;
        }

        @Override
        public List<Projection> findHistory(
                UUID userId, Optional<Instant> beforeStartedAt, Optional<UUID> beforeId, int limit) {
            calls += 1;
            lastLimit = limit;
            return results.stream().limit(limit).toList();
        }
    }

    private static WorkoutSession session(UUID id, UUID user, Instant startedAt) {
        UUID exerciseId = new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits() + 100);
        WorkoutExerciseSnapshot exercise = new WorkoutExerciseSnapshot(
                exerciseId, id, new UUID(0, id.getLeastSignificantBits() + 200), 1,
                "DB_ROW", "哑铃划船", "content-v1", Set.of("DUMBBELL"),
                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                WorkoutExerciseSnapshot.Status.COMPLETED);
        return new WorkoutSession(
                id, user, new UUID(0, id.getLeastSignificantBits() + 300),
                new UUID(0, id.getLeastSignificantBits() + 400), 1,
                new UUID(0, id.getLeastSignificantBits() + 500), "DAY_1", "session-key-" + id,
                WorkoutStatus.COMPLETED, startedAt, Optional.of(startedAt.plusSeconds(300)), 2, List.of(exercise));
    }
}
