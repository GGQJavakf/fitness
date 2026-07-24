package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.progression.application.EffectiveSetSelector;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.domain.EquipmentRoundingPolicy;
import com.aifitness.assistant.progression.domain.ProgressionDecision;
import com.aifitness.assistant.progression.domain.ProgressionEngine;
import com.aifitness.assistant.progression.domain.ProgressionInput;
import com.aifitness.assistant.rules.domain.RuleEvaluationInput;
import com.aifitness.assistant.workout.application.WorkoutCompletionObserver;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Builds replayable progression recommendations from immutable completed-workout facts. */
public final class CompletedWorkoutProgressionObserver implements WorkoutCompletionObserver {
    static final String ALGORITHM_VERSION = "double-progression-v1";
    private static final BigDecimal REDUCTION_RATE = new BigDecimal("0.05");
    private static final BigDecimal SAFE_REVIEW_SENTINEL_STEP = BigDecimal.ONE;

    private final RecommendationService recommendations;
    private final EquipmentIncrementProvider equipment;
    private final HistoricalFactProvider history;
    private final WeightLockProvider locks;
    private final EffectiveSetSelector selector;
    private final ProgressionEngine engine;
    private final ObjectMapper json;
    private final Clock clock;

    public CompletedWorkoutProgressionObserver(
            RecommendationService recommendations,
            EquipmentIncrementProvider equipment,
            ObjectMapper json,
            Clock clock) {
        this(recommendations, equipment, (user, exercise, current) -> current,
                (user, session, exercise) -> false,
                new EffectiveSetSelector(), new ProgressionEngine(), json, clock);
    }

    public CompletedWorkoutProgressionObserver(
            RecommendationService recommendations,
            EquipmentIncrementProvider equipment,
            HistoricalFactProvider history,
            ObjectMapper json,
            Clock clock) {
        this(recommendations, equipment, history, (user, session, exercise) -> false,
                new EffectiveSetSelector(), new ProgressionEngine(), json, clock);
    }

    public CompletedWorkoutProgressionObserver(
            RecommendationService recommendations,
            EquipmentIncrementProvider equipment,
            HistoricalFactProvider history,
            WeightLockProvider locks,
            ObjectMapper json,
            Clock clock) {
        this(recommendations, equipment, history, locks,
                new EffectiveSetSelector(), new ProgressionEngine(), json, clock);
    }

