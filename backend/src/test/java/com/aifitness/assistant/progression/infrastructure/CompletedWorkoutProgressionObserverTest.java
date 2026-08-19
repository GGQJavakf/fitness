package com.aifitness.assistant.progression.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.application.EffectiveSetSelector;
import com.aifitness.assistant.progression.domain.ProgressionDecision;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CompletedWorkoutProgressionObserverTest {
    private static final UUID USER_ID = new UUID(0, 1);
    private static final UUID SESSION_ID = new UUID(0, 2);
    private static final UUID SESSION_EXERCISE_ID = new UUID(0, 3);
    private static final UUID PLAN_EXERCISE_ID = new UUID(0, 4);
    private static final Instant COMPLETED_AT = Instant.parse("2026-07-24T10:00:00Z");

    @Test
    void completedMaxRepsWithRirCreatesRoundedIncreaseFromConfiguredEquipment() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver observer = observer(recommendations, weightedLevels());

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(set(1), set(2), set(3)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        ArgumentCaptor<UUID> exerciseId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(recommendations).save(any(), exerciseId.capture(), anyString(), any(),
                decision.capture(), snapshot.capture());
        assertThat(exerciseId.getValue()).isEqualTo(UUID.nameUUIDFromBytes(
                "ai-fitness-exercise:DB_SQUAT".getBytes(StandardCharsets.UTF_8)));
        assertThat(exerciseId.getValue()).isNotEqualTo(PLAN_EXERCISE_ID);
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.INCREASE);
        assertThat(decision.getValue().recommendedPrescription().weightKg()).isEqualByComparingTo("42.5");
        assertThat(decision.getValue().availableEquipmentSteps()).extracting(BigDecimal::toPlainString)
                .containsExactly("37.5", "40", "42.5");
        assertThat(snapshot.getValue()).contains(
                "progression-decision-snapshot-v2", "payloadDigest",
                "\"trainingGapEvidence\":\"NO_PREVIOUS_COMPLETED_SESSION\"",
                "\"actualTrainingGapDays\":null");
    }

    @Test
    void missingEquipmentIncrementNeverGuessesAWeight() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver observer = observer(recommendations, List.of());

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(set(1), set(2), set(3)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), anyString());
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.REVIEW);
        assertThat(decision.getValue().reasonCode()).isEqualTo(ProgressionDecision.ReasonCode.CONFLICTING_INPUT);
        assertThat(decision.getValue().rawRecommendedWeight()).isEmpty();
    }

    @Test
    void lockedWeightDoesNotCreateAUserConfirmationTask() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver observer = new CompletedWorkoutProgressionObserver(
                recommendations, (user, types) -> contexts(weightedLevels()),
                (user, exercise, current) -> current, (user, workout, exercise) -> true,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(set(1), set(2), set(3)));

        verifyNoInteractions(recommendations);
    }

    @Test
    void twoConsecutiveBelowMinimumSessionsProduceAConservativeReduction() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver.HistoricalFactProvider history = (user, exercise, current) -> {
            List<EffectiveSetSelector.RawSetFact> previous = current.stream().map(value ->
                    new EffectiveSetSelector.RawSetFact(
                            UUID.randomUUID(), new UUID(0, 20), value.userId(), value.exerciseId(),
                            value.variantKey(), value.unit(), value.kind(), value.setOrder(), value.sessionOutcome(),
                            value.status(), value.weight(), value.reps(), value.remainingReps(), value.anomalous(),
                            value.currentRevision(), value.completedAt().minusSeconds(86400), value.serverRevision(),
                            value.payloadDigest())).toList();
            return java.util.stream.Stream.concat(previous.stream(), current.stream()).toList();
        };
        CompletedWorkoutProgressionObserver observer = new CompletedWorkoutProgressionObserver(
                recommendations, (user, types) -> contexts(weightedLevels()), history,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(),
                List.of(set(1, 6, 0), set(2, 6, 0), set(3, 6, 0)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), anyString());
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.REDUCE);
        assertThat(decision.getValue().recommendedPrescription().weightKg()).isEqualByComparingTo("37.5");
        assertThat(decision.getValue().reasonCode())
                .isEqualTo(ProgressionDecision.ReasonCode.CONSECUTIVE_BELOW_MIN);
    }

    @Test
    void bodyweightCompletionDoesNotCreateAUserConfirmationTask() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver observer = observer(recommendations, List.of());

        observer.onCompleted(new AuthenticatedUserId(USER_ID), bodyweightSession(),
                List.of(bodyweightSet(1, 10), bodyweightSet(2, 10), bodyweightSet(3, 10)));

        verifyNoInteractions(recommendations);
    }

    @Test
    void firstBodyweightSessionAtTheUpperRepLimitDoesNotCreateAConfirmationTask() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver observer = observer(recommendations, List.of());

        observer.onCompleted(new AuthenticatedUserId(USER_ID), bodyweightSession(),
                List.of(bodyweightSet(1, 12), bodyweightSet(2, 12), bodyweightSet(3, 12)));

        verifyNoInteractions(recommendations);
    }

    @Test
    void repeatedBodyweightSessionsAtTheUpperRepLimitCreateOneProgressionReview() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver.HistoricalFactProvider history = (user, exercise, current) -> {
            List<EffectiveSetSelector.RawSetFact> previous = current.stream().map(value ->
                    new EffectiveSetSelector.RawSetFact(
                            UUID.randomUUID(), new UUID(0, 20), value.userId(), value.exerciseId(),
                            value.variantKey(), value.unit(), value.kind(), value.setOrder(), value.sessionOutcome(),
                            value.status(), value.weight(), value.reps(), value.remainingReps(), value.anomalous(),
                            value.currentRevision(), value.completedAt().minusSeconds(86400), value.serverRevision(),
                            value.payloadDigest())).toList();
            return java.util.stream.Stream.concat(previous.stream(), current.stream()).toList();
        };
        CompletedWorkoutProgressionObserver observer = new CompletedWorkoutProgressionObserver(
                recommendations, (user, types) -> List.of(), history,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

        observer.onCompleted(new AuthenticatedUserId(USER_ID), bodyweightSession(),
                List.of(bodyweightSet(1, 12), bodyweightSet(2, 12), bodyweightSet(3, 12)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), anyString());
        assertThat(decision.getValue().reasonCode())
                .isEqualTo(ProgressionDecision.ReasonCode.BODYWEIGHT_REQUIRES_CONFIRMATION);
    }

    @Test
    void unchangedWeightedPrescriptionDoesNotCreateAUserConfirmationTask() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver observer = observer(recommendations, weightedLevels());

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(),
                List.of(set(1, 10, 2), set(2, 10, 2), set(3, 10, 2)));

        verifyNoInteractions(recommendations);
    }

    @Test
    void progressionUsesActualCompletedWeightInsteadOfPlanTarget() {
        RecommendationService recommendations = mock(RecommendationService.class);
        List<BigDecimal> levels = List.of(
                new BigDecimal("30"), new BigDecimal("35"), new BigDecimal("37.5"), new BigDecimal("40"));
        CompletedWorkoutProgressionObserver observer = observer(recommendations, levels);

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(
                set(1, 12, 2, "35", Optional.empty()),
                set(2, 12, 2, "35", Optional.empty()),
                set(3, 12, 2, "35", Optional.empty())));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), anyString());
        assertThat(decision.getValue().currentPrescription().weightKg()).isEqualByComparingTo("35");
        assertThat(decision.getValue().recommendedPrescription().weightKg()).isEqualByComparingTo("37.5");
    }

    @Test
    void conflictingActualWeightsRequireReviewInsteadOfUsingPlanTarget() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver observer = observer(recommendations, weightedLevels());

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(
                set(1, 12, 2, "37.5", Optional.empty()),
                set(2, 12, 2, "40", Optional.empty()),
                set(3, 12, 2, "40", Optional.empty())));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), snapshot.capture());
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.REVIEW);
        assertThat(decision.getValue().reasonCode()).isEqualTo(ProgressionDecision.ReasonCode.CONFLICTING_INPUT);
        assertThat(snapshot.getValue()).contains("MISSING_OR_CONFLICTING_ACTUAL_WEIGHT");
    }

    @Test
    void twoFailedWorkFactsCountEvenThoughTheyAreExcludedFromEffectiveVolume() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver observer = observer(recommendations, weightedLevels());

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(
                set(1), set(2), set(3), failedSet(4), failedSet(5)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), snapshot.capture());
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.REDUCE);
        assertThat(decision.getValue().reasonCode()).isEqualTo(ProgressionDecision.ReasonCode.MULTIPLE_FAILED_SETS);
        assertThat(snapshot.getValue()).contains("\"failedWorkSetCount\":2", "\"multipleFailedSets\":true");
    }

    @Test
    void twentyOneDayGapCreatesNonMedicalReviewWithActualGapEvidence() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver.HistoricalFactProvider history = (user, exercise, current) -> {
            List<EffectiveSetSelector.RawSetFact> previous = current.stream().map(value ->
                    new EffectiveSetSelector.RawSetFact(
                            UUID.randomUUID(), new UUID(0, 20), value.userId(), value.exerciseId(),
                            value.variantKey(), value.unit(), value.kind(), value.setOrder(), value.sessionOutcome(),
                            value.status(), value.weight(), value.reps(), value.remainingReps(), value.safetyFlag(),
                            value.anomalous(), value.currentRevision(), value.completedAt().minusSeconds(21L * 86400),
                            value.serverRevision(), value.payloadDigest())).toList();
            return java.util.stream.Stream.concat(previous.stream(), current.stream()).toList();
        };
        CompletedWorkoutProgressionObserver observer = new CompletedWorkoutProgressionObserver(
                recommendations, (user, types) -> contexts(weightedLevels()), history,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(set(1), set(2), set(3)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), snapshot.capture());
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.REVIEW);
        assertThat(decision.getValue().reasonCode()).isEqualTo(ProgressionDecision.ReasonCode.LONG_TRAINING_GAP);
        assertThat(snapshot.getValue()).contains(
                "\"trainingGapEvidence\":\"ACTUAL_COMPLETED_SESSION_GAP\"",
                "\"actualTrainingGapDays\":21", "\"longTrainingGapDays\":21");
    }

    @Test
    void safetyFlagAlwaysOverridesStrongPerformanceAndAmbiguousEquipment() {
        RecommendationService recommendations = mock(RecommendationService.class);
        List<CompletedWorkoutProgressionObserver.EquipmentContext> ambiguous = List.of(
                new CompletedWorkoutProgressionObserver.EquipmentContext(
                        new UUID(0, 91), "DUMBBELL", "KG", weightedLevels()),
                new CompletedWorkoutProgressionObserver.EquipmentContext(
                        new UUID(0, 92), "DUMBBELL", "KG", weightedLevels()));
        CompletedWorkoutProgressionObserver observer = new CompletedWorkoutProgressionObserver(
                recommendations, (user, types) -> ambiguous,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(
                set(1, 12, 2, "40", Optional.of(WorkoutSet.SafetyFlag.DIZZINESS)), set(2), set(3)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), anyString());
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.REVIEW);
        assertThat(decision.getValue().reasonCode()).isEqualTo(ProgressionDecision.ReasonCode.PAIN_OR_SAFETY_FLAG);
    }

    @Test
    void multipleMatchingSameTypeEquipmentContextsAreNeverMerged() {
        RecommendationService recommendations = mock(RecommendationService.class);
        List<CompletedWorkoutProgressionObserver.EquipmentContext> ambiguous = List.of(
                new CompletedWorkoutProgressionObserver.EquipmentContext(
                        new UUID(0, 91), "DUMBBELL", "KG", weightedLevels()),
                new CompletedWorkoutProgressionObserver.EquipmentContext(
                        new UUID(0, 92), "DUMBBELL", "KG", List.of(
                                new BigDecimal("40"), new BigDecimal("45"))));
        CompletedWorkoutProgressionObserver observer = new CompletedWorkoutProgressionObserver(
                recommendations, (user, types) -> ambiguous,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(set(1), set(2), set(3)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), anyString());
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.REVIEW);
        assertThat(decision.getValue().reasonCode()).isEqualTo(ProgressionDecision.ReasonCode.CONFLICTING_INPUT);
    }

    private static CompletedWorkoutProgressionObserver observer(
            RecommendationService recommendations, List<BigDecimal> levels) {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        return new CompletedWorkoutProgressionObserver(recommendations, (user, types) -> contexts(levels), json,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));
    }

    private static List<CompletedWorkoutProgressionObserver.EquipmentContext> contexts(
            List<BigDecimal> levels) {
        if (levels.isEmpty()) return List.of();
        return List.of(new CompletedWorkoutProgressionObserver.EquipmentContext(
                new UUID(0, 99), "DUMBBELL", "KG", levels));
    }

    private static List<BigDecimal> weightedLevels() {
        return List.of(new BigDecimal("37.5"), new BigDecimal("40"), new BigDecimal("42.5"));
    }

    private static WorkoutSession session() {
        WorkoutExerciseSnapshot exercise = new WorkoutExerciseSnapshot(
                SESSION_EXERCISE_ID, SESSION_ID, PLAN_EXERCISE_ID, 1, "DB_SQUAT", "哑铃深蹲", "content-v1",
                Set.of("DUMBBELL"), new WorkoutExerciseSnapshot.Prescription(
                        3, 8, 12, 90, "KNOWN", Optional.of(new BigDecimal("40")), "KG"),
                WorkoutExerciseSnapshot.Status.COMPLETED);
        return new WorkoutSession(
                SESSION_ID, USER_ID, new UUID(0, 5), new UUID(0, 6), 1, new UUID(0, 7), "DAY_1",
                "session-key-0001", WorkoutStatus.COMPLETED, COMPLETED_AT.minusSeconds(1800),
                Optional.of(COMPLETED_AT), 5, List.of(exercise));
    }

    private static WorkoutSession bodyweightSession() {
        WorkoutExerciseSnapshot exercise = new WorkoutExerciseSnapshot(
                SESSION_EXERCISE_ID, SESSION_ID, PLAN_EXERCISE_ID, 1, "BODYWEIGHT_SQUAT", "自重深蹲",
                "content-v1", Set.of(), new WorkoutExerciseSnapshot.Prescription(
                        3, 8, 12, 90, "BODYWEIGHT", Optional.empty(), "KG"),
                WorkoutExerciseSnapshot.Status.COMPLETED);
        return new WorkoutSession(
                SESSION_ID, USER_ID, new UUID(0, 5), new UUID(0, 6), 1, new UUID(0, 7), "BODYWEIGHT_A",
                "session-key-0001", WorkoutStatus.COMPLETED, COMPLETED_AT.minusSeconds(1800),
                Optional.of(COMPLETED_AT), 5, List.of(exercise));
    }

    private static WorkoutSet set(int order) {
        return set(order, 12, 2);
    }

    private static WorkoutSet set(int order, int reps, int rir) {
        return set(order, reps, rir, "40", Optional.empty());
    }

    private static WorkoutSet set(
            int order, int reps, int rir, String weight, Optional<WorkoutSet.SafetyFlag> safetyFlag) {
        WorkoutSet.Performance target = new WorkoutSet.Performance(new BigDecimal("40"), "KG", reps);
        WorkoutSet.Performance actual = new WorkoutSet.Performance(new BigDecimal(weight), "KG", reps);
        return new WorkoutSet(
                new UUID(0, 100 + order), SESSION_ID, SESSION_EXERCISE_ID, "client-set-000" + order, order,
                WorkoutSet.SetType.WORK, order, target, actual, rir,
                WorkoutSet.CompletionStatus.COMPLETED, Optional.of(COMPLETED_AT.minusSeconds(4 - order)), order,
                safetyFlag, Optional.empty(), "a".repeat(64));
    }

    private static WorkoutSet failedSet(int order) {
        WorkoutSet.Performance target = new WorkoutSet.Performance(new BigDecimal("40"), "KG", 8);
        WorkoutSet.Performance actual = new WorkoutSet.Performance(new BigDecimal("40"), "KG", 0);
        return new WorkoutSet(
                new UUID(0, 100 + order), SESSION_ID, SESSION_EXERCISE_ID, "client-set-000" + order, order,
                WorkoutSet.SetType.WORK, order, target, actual, 0,
                WorkoutSet.CompletionStatus.FAILED, Optional.empty(), order,
                Optional.empty(), Optional.empty(), "f".repeat(64));
    }

    private static WorkoutSet bodyweightSet(int order, int reps) {
        WorkoutSet.Performance performance = new WorkoutSet.Performance(BigDecimal.ZERO, "KG", reps);
        return new WorkoutSet(
                new UUID(0, 200 + order), SESSION_ID, SESSION_EXERCISE_ID, "bodyweight-set-" + order, order,
                WorkoutSet.SetType.WORK, order, performance, performance, 2,
                WorkoutSet.CompletionStatus.COMPLETED, Optional.of(COMPLETED_AT.minusSeconds(4 - order)), order,
                Optional.empty(), "b".repeat(64));
    }
}
