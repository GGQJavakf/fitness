package com.aifitness.assistant.progression.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
        CompletedWorkoutProgressionObserver observer = observer(recommendations, List.of(new BigDecimal("2.5")));

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(set(1), set(2), set(3)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), snapshot.capture());
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.INCREASE);
        assertThat(decision.getValue().recommendedPrescription().weightKg()).isEqualByComparingTo("42.5");
        assertThat(decision.getValue().availableEquipmentSteps()).containsExactly(new BigDecimal("2.5"));
        assertThat(snapshot.getValue()).contains("progression-decision-snapshot-v1", "payloadDigest");
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
    void lockedWeightIsSuggestionOnlyAndNeverBecomesAnApplicableIncrease() {
        RecommendationService recommendations = mock(RecommendationService.class);
        CompletedWorkoutProgressionObserver observer = new CompletedWorkoutProgressionObserver(
                recommendations, (user, types) -> List.of(new BigDecimal("2.5")),
                (user, exercise, current) -> current, (user, workout, exercise) -> true,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));

        observer.onCompleted(new AuthenticatedUserId(USER_ID), session(), List.of(set(1), set(2), set(3)));

        ArgumentCaptor<ProgressionDecision> decision = ArgumentCaptor.forClass(ProgressionDecision.class);
        verify(recommendations).save(any(), any(), anyString(), any(), decision.capture(), anyString());
        assertThat(decision.getValue().decision()).isEqualTo(ProgressionDecision.Decision.KEEP);
        assertThat(decision.getValue().reasonCode()).isEqualTo(ProgressionDecision.ReasonCode.WEIGHT_USER_LOCKED);
        assertThat(decision.getValue().application()).isEqualTo(ProgressionDecision.Application.SUGGEST_ONLY);
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
                recommendations, (user, types) -> List.of(new BigDecimal("2.5")), history,
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

    private static CompletedWorkoutProgressionObserver observer(
            RecommendationService recommendations, List<BigDecimal> increments) {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        return new CompletedWorkoutProgressionObserver(recommendations, (user, types) -> increments, json,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));
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

    private static WorkoutSet set(int order) {
        return set(order, 12, 2);
    }

    private static WorkoutSet set(int order, int reps, int rir) {
        WorkoutSet.Performance performance = new WorkoutSet.Performance(new BigDecimal("40"), "KG", reps);
        return new WorkoutSet(
                new UUID(0, 100 + order), SESSION_ID, SESSION_EXERCISE_ID, "client-set-000" + order, order,
                WorkoutSet.SetType.WORK, order, performance, performance, rir,
                WorkoutSet.CompletionStatus.COMPLETED, Optional.of(COMPLETED_AT.minusSeconds(4 - order)), order,
                Optional.empty(), "a".repeat(64));
    }
}