    CompletedWorkoutProgressionObserver(
            RecommendationService recommendations,
            EquipmentIncrementProvider equipment,
            HistoricalFactProvider history,
            WeightLockProvider locks,
            EffectiveSetSelector selector,
            ProgressionEngine engine,
            ObjectMapper json,
            Clock clock) {
        this.recommendations = Objects.requireNonNull(recommendations);
        this.equipment = Objects.requireNonNull(equipment);
        this.history = Objects.requireNonNull(history);
        this.locks = Objects.requireNonNull(locks);
        this.selector = Objects.requireNonNull(selector);
        this.engine = Objects.requireNonNull(engine);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void onCompleted(AuthenticatedUserId user, WorkoutSession session, List<WorkoutSet> facts) {
        if (!session.status().terminal() || session.status() != com.aifitness.assistant.workout.domain.WorkoutStatus.COMPLETED) {
            return;
        }
        session.exercises().forEach(exercise -> generate(user, session, exercise, facts));
    }

    private void generate(
            AuthenticatedUserId user,
            WorkoutSession session,
            WorkoutExerciseSnapshot exercise,
            List<WorkoutSet> facts) {
        List<EffectiveSetSelector.RawSetFact> rawFacts = facts.stream()
                .filter(fact -> fact.sessionExerciseId().equals(exercise.id()))
                .map(fact -> rawFact(user, session, exercise, fact))
                .toList();
        List<EffectiveSetSelector.RawSetFact> historicalFacts = history.facts(user, exercise, rawFacts);
        ProgressionInput selected = selector.select(new EffectiveSetSelector.SelectionCriteria(
                user.value(), exercise.sourcePlanExerciseId(), exercise.exerciseCode(), "KG"), historicalFacts,
                clock.instant());
        DerivedSignals derived = derive(session.id(), exercise, selected);
        List<BigDecimal> increments = equipment.increments(user, exercise.equipment());
        boolean bodyweight = "BODYWEIGHT".equals(exercise.prescription().weightStatus());
        boolean conflicting = derived.conflictingInput() || increments.isEmpty() && !bodyweight;
        Integer rir = derived.rir().filter(value -> value <= 3).orElse(null);
        if (derived.rir().filter(value -> value > 3).isPresent()) conflicting = true;

        RuleEvaluationInput.Progression input = new RuleEvaluationInput.Progression(
                ALGORITHM_VERSION, RuleEvaluationInput.WeightUnit.KG, derived.historySufficient(), false,
                derived.anomalousInput(), conflicting, false,
                exercise.status() == WorkoutExerciseSnapshot.Status.REPLACED, bodyweight,
                derived.consecutiveBelowMin(), false, derived.allSetsAtMax(), derived.consecutiveAllAtMax(),
                derived.oneSessionBelowMin(), locks.weightLocked(user, session, exercise), rir);
        ProgressionDecision.Prescription current = new ProgressionDecision.Prescription(
                derived.currentWeightKg(), exercise.prescription().repMin(), exercise.prescription().repMax());
        EquipmentRoundingPolicy rounding = new EquipmentRoundingPolicy(
                "KG", increments.isEmpty() ? List.of(SAFE_REVIEW_SENTINEL_STEP) : increments);
        ProgressionDecision decision = engine.evaluate(input, current,
                new ProgressionEngine.EnginePolicy(ALGORITHM_VERSION, REDUCTION_RATE), rounding);

        recommendations.save(user, exercise.sourcePlanExerciseId(), exercise.exerciseCode(), session.id(), decision,
                snapshot(selected, input, increments));
    }

    private EffectiveSetSelector.RawSetFact rawFact(
            AuthenticatedUserId user,
            WorkoutSession session,
            WorkoutExerciseSnapshot exercise,
            WorkoutSet fact) {
        return new EffectiveSetSelector.RawSetFact(
                fact.id(), session.id(), user.value(), exercise.sourcePlanExerciseId(), exercise.exerciseCode(),
                fact.actual().unit(), EffectiveSetSelector.SetKind.valueOf(fact.setType().name()), fact.setOrder(),
                EffectiveSetSelector.SessionOutcome.COMPLETED, factStatus(fact.completionStatus()), fact.actual().weight(),
                fact.actual().reps(), Optional.ofNullable(fact.remainingReps()), fact.anomalyStatus().isPresent(), true,
                fact.completedAt().orElse(session.completedAt().orElseThrow()), fact.serverRevision(),
                fact.payloadDigest());
    }

    private static EffectiveSetSelector.FactStatus factStatus(WorkoutSet.CompletionStatus status) {
        return switch (status) {
            case COMPLETED -> EffectiveSetSelector.FactStatus.COMPLETED;
            case FAILED -> EffectiveSetSelector.FactStatus.FAILED;
            case PLANNED, SKIPPED -> EffectiveSetSelector.FactStatus.SKIPPED;
        };
    }

    private static DerivedSignals derive(
            UUID sourceSessionId, WorkoutExerciseSnapshot exercise, ProgressionInput selected) {
        List<ProgressionInput.EffectiveSet> effective = selected.effectiveSets();
        List<ProgressionInput.EffectiveSet> current = effective.stream()
                .filter(value -> value.sessionId().equals(sourceSessionId)).toList();
        List<BigDecimal> weights = current.stream().map(ProgressionInput.EffectiveSet::weightKg).distinct().toList();
        BigDecimal currentWeight = exercise.prescription().targetWeightKg()
                .orElseGet(() -> weights.size() == 1 ? weights.getFirst() : BigDecimal.ZERO);
        boolean conflicting = weights.size() > 1 || current.isEmpty();
        boolean anomalous = selected.excludedSets().stream().flatMap(value -> value.reasons().stream())
                .anyMatch(reason -> reason == ProgressionInput.ExclusionReason.ANOMALOUS_INPUT);
        boolean enough = current.size() == exercise.prescription().workSets() && !current.isEmpty();
        boolean allAtMax = enough && current.stream().allMatch(value -> value.reps() >= exercise.prescription().repMax());
        boolean belowMin = current.stream().anyMatch(value -> value.reps() < exercise.prescription().repMin());
        Map<UUID, List<ProgressionInput.EffectiveSet>> sessions = effective.stream().collect(
                java.util.stream.Collectors.groupingBy(ProgressionInput.EffectiveSet::sessionId));
        List<List<ProgressionInput.EffectiveSet>> newestSessions = sessions.values().stream()
                .sorted(java.util.Comparator.comparing((List<ProgressionInput.EffectiveSet> values) ->
                        values.stream().map(ProgressionInput.EffectiveSet::completedAt).max(java.util.Comparator.naturalOrder())
                                .orElseThrow()).reversed())
                .toList();
        int consecutiveBelow = consecutive(newestSessions, values ->
                values.size() == exercise.prescription().workSets()
                        && values.stream().anyMatch(value -> value.reps() < exercise.prescription().repMin()));
        int consecutiveAtMax = consecutive(newestSessions, values ->
                values.size() == exercise.prescription().workSets()
                        && values.stream().allMatch(value -> value.reps() >= exercise.prescription().repMax()));
        boolean allRirPresent = !current.isEmpty()
                && current.stream().allMatch(value -> value.remainingReps().isPresent());
        Optional<Integer> rir = allRirPresent
                ? current.stream().map(value -> value.remainingReps().orElseThrow()).min(Integer::compareTo)
                : Optional.empty();
        return new DerivedSignals(currentWeight, enough, anomalous, conflicting, consecutiveBelow,
                allAtMax, consecutiveAtMax, belowMin, rir);
    }

    private static int consecutive(
            List<List<ProgressionInput.EffectiveSet>> sessions,
            java.util.function.Predicate<List<ProgressionInput.EffectiveSet>> matches) {
        int count = 0;
        for (List<ProgressionInput.EffectiveSet> values : sessions) {
            if (!matches.test(values)) break;
            count++;
        }
        return count;
    }

    private String snapshot(
            ProgressionInput selected, RuleEvaluationInput.Progression signals, List<BigDecimal> increments) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "progression-decision-snapshot-v1");
        value.put("selectedInput", selected);
        value.put("signals", signals);
        value.put("equipmentStepsKg", increments);
        value.put("algorithmVersion", ALGORITHM_VERSION);
        value.put("reductionRate", REDUCTION_RATE);
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("progression snapshot cannot be serialized", exception);
        }
    }

    @FunctionalInterface
    public interface EquipmentIncrementProvider {
        List<BigDecimal> increments(AuthenticatedUserId user, Set<String> exerciseEquipment);
    }

    @FunctionalInterface
    public interface HistoricalFactProvider {
        List<EffectiveSetSelector.RawSetFact> facts(
                AuthenticatedUserId user,
                WorkoutExerciseSnapshot exercise,
                List<EffectiveSetSelector.RawSetFact> currentFacts);
    }

    @FunctionalInterface
    public interface WeightLockProvider {
        boolean weightLocked(
                AuthenticatedUserId user, WorkoutSession session, WorkoutExerciseSnapshot exercise);
    }

    private record DerivedSignals(
            BigDecimal currentWeightKg,
            boolean historySufficient,
            boolean anomalousInput,
            boolean conflictingInput,
            int consecutiveBelowMin,
            boolean allSetsAtMax,
            int consecutiveAllAtMax,
            boolean oneSessionBelowMin,
            Optional<Integer> rir) {}
}
