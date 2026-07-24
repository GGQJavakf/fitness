package com.aifitness.assistant.workout;

import com.aifitness.assistant.content.application.ContentCatalogRepository;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.aifitness.assistant.content.domain.ReleaseMetadata;
import com.aifitness.assistant.content.domain.ReleaseStatus;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.PreferenceProfile;
import com.aifitness.assistant.profile.infrastructure.InMemoryProfileRepository;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExerciseReplacementTest {
    private static final AuthenticatedUserId USER = new AuthenticatedUserId(UUID.randomUUID());

    @Test
    void returnsOnlyReviewedEquipmentEligibleSamePatternMuscleAndDifficultyCandidates() {
        ExerciseCatalog.Exercise valid = exercise("VALID", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise excluded = exercise("EXCLUDED", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise wrongPattern = exercise("WRONG_PATTERN", "HINGE", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise wrongMuscle = exercise("WRONG_MUSCLE", "SQUAT", "BEGINNER", Set.of("CHEST"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise wrongDifficulty = exercise("WRONG_LEVEL", "SQUAT", "INTERMEDIATE", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise unavailable = exercise("UNAVAILABLE", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("BARBELL"), List.of());
        ExerciseCatalog.Exercise source = exercise("SOURCE", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of(
                alternative("VALID", 1, ReleaseStatus.AI_VALIDATED),
                alternative("EXCLUDED", 2, ReleaseStatus.PUBLIC_RELEASE_APPROVED),
                alternative("WRONG_PATTERN", 3, ReleaseStatus.AI_VALIDATED),
                alternative("WRONG_MUSCLE", 4, ReleaseStatus.AI_VALIDATED),
                alternative("WRONG_LEVEL", 5, ReleaseStatus.AI_VALIDATED),
                alternative("UNAVAILABLE", 6, ReleaseStatus.AI_VALIDATED),
                alternative("DRAFT", 7, ReleaseStatus.AI_DRAFT)));
        ExerciseQueryService query = new ExerciseQueryService(
                repository(source, valid, excluded, wrongPattern, wrongMuscle, wrongDifficulty, unavailable),
                userId -> Set.of("DUMBBELL"), ContentEnvironment.LOCAL);
        InMemoryProfileRepository profileRepository = new InMemoryProfileRepository();
        profileRepository.replacePreferences(USER.value(), 0, List.of(new PreferenceProfile.Preference(
                excluded.stableId(), PreferenceProfile.PreferenceType.EXCLUDED)));

        List<ExerciseCatalog.Exercise> result = new ExerciseReplacementService(
                query, new ProfileService(profileRepository), new InMemoryWorkoutSessionRepository())
                .candidates(USER, "SOURCE");

        assertThat(result).extracting(ExerciseCatalog.Exercise::code).containsExactly("VALID");
    }

    @Test
    void replacementChangesOnlyTheCurrentSessionOverlayAndPreservesPlanSourceIdentity() {
        ExerciseCatalog.Exercise valid = exercise("VALID", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise source = exercise("SOURCE", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"),
                List.of(alternative("VALID", 1, ReleaseStatus.AI_VALIDATED), alternative("OTHER", 2, ReleaseStatus.AI_VALIDATED)));
        ExerciseCatalog.Exercise other = exercise("OTHER", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseQueryService query = new ExerciseQueryService(repository(source, valid, other),
                userId -> Set.of("DUMBBELL"), ContentEnvironment.LOCAL);
        InMemoryProfileRepository profileRepository = new InMemoryProfileRepository();
        InMemoryWorkoutSessionRepository sessions = new InMemoryWorkoutSessionRepository();
        UUID sessionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID sourcePlanExerciseId = UUID.randomUUID();
        sessions.create(new WorkoutSession(
                sessionId, USER.value(), UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                "DAY_A", "replacement-session-key", WorkoutStatus.IN_PROGRESS, Instant.parse("2026-07-24T08:00:00Z"),
                Optional.empty(), 1, List.of(new WorkoutExerciseSnapshot(
                        snapshotId, sessionId, sourcePlanExerciseId, 1, "SOURCE", "原动作", "1.0.0",
                        Set.of("DUMBBELL"), new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                        WorkoutExerciseSnapshot.Status.ACTIVE))));
        ExerciseReplacementService service = new ExerciseReplacementService(
                query, new ProfileService(profileRepository), sessions);

        WorkoutSession result = service.replace(USER, sessionId, snapshotId, "VALID", 1);

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.exercises().getFirst().exerciseCode()).isEqualTo("VALID");
        assertThat(result.exercises().getFirst().sourcePlanExerciseId()).isEqualTo(sourcePlanExerciseId);
        assertThat(result.exercises().getFirst().status()).isEqualTo(WorkoutExerciseSnapshot.Status.REPLACED);
    }

    private static ExerciseCatalog.Alternative alternative(String code, int rank, ReleaseStatus status) {
        return new ExerciseCatalog.Alternative(code, rank, status);
    }

    private static ExerciseCatalog.Exercise exercise(
            String code, String pattern, String difficulty, Set<String> muscles, Set<String> equipment,
            List<ExerciseCatalog.Alternative> alternatives) {
        return new ExerciseCatalog.Exercise(code, code + " 动作", "这是一个便于理解的动作说明", pattern,
                difficulty, equipment, muscles, List.of("保持身体稳定", "在可控范围完成"), List.of("疼痛时停止"),
                "ORIGINAL_SUMMARY", true, new ExerciseCatalog.Image("asset://" + code, "asset://placeholder"), alternatives);
    }

    private static ContentCatalogRepository repository(ExerciseCatalog.Exercise... exercises) {
        ReleaseMetadata metadata = new ReleaseMetadata("1.0.0", ReleaseStatus.AI_VALIDATED, true,
                Set.of(ContentEnvironment.LOCAL), List.of("SOURCE"));
        return new ContentCatalogRepository() {
            @Override public ExerciseCatalog exercises() { return new ExerciseCatalog(metadata, List.of(exercises)); }
            @Override public PlanTemplateCatalog templates() { return new PlanTemplateCatalog(metadata, "1.0.0", List.of()); }
        };
    }
}
