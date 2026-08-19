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
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExerciseReplacementTest {
    private static final AuthenticatedUserId USER = new AuthenticatedUserId(UUID.randomUUID());
    private static final PlanRulePolicy POLICY = new PlanRulePolicy(
            "test", new PlanRulePolicy.PlanLimits(2, 6, 8, 90),
            new PlanRulePolicy.Prescription(2, 4, 5, 15), new PlanRulePolicy.Rest(45, 240),
            new PlanRulePolicy.Duration(45, 75), new PlanRulePolicy.Balance(2, 12, 48));

    @Test
    void returnsOnlyReviewedEquipmentEligibleSamePatternMuscleAndDifficultyCandidates() {
        ExerciseCatalog.Exercise valid = exercise("VALID", "SQUAT", "BEGINNER", Set.of("LEGS", "GLUTES"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise validSecond = exercise("VALID_SECOND", "SQUAT", "BEGINNER", Set.of("LEGS", "GLUTES"), Set.of("BODYWEIGHT"), List.of());
        ExerciseCatalog.Exercise excluded = exercise("EXCLUDED", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise wrongPattern = exercise("WRONG_PATTERN", "HINGE", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise wrongMuscle = exercise("WRONG_MUSCLE", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise wrongDifficulty = exercise("WRONG_LEVEL", "SQUAT", "INTERMEDIATE", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise unavailable = exercise("UNAVAILABLE", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("BARBELL"), List.of());
        ExerciseCatalog.Exercise source = exercise("SOURCE", "SQUAT", "BEGINNER", Set.of("LEGS", "GLUTES"), Set.of("DUMBBELL"), List.of(
                alternative("VALID", 1, ReleaseStatus.AI_VALIDATED),
                alternative("VALID_SECOND", 2, ReleaseStatus.AI_VALIDATED),
                alternative("EXCLUDED", 3, ReleaseStatus.PUBLIC_RELEASE_APPROVED),
                alternative("WRONG_PATTERN", 4, ReleaseStatus.AI_VALIDATED),
                alternative("WRONG_MUSCLE", 5, ReleaseStatus.AI_VALIDATED),
                alternative("WRONG_LEVEL", 6, ReleaseStatus.AI_VALIDATED),
                alternative("UNAVAILABLE", 7, ReleaseStatus.AI_VALIDATED),
                alternative("DRAFT", 8, ReleaseStatus.AI_DRAFT)));
        ExerciseQueryService query = new ExerciseQueryService(
                repository(source, valid, validSecond, excluded, wrongPattern, wrongMuscle, wrongDifficulty, unavailable),
                userId -> Set.of("DUMBBELL"), ContentEnvironment.LOCAL);
        InMemoryProfileRepository profileRepository = new InMemoryProfileRepository();
        profileRepository.replacePreferences(USER.value(), 0, List.of(new PreferenceProfile.Preference(
                excluded.stableId(), PreferenceProfile.PreferenceType.EXCLUDED)));

        List<ExerciseCatalog.Exercise> result = new ExerciseReplacementService(
                query, new ProfileService(profileRepository), new InMemoryWorkoutSessionRepository(), POLICY)
                .candidates(USER, "SOURCE");

        assertThat(result).extracting(ExerciseCatalog.Exercise::code).containsExactly("VALID", "VALID_SECOND");
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
                query, new ProfileService(profileRepository), sessions, POLICY);

        WorkoutSession result = service.replace(USER, sessionId, snapshotId, "VALID", 1);

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.exercises().getFirst().exerciseCode()).isEqualTo("VALID");
        assertThat(result.exercises().getFirst().sourcePlanExerciseId()).isEqualTo(sourcePlanExerciseId);
        assertThat(result.exercises().getFirst().status()).isEqualTo(WorkoutExerciseSnapshot.Status.REPLACED);
    }

    @Test
    void externalLoadToBodyweightClearsWeightAndMarksBodyweight() {
        ReplacementFixture fixture = fixture(
                Set.of("DUMBBELL"), Set.of("BODYWEIGHT"), "KNOWN", Optional.of(new BigDecimal("18")));

        WorkoutExerciseSnapshot replaced = fixture.service().replace(
                USER, fixture.sessionId(), fixture.snapshotId(), "VALID", 1).exercises().getFirst();

        assertThat(replaced.prescription().weightStatus()).isEqualTo("BODYWEIGHT");
        assertThat(replaced.prescription().targetWeightKg()).isEmpty();
        assertThat(replaced.prescription().workSets()).isEqualTo(3);
        assertThat(replaced.prescription().repMin()).isEqualTo(8);
        assertThat(replaced.prescription().repMax()).isEqualTo(12);
        assertThat(replaced.prescription().restSeconds()).isEqualTo(90);
    }

    @Test
    void bodyweightToExternalLoadRequiresCalibrationAndHasNoTargetWeight() {
        ReplacementFixture fixture = fixture(
                Set.of("BODYWEIGHT"), Set.of("DUMBBELL"), "BODYWEIGHT", Optional.empty());

        WorkoutExerciseSnapshot replaced = fixture.service().replace(
                USER, fixture.sessionId(), fixture.snapshotId(), "VALID", 1).exercises().getFirst();

        assertThat(replaced.prescription().weightStatus()).isEqualTo("NEEDS_CALIBRATION");
        assertThat(replaced.prescription().targetWeightKg()).isEmpty();
    }

    @Test
    void invalidPrescriptionIsRejectedBeforeSnapshotMutation() {
        ReplacementFixture fixture = fixture(
                Set.of("DUMBBELL"), Set.of("DUMBBELL"), "KNOWN", Optional.of(new BigDecimal("18")),
                new WorkoutExerciseSnapshot.Prescription(5, 8, 12, 90, "KNOWN", Optional.of(new BigDecimal("18")), "KG"));

        assertThatThrownBy(() -> fixture.service().replace(
                USER, fixture.sessionId(), fixture.snapshotId(), "VALID", 1))
                .isInstanceOf(ExerciseReplacementService.IllegalReplacementException.class);

        WorkoutSession unchanged = fixture.sessions().findByIdAndUser(fixture.sessionId(), USER.value()).orElseThrow();
        assertThat(unchanged.version()).isEqualTo(1);
        assertThat(unchanged.exercises().getFirst().exerciseCode()).isEqualTo("SOURCE");
    }

    @Test
    void noCompatibleCandidateReturnsTypedFailureAndLeavesSnapshotUnchanged() {
        ExerciseCatalog.Exercise wrongMuscle = exercise(
                "WRONG", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise source = exercise(
                "SOURCE", "SQUAT", "BEGINNER", Set.of("LEGS", "GLUTES"), Set.of("DUMBBELL"),
                List.of(alternative("WRONG", 1, ReleaseStatus.AI_VALIDATED)));
        ExerciseQueryService query = new ExerciseQueryService(
                repository(source, wrongMuscle), userId -> Set.of("DUMBBELL"), ContentEnvironment.LOCAL);
        InMemoryWorkoutSessionRepository sessions = new InMemoryWorkoutSessionRepository();
        UUID sessionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        sessions.create(session(sessionId, snapshotId, Set.of("DUMBBELL"),
                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG")));
        ExerciseReplacementService service = new ExerciseReplacementService(
                query, new ProfileService(new InMemoryProfileRepository()), sessions, POLICY);

        assertThatThrownBy(() -> service.candidates(USER, "SOURCE"))
                .isInstanceOfSatisfying(ExerciseReplacementService.InsufficientReplacementsException.class,
                        failure -> assertThat(failure.availableCandidateCount()).isZero());
        assertThatThrownBy(() -> service.replace(USER, sessionId, snapshotId, "WRONG", 1))
                .isInstanceOf(ExerciseReplacementService.InsufficientReplacementsException.class);

        WorkoutSession unchanged = sessions.findByIdAndUser(sessionId, USER.value()).orElseThrow();
        assertThat(unchanged.version()).isEqualTo(1);
        assertThat(unchanged.exercises().getFirst().exerciseCode()).isEqualTo("SOURCE");
    }

    @Test
    void oneCompatibleCandidateIsReturnedInsteadOfBeingDiscarded() {
        ExerciseCatalog.Exercise valid = exercise(
                "VALID", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise source = exercise(
                "SOURCE", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"),
                List.of(alternative("VALID", 1, ReleaseStatus.AI_VALIDATED)));
        ExerciseReplacementService service = new ExerciseReplacementService(
                new ExerciseQueryService(repository(source, valid), userId -> Set.of("DUMBBELL"), ContentEnvironment.LOCAL),
                new ProfileService(new InMemoryProfileRepository()), new InMemoryWorkoutSessionRepository(), POLICY);

        assertThat(service.candidates(USER, "SOURCE"))
                .extracting(ExerciseCatalog.Exercise::code)
                .containsExactly("VALID");
    }

    @Test
    void sourceSnapshotRemainsClassifiableAfterItsEquipmentIsNoLongerAvailable() {
        ExerciseCatalog.Exercise valid = exercise(
                "VALID", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise source = exercise(
                "SOURCE", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("BARBELL"),
                List.of(alternative("VALID", 1, ReleaseStatus.AI_VALIDATED)));
        ExerciseReplacementService service = new ExerciseReplacementService(
                new ExerciseQueryService(repository(source, valid), userId -> Set.of("DUMBBELL"), ContentEnvironment.LOCAL),
                new ProfileService(new InMemoryProfileRepository()), new InMemoryWorkoutSessionRepository(), POLICY);

        assertThat(service.candidates(USER, "SOURCE"))
                .extracting(ExerciseCatalog.Exercise::code)
                .containsExactly("VALID");
    }

    @Test
    void currentWorkoutActionsAreExcludedFromCandidatesAndDirectReplacement() {
        ExerciseCatalog.Exercise valid = exercise(
                "VALID", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"), List.of());
        ExerciseCatalog.Exercise source = exercise(
                "SOURCE", "SQUAT", "BEGINNER", Set.of("LEGS"), Set.of("DUMBBELL"),
                List.of(alternative("VALID", 1, ReleaseStatus.AI_VALIDATED)));
        ExerciseQueryService query = new ExerciseQueryService(
                repository(source, valid), userId -> Set.of("DUMBBELL"), ContentEnvironment.LOCAL);
        InMemoryWorkoutSessionRepository sessions = new InMemoryWorkoutSessionRepository();
        UUID sessionId = UUID.randomUUID();
        UUID sourceSnapshotId = UUID.randomUUID();
        sessions.create(new WorkoutSession(
                sessionId, USER.value(), UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                "DAY_A", "duplicate-replacement-" + sessionId, WorkoutStatus.IN_PROGRESS,
                Instant.parse("2026-07-24T08:00:00Z"), Optional.empty(), 1,
                List.of(
                        new WorkoutExerciseSnapshot(
                                sourceSnapshotId, sessionId, UUID.randomUUID(), 1,
                                "SOURCE", "原动作", "1.0.0", Set.of("DUMBBELL"),
                                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                                WorkoutExerciseSnapshot.Status.ACTIVE),
                        new WorkoutExerciseSnapshot(
                                UUID.randomUUID(), sessionId, UUID.randomUUID(), 2,
                                "VALID", "已在训练中的动作", "1.0.0", Set.of("DUMBBELL"),
                                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                                WorkoutExerciseSnapshot.Status.PENDING))));
        ExerciseReplacementService service = new ExerciseReplacementService(
                query, new ProfileService(new InMemoryProfileRepository()), sessions, POLICY);

        assertThatThrownBy(() -> service.candidates(
                USER, sessionId, sourceSnapshotId, "SOURCE"))
                .isInstanceOf(ExerciseReplacementService.InsufficientReplacementsException.class);
        assertThatThrownBy(() -> service.replace(USER, sessionId, sourceSnapshotId, "VALID", 1))
                .isInstanceOf(ExerciseReplacementService.InsufficientReplacementsException.class);
        assertThat(sessions.findByIdAndUser(sessionId, USER.value()).orElseThrow().version())
                .isEqualTo(1);
    }

    private static ReplacementFixture fixture(
            Set<String> sourceEquipment, Set<String> replacementEquipment,
            String weightStatus, Optional<BigDecimal> targetWeight) {
        return fixture(sourceEquipment, replacementEquipment, weightStatus, targetWeight,
                new WorkoutExerciseSnapshot.Prescription(
                        3, 8, 12, 90, weightStatus, targetWeight, "KG"));
    }

    private static ReplacementFixture fixture(
            Set<String> sourceEquipment, Set<String> replacementEquipment,
            String weightStatus, Optional<BigDecimal> targetWeight,
            WorkoutExerciseSnapshot.Prescription prescription) {
        ExerciseCatalog.Exercise valid = exercise(
                "VALID", "SQUAT", "BEGINNER", Set.of("LEGS"), replacementEquipment, List.of());
        ExerciseCatalog.Exercise other = exercise(
                "OTHER", "SQUAT", "BEGINNER", Set.of("LEGS"), replacementEquipment, List.of());
        ExerciseCatalog.Exercise source = exercise(
                "SOURCE", "SQUAT", "BEGINNER", Set.of("LEGS"), sourceEquipment,
                List.of(alternative("VALID", 1, ReleaseStatus.AI_VALIDATED),
                        alternative("OTHER", 2, ReleaseStatus.AI_VALIDATED)));
        Set<String> available = new HashSet<>();
        available.addAll(sourceEquipment);
        available.addAll(replacementEquipment);
        available.remove("BODYWEIGHT");
        ExerciseQueryService query = new ExerciseQueryService(
                repository(source, valid, other), userId -> available, ContentEnvironment.LOCAL);
        InMemoryWorkoutSessionRepository sessions = new InMemoryWorkoutSessionRepository();
        UUID sessionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        sessions.create(session(sessionId, snapshotId, sourceEquipment, prescription));
        return new ReplacementFixture(
                new ExerciseReplacementService(
                        query, new ProfileService(new InMemoryProfileRepository()), sessions, POLICY),
                sessions, sessionId, snapshotId);
    }

    private static WorkoutSession session(
            UUID sessionId, UUID snapshotId, Set<String> equipment,
            WorkoutExerciseSnapshot.Prescription prescription) {
        return new WorkoutSession(
                sessionId, USER.value(), UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                "DAY_A", "replacement-session-key-" + sessionId, WorkoutStatus.IN_PROGRESS,
                Instant.parse("2026-07-24T08:00:00Z"), Optional.empty(), 1,
                List.of(new WorkoutExerciseSnapshot(
                        snapshotId, sessionId, UUID.randomUUID(), 1, "SOURCE", "原动作", "1.0.0",
                        equipment, prescription, WorkoutExerciseSnapshot.Status.ACTIVE)));
    }

    private record ReplacementFixture(
            ExerciseReplacementService service,
            InMemoryWorkoutSessionRepository sessions,
            UUID sessionId,
            UUID snapshotId) {}

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
