package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

/** Stable digest of the recovery rule and source facts; intentionally excludes clock-derived values. */
public final class WorkoutRecoveryAssessmentFingerprint {
    private WorkoutRecoveryAssessmentFingerprint() {}

    public static String create(WorkoutRecoveryAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment must not be null");
        MessageDigest digest = sha256();
        add(digest, "workout-recovery-assessment-v1");
        add(digest, assessment.policyVersion());
        add(digest, Integer.toString(assessment.minimumRecoveryHours()));
        add(digest, assessment.decision().name());
        assessment.affectedMuscles().stream()
                .sorted(Comparator.comparing(WorkoutRecoveryAssessment.AffectedMuscle::muscleGroup)
                        .thenComparing(WorkoutRecoveryAssessment.AffectedMuscle::lastCompletedAt))
                .forEach(affected -> {
                    add(digest, affected.muscleGroup());
                    add(digest, affected.lastCompletedAt().toString());
                    add(digest, Integer.toString(affected.minimumRecoveryHours()));
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
