package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.workout.application.WorkoutRecoveryAssessmentFingerprint;
import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkoutRecoveryAssessmentFingerprintTest {
    @Test
    void ignoresCheckedAtAndElapsedClockDerivativesButDetectsRuleOrFactChanges() {
        Instant completedAt = Instant.parse("2026-08-10T14:00:00Z");
        WorkoutRecoveryAssessment original = assessment("1.3.0", completedAt, 18, 48,
                Instant.parse("2026-08-11T08:00:00Z"));
        WorkoutRecoveryAssessment oneHourLater = assessment("1.3.0", completedAt, 19, 48,
                Instant.parse("2026-08-11T09:00:00Z"));
        WorkoutRecoveryAssessment changedFact = assessment("1.3.0", completedAt.plusSeconds(60), 18, 48,
                Instant.parse("2026-08-11T08:00:00Z"));
        WorkoutRecoveryAssessment changedPolicy = assessment("1.4.0", completedAt, 18, 48,
                Instant.parse("2026-08-11T08:00:00Z"));

        assertThat(WorkoutRecoveryAssessmentFingerprint.create(oneHourLater))
                .isEqualTo(WorkoutRecoveryAssessmentFingerprint.create(original));
        assertThat(WorkoutRecoveryAssessmentFingerprint.create(changedFact))
                .isNotEqualTo(WorkoutRecoveryAssessmentFingerprint.create(original));
        assertThat(WorkoutRecoveryAssessmentFingerprint.create(changedPolicy))
                .isNotEqualTo(WorkoutRecoveryAssessmentFingerprint.create(original));
    }

    private static WorkoutRecoveryAssessment assessment(
            String policyVersion, Instant lastCompletedAt, long elapsedHours,
            int minimumHours, Instant checkedAt) {
        return new WorkoutRecoveryAssessment(
                policyVersion, checkedAt, minimumHours,
                WorkoutRecoveryAssessment.Decision.CONFIRMATION_REQUIRED,
                List.of(new WorkoutRecoveryAssessment.AffectedMuscle(
                        "CHEST", elapsedHours, minimumHours, lastCompletedAt)));
    }
}
