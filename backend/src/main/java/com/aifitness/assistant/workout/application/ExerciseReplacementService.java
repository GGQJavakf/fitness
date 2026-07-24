package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.profile.application.ProfileService;
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

    public ExerciseReplacementService(
            ExerciseQueryService exercises, ProfileService profiles, WorkoutSessionRepository sessions) {
        this.exercises = Objects.requireNonNull(exercises, "exercises must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    }

    public WorkoutSession replace(
            AuthenticatedUserId user, UUID sessionId, UUID snapshotId,
            String replacementCode, long expectedVersion) {
        WorkoutSession current = sessions.findByIdAndUser(sessionId, user.value())
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        WorkoutExerciseSnapshot source = current.exercises().stream()
                .filter(exercise -> exercise.id().equals(snapshotId))
                .findFirst().orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        ExerciseCatalog.Exercise replacement = candidates(user, source.exerciseCode()).stream()
                .filter(candidate -> candidate.code().equals(replacementCode))
                .findFirst().orElseThrow(IllegalReplacementException::new);
        WorkoutExerciseSnapshot overlay = new WorkoutExerciseSnapshot(
                source.id(), source.sessionId(), source.sourcePlanExerciseId(), source.order(),
                replacement.code(), replacement.name(), exercises.version(), replacement.equipment(),
                source.prescription(), WorkoutExerciseSnapshot.Status.REPLACED);
        return sessions.replaceExercise(user.value(), sessionId, snapshotId, expectedVersion, overlay);
    }

    public List<ExerciseCatalog.Exercise> candidates(AuthenticatedUserId user, String sourceCode) {
        ExerciseCatalog.Exercise source = exercises.get(user, sourceCode)
                .orElseThrow(ExerciseNotFoundException::new);
        Set<UUID> excluded = profiles.excludedExerciseIds(user);
        Set<String> sourceMuscles = source.primaryMuscles();
        Set<String> seen = new HashSet<>();
        return source.alternatives().stream()
                .map(ExerciseCatalog.Alternative::exerciseCode)
                .filter(seen::add)
                .map(code -> exercises.get(user, code).orElse(null))
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.movementPattern().equals(source.movementPattern()))
                .filter(candidate -> candidate.difficulty().equals(source.difficulty()))
                .filter(candidate -> candidate.primaryMuscles().stream().anyMatch(sourceMuscles::contains))
                .filter(candidate -> !excluded.contains(candidate.stableId()))
                .limit(MAX_RESULTS)
                .toList();
    }

    public static final class ExerciseNotFoundException extends RuntimeException {
        public ExerciseNotFoundException() {
            super("exercise not found");
        }
    }

    public static final class IllegalReplacementException extends RuntimeException {
        public IllegalReplacementException() { super("replacement exercise is not eligible"); }
    }
}
