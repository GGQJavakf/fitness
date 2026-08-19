package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkoutRecoveryAssessmentTest {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");

    @Test
    void reportsOnlyOverlappingMusclesStillInsideTheVersionedWindow() {
        WorkoutRecoveryAssessment assessment = WorkoutRecoveryAssessment.evaluate(
                "rules-v1",
                48,
                NOW,
                Set.of("CHEST", "TRICEPS"),
                List.of(
                        new WorkoutRecoveryAssessment.CompletedMuscleFact(
                                NOW.minusSeconds(18 * 3600L), Set.of("CHEST")),
                        new WorkoutRecoveryAssessment.CompletedMuscleFact(
                                NOW.minusSeconds(12 * 3600L), Set.of("QUADRICEPS"))));

        assertThat(assessment.decision())
                .isEqualTo(WorkoutRecoveryAssessment.Decision.CONFIRMATION_REQUIRED);
        assertThat(assessment.policyVersion()).isEqualTo("rules-v1");
        assertThat(assessment.minimumRecoveryHours()).isEqualTo(48);
        assertThat(assessment.affectedMuscles()).containsExactly(
                new WorkoutRecoveryAssessment.AffectedMuscle(
                        "CHEST", 18, 48, NOW.minusSeconds(18 * 3600L)));
    }

    @Test
    void allowsWhenThereIsNoHistoryOrTheWindowHasElapsed() {
        assertThat(WorkoutRecoveryAssessment.evaluate(
                "rules-v1", 48, NOW, Set.of("CHEST"), List.of()).decision())
                .isEqualTo(WorkoutRecoveryAssessment.Decision.READY);

        assertThat(WorkoutRecoveryAssessment.evaluate(
                "rules-v1",
                48,
                NOW,
                Set.of("CHEST"),
                List.of(new WorkoutRecoveryAssessment.CompletedMuscleFact(
                        NOW.minusSeconds(48 * 3600L), Set.of("CHEST"))))
                .decision()).isEqualTo(WorkoutRecoveryAssessment.Decision.READY);
    }

    @Test
    void usesTheLatestActualCompletionForEachMuscle() {
        WorkoutRecoveryAssessment assessment = WorkoutRecoveryAssessment.evaluate(
                "rules-v1",
                48,
                NOW,
                Set.of("BACK"),
                List.of(
                        new WorkoutRecoveryAssessment.CompletedMuscleFact(
                                NOW.minusSeconds(30 * 3600L), Set.of("BACK")),
                        new WorkoutRecoveryAssessment.CompletedMuscleFact(
                                NOW.minusSeconds(8 * 3600L), Set.of("BACK"))));

        assertThat(assessment.affectedMuscles()).singleElement()
                .extracting(WorkoutRecoveryAssessment.AffectedMuscle::elapsedHours)
                .isEqualTo(8L);
    }
}
