package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkoutStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");

    @Test
    void followsOnlyDocumentedSessionTransitions() {
        WorkoutSession created = session(WorkoutStatus.CREATED, 0);

        WorkoutSession active = created.transitionTo(WorkoutStatus.IN_PROGRESS, NOW.plusSeconds(1));
        WorkoutSession paused = active.transitionTo(WorkoutStatus.PAUSED, NOW.plusSeconds(2));
        WorkoutSession resumed = paused.transitionTo(WorkoutStatus.IN_PROGRESS, NOW.plusSeconds(3));
        WorkoutSession completing = resumed.transitionTo(WorkoutStatus.COMPLETING, NOW.plusSeconds(4));
        WorkoutSession completed = completing.transitionTo(WorkoutStatus.COMPLETED, NOW.plusSeconds(5));

        assertThat(completed.status()).isEqualTo(WorkoutStatus.COMPLETED);
        assertThat(completed.completedAt()).contains(NOW.plusSeconds(5));
        assertThat(completed.version()).isEqualTo(5);
    }

    @Test
    void terminalSessionsCannotBeReopened() {
        WorkoutSession completed = session(WorkoutStatus.COMPLETING, 3)
                .transitionTo(WorkoutStatus.COMPLETED, NOW.plusSeconds(1));
        WorkoutSession aborted = session(WorkoutStatus.CREATED, 0)
                .transitionTo(WorkoutStatus.ABORTED, NOW.plusSeconds(1));

        assertThatThrownBy(() -> completed.transitionTo(WorkoutStatus.IN_PROGRESS, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
        assertThatThrownBy(() -> aborted.transitionTo(WorkoutStatus.IN_PROGRESS, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ABORTED");
    }

    @Test
    void rejectsUndocumentedShortcuts() {
        assertThatThrownBy(() -> session(WorkoutStatus.CREATED, 0)
                .transitionTo(WorkoutStatus.COMPLETED, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session(WorkoutStatus.PAUSED, 2)
                .transitionTo(WorkoutStatus.COMPLETED, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static WorkoutSession session(WorkoutStatus status, long version) {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        return new WorkoutSession(
                sessionId,
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                UUID.fromString("00000000-0000-0000-0000-000000000103"),
                UUID.fromString("00000000-0000-0000-0000-000000000104"),
                1,
                UUID.fromString("00000000-0000-0000-0000-000000000105"),
                "DAY_1",
                "client-session-key",
                status,
                NOW,
                status.terminal() ? java.util.Optional.of(NOW) : java.util.Optional.empty(),
                version,
                List.of(snapshot(sessionId)));
    }

    static WorkoutExerciseSnapshot snapshot(UUID sessionId) {
        return new WorkoutExerciseSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000106"),
                sessionId,
                UUID.fromString("00000000-0000-0000-0000-000000000107"),
                1,
                "DUMBBELL_GOBLET_SQUAT",
                "哑铃高脚杯深蹲",
                "content-v1",
                Set.of("DUMBBELL"),
                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "NEEDS_CALIBRATION", "KG"),
                WorkoutExerciseSnapshot.Status.PENDING);
    }
}
