package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Selects reviewed replacements without mutating the plan version or workout snapshot. */
public final class ExerciseReplacementService {
    private static final int MAX_RESULTS = 4;

    private final ExerciseQueryService exercises;
    private final ProfileService profiles;
    private final WorkoutSessionRepository sessions;
    private final PlanRulePolicy policy;

    public ExerciseReplacementService(
            ExerciseQueryService exercises, ProfileService profiles, WorkoutSessionRepository sessions,
            PlanRulePolicy policy) {
        this.exercises = Objects.requireNonNull(exercises, "exercises must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.policy = Objects.requireNonNull(policy, "plan rule policy must not be null");
    }

    public WorkoutSession replace(
            AuthenticatedUserId user, UUID sessionId, UUID snapshotId,
            String replacementCode, long expectedVersion) {
        WorkoutSession current = sessions.findByIdAndUser(sessionId, user.value())
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        WorkoutExerciseSnapshot source = current.exercises().stream()
                .filter(exercise -> exercise.id().equals(snapshotId))
                .findFirst().orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        ExerciseCatalog.Exercise replacement = candidates(
                user, sessionId, snapshotId, source.exerciseCode()).stream()
                .filter(candidate -> candidate.code().equals(replacementCode))
                .findFirst().orElseThrow(IllegalReplacementException::new);
        WorkoutExerciseSnapshot.Prescription prescription = replacementPrescription(
                source.prescription(), source.equipment(), replacement.equipment());
        WorkoutExerciseSnapshot overlay = new WorkoutExerciseSnapshot(
                source.id(), source.sessionId(), source.sourcePlanExerciseId(), source.order(),
                replacement.code(), replacement.name(), exercises.version(), replacement.equipment(),
                prescription, WorkoutExerciseSnapshot.Status.REPLACED);
        return sessions.replaceExercise(user.value(), sessionId, snapshotId, expectedVersion, overlay);
    }

    public List<ExerciseCatalog.Exercise> candidates(AuthenticatedUserId user, String sourceCode) {
        ExerciseCatalog.Exercise source = exercises.catalog().stream()
                .filter(exercise -> exercise.code().equals(sourceCode))
                .findFirst()
                .orElseThrow(ExerciseNotFoundException::new);
        return replacementCandidates(user, source, Set.of());
    }

    public List<ExerciseCatalog.Exercise> candidates(
            AuthenticatedUserId user, UUID sessionId, UUID snapshotId, String sourceCode) {
        WorkoutSession current = sessions.findByIdAndUser(sessionId, user.value())
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        WorkoutExerciseSnapshot source = current.exercises().stream()
                .filter(exercise -> exercise.id().equals(snapshotId))
                .findFirst().orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        if (sourceCode != null && !source.exerciseCode().equals(sourceCode)) {
            throw new ExerciseNotFoundException();
        }
        Set<String> existingCodes = current.exercises().stream()
                .filter(exercise -> !exercise.id().equals(snapshotId))
                .map(WorkoutExerciseSnapshot::exerciseCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ExerciseCatalog.Exercise catalogSource = exercises.catalog().stream()
                .filter(exercise -> exercise.code().equals(source.exerciseCode()))
                .findFirst()
                .orElseThrow(ExerciseNotFoundException::new);
        return replacementCandidates(user, catalogSource, existingCodes);
    }

    public String sourceCode(AuthenticatedUserId user, UUID sessionId, UUID snapshotId) {
        return sessions.findByIdAndUser(sessionId, user.value())
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new)
                .exercises().stream()
                .filter(exercise -> exercise.id().equals(snapshotId))
                .map(WorkoutExerciseSnapshot::exerciseCode)
                .findFirst()
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
    }

    private List<ExerciseCatalog.Exercise> replacementCandidates(
            AuthenticatedUserId user, ExerciseCatalog.Exercise source, Set<String> existingCodes) {
        Set<UUID> excluded = profiles.excludedExerciseIds(user);
        Set<String> seen = new HashSet<>();
        java.util.Map<String, ExerciseCatalog.Exercise> eligible = exercises
                .list(user, ExerciseQueryService.Filter.none()).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ExerciseCatalog.Exercise::code, exercise -> exercise));
        List<ExerciseCatalog.Exercise> candidates = source.alternatives().stream()
                .map(ExerciseCatalog.Alternative::exerciseCode)
                .filter(seen::add)
                .map(eligible::get)
                .filter(Objects::nonNull)
                .filter(candidate -> equivalent(source, candidate))
                .filter(candidate -> !excluded.contains(candidate.stableId()))
                .filter(candidate -> !existingCodes.contains(candidate.code()))
                .limit(MAX_RESULTS)
                .toList();
        if (candidates.isEmpty()) {
            throw new InsufficientReplacementsException(candidates.size());
        }
        return candidates;
    }

    private boolean equivalent(ExerciseCatalog.Exercise source, ExerciseCatalog.Exercise candidate) {
        return candidate.movementPattern().equals(source.movementPattern())
                && candidate.difficulty().equals(source.difficulty())
                && candidate.primaryMuscles().equals(source.primaryMuscles())
                && loadModesAreCompatible(source.equipment(), candidate.equipment());
    }

    /**
     * P0 supports two explicit load modes. Transitions between them are compatible only because
     * replacementPrescription performs the required weight-state conversion; mixed/empty modes are rejected.
     */
    private boolean loadModesAreCompatible(Set<String> sourceEquipment, Set<String> candidateEquipment) {
        return validLoadMode(sourceEquipment) && validLoadMode(candidateEquipment);
    }

    private boolean validLoadMode(Set<String> equipment) {
        return !equipment.isEmpty()
                && (isBodyweight(equipment) || !equipment.contains("BODYWEIGHT"));
    }

    private WorkoutExerciseSnapshot.Prescription replacementPrescription(
            WorkoutExerciseSnapshot.Prescription source,
            Set<String> sourceEquipment,
            Set<String> replacementEquipment) {
        validatePrescription(source);
        if (!loadModesAreCompatible(sourceEquipment, replacementEquipment)) {
            throw new IllegalReplacementException();
        }

        try {
            return source.forReplacement(sourceEquipment, replacementEquipment);
        } catch (IllegalArgumentException exception) {
            throw new IllegalReplacementException();
        }
    }

    private void validatePrescription(WorkoutExerciseSnapshot.Prescription prescription) {
        PlanRulePolicy.Prescription bounds = policy.prescription();
        PlanRulePolicy.Rest rest = policy.rest();
        boolean valid = prescription.workSets() >= bounds.minimumWorkSets()
                && prescription.workSets() <= bounds.maximumWorkSets()
                && prescription.repMin() >= bounds.minimumReps()
                && prescription.repMax() <= bounds.maximumReps()
                && prescription.restSeconds() >= rest.minimumSeconds()
                && prescription.restSeconds() <= rest.maximumSeconds()
                && Set.of("KNOWN", "NEEDS_CALIBRATION", "BODYWEIGHT")
                        .contains(prescription.weightStatus());
        if (!valid) {
            throw new IllegalReplacementException();
        }
    }

    private boolean isBodyweight(Set<String> equipment) {
        return equipment.size() == 1 && equipment.contains("BODYWEIGHT");
    }

    public static final class ExerciseNotFoundException extends RuntimeException {
        public ExerciseNotFoundException() {
            super("exercise not found");
        }
    }

    public static final class IllegalReplacementException extends RuntimeException {
        public IllegalReplacementException() { super("replacement exercise is not eligible"); }
    }

    public static final class InsufficientReplacementsException extends RuntimeException {
        private final int availableCandidateCount;

        public InsufficientReplacementsException(int availableCandidateCount) {
            super("no compatible replacement exercise is available");
            this.availableCandidateCount = availableCandidateCount;
        }

        public int availableCandidateCount() {
            return availableCandidateCount;
        }
    }
}
