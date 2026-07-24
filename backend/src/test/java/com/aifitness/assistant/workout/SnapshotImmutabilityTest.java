package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotImmutabilityTest {

    @Test
    void copiesExerciseAndEquipmentCollectionsAtSessionCreation() {
        UUID sessionId = UUID.randomUUID();
        Set<String> equipment = new HashSet<>(Set.of("DUMBBELL"));
        WorkoutExerciseSnapshot snapshot = new WorkoutExerciseSnapshot(
                UUID.randomUUID(), sessionId, UUID.randomUUID(), 1, "DB_SQUAT", "哑铃深蹲",
                "content-v1", equipment,
                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                WorkoutExerciseSnapshot.Status.PENDING);
        List<WorkoutExerciseSnapshot> source = new ArrayList<>(List.of(snapshot));

        WorkoutSession session = new WorkoutSession(
                sessionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2,
                UUID.randomUUID(), "DAY_A", "stable-client-key", WorkoutStatus.CREATED,
                Instant.parse("2026-07-24T08:00:00Z"), Optional.empty(), 0, source);
        source.clear();
        equipment.add("CABLE");

        assertThat(session.exercises()).hasSize(1);
        assertThat(session.exercises().getFirst().equipment()).containsExactly("DUMBBELL");
        assertThatThrownBy(() -> session.exercises().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> session.exercises().getFirst().equipment().add("BARBELL"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
