package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.progression.application.EffectiveSetSelector;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.domain.EquipmentRoundingPolicy;
import com.aifitness.assistant.progression.domain.ProgressionDecision;
import com.aifitness.assistant.progression.domain.ProgressionEngine;
import com.aifitness.assistant.progression.domain.ProgressionInput;
import com.aifitness.assistant.progression.domain.ProgressionRulePolicy;
import com.aifitness.assistant.rules.domain.RuleEvaluationInput;
import com.aifitness.assistant.workout.application.WorkoutCompletionObserver;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Builds replayable progression recommendations from immutable completed-workout facts. */
public final class CompletedWorkoutProgressionObserver implements WorkoutCompletionObserver {
    static final String ALGORITHM_VERSION = "double-progression-v1";
    private static final BigDecimal REDUCTION_RATE = new BigDecimal("0.05");

    private final RecommendationService recommendations;
    private final EquipmentContextProvider equipment;
    private final HistoricalFactProvider history;
    private final WeightLockProvider locks;
    private final EffectiveSetSelector selector;
    private final ProgressionEngine engine;
    private final ProgressionRulePolicy policy;
    private final ObjectMapper json;
    private final Clock clock;

    public CompletedWorkoutProgressionObserver(
            RecommendationService recommendations,
            EquipmentContextProvider equipment,
            ObjectMapper json,
            Clock clock) {
        this(recommendations, equipment, (user, exercise, current) -> current,
                (user, session, exercise) -> false, new EffectiveSetSelector(), new ProgressionEngine(),
                ProgressionRulePolicy.defaults(), json, clock);
    }

    public CompletedWorkoutProgressionObserver(
            RecommendationService recommendations,
            EquipmentContextProvider equipment,
            HistoricalFactProvider history,
            ObjectMapper json,
            Clock clock) {
        this(recommendations, equipment, history, (user, session, exercise) -> false,
                new EffectiveSetSelector(), new ProgressionEngine(), ProgressionRulePolicy.defaults(), json, clock);
    }

    public CompletedWorkoutProgressionObserver(
            RecommendationService recommendations,
            EquipmentContextProvider equipment,
            HistoricalFactProvider history,
            WeightLockProvider locks,
            ObjectMapper json,
            Clock clock) {
        this(recommendations, equipment, history, locks, new EffectiveSetSelector(), new ProgressionEngine(),
                ProgressionRulePolicy.defaults(), json, clock);
    }

    public CompletedWorkoutProgressionObserver(
            RecommendationService recommendations,
            EquipmentContextProvider equipment,
            HistoricalFactProvider history,
            WeightLockProvider locks,
            ProgressionRulePolicy policy,
            ObjectMapper json,
            Clock clock) {
        this(recommendations, equipment, history, locks, new EffectiveSetSelector(), new ProgressionEngine(),
                policy, json, clock);
    }

    CompletedWorkoutProgressionObserver(
            RecommendationService recommendations,
            EquipmentContextProvider equipment,
            HistoricalFactProvider history,
            WeightLockProvider locks,
            EffectiveSetSelector selector,
            ProgressionEngine engine,
            ProgressionRulePolicy policy,
            ObjectMapper json,
            Clock clock) {
        this.recommendations = Objects.requireNonNull(recommendations);
        this.equipment = Objects.requireNonNull(equipment);
        this.history = Objects.requireNonNull(history);
        this.locks = Objects.requireNonNull(locks);
        this.selector = Objects.requireNonNull(selector);
        this.engine = Objects.requireNonNull(engine);
        this.policy = Objects.requireNonNull(policy);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void onCompleted(AuthenticatedUserId user, WorkoutSession session, List<WorkoutSet> facts) {
        if (!session.status().terminal() || session.status() != WorkoutStatus.COMPLETED) return;
        session.exercises().forEach(exercise -> generate(user, session, exercise, facts));
    }

    private void generate(
            AuthenticatedUserId user,
            WorkoutSession session,
            WorkoutExerciseSnapshot exercise,
            List<WorkoutSet> facts) {
        UUID exerciseId = stableExerciseId(exercise.exerciseCode());
        List<EffectiveSetSelector.RawSetFact> currentRaw = facts.stream()
                .filter(fact -> fact.sessionExerciseId().equals(exercise.id()))
                .map(fact -> rawFact(user, session, exerciseId, exercise, fact))
                .toList();
        ProgressionInput selected = selector.select(
                new EffectiveSetSelector.SelectionCriteria(
                        user.value(), exerciseId, exercise.exerciseCode(), "KG"),
                history.facts(user, exercise, currentRaw), clock.instant());
        DerivedSignals derived = derive(session, exercise, selected, policy);
        boolean bodyweight = "BODYWEIGHT".equals(exercise.prescription().weightStatus());
        List<EquipmentContext> contexts = bodyweight ? List.of() : equipment.contexts(user, exercise.equipment());
        EquipmentResolution resolution = resolveEquipment(
                exercise.equipment(), derived.currentWeightKg(), derived.hasUniqueActualWeight(), contexts, bodyweight);
        boolean conflicting = derived.conflictingInput() || resolution.conflicting();
        Integer rir = derived.rir().filter(value -> value <= 3).orElse(null);
        if (derived.rir().filter(value -> value > 3).isPresent()) conflicting = true;

        RuleEvaluationInput.Progression input = new RuleEvaluationInput.Progression(
                ALGORITHM_VERSION, RuleEvaluationInput.WeightUnit.KG, derived.historySufficient(),
                derived.painOrSafetyFlag(), derived.anomalousInput(), conflicting, derived.longTrainingGap(), false,
                bodyweight, derived.consecutiveBelowMin(), derived.multipleFailedSets(), derived.allSetsAtMax(),
                derived.consecutiveAllAtMax(), derived.oneSessionBelowMin(),
                locks.weightLocked(user, session, exercise), rir);
        ProgressionDecision.Prescription current = new ProgressionDecision.Prescription(
                derived.currentWeightKg(), exercise.prescription().repMin(), exercise.prescription().repMax());
        EquipmentRoundingPolicy rounding = new EquipmentRoundingPolicy(
                "KG", resolution.context().map(EquipmentContext::availableLevels).orElse(List.of()));
        ProgressionDecision decision = engine.evaluate(
                input, current, new ProgressionEngine.EnginePolicy(ALGORITHM_VERSION, REDUCTION_RATE), rounding);

        if (!requiresUserAttention(decision)) return;
        recommendations.save(
                user, exerciseId, exercise.exerciseCode(), session.id(), decision,
                snapshot(selected, input, derived, resolution, contexts));
    }

    private static EquipmentResolution resolveEquipment(
            Set<String> exerciseEquipment,
            BigDecimal actualWeight,
            boolean hasUniqueActualWeight,
            List<EquipmentContext> contexts,
            boolean bodyweight) {
        if (bodyweight) return new EquipmentResolution(Optional.empty(), false);
        if (!hasUniqueActualWeight) return new EquipmentResolution(Optional.empty(), true);
        List<EquipmentContext> exact = contexts.stream()
                .filter(context -> exerciseEquipment.contains(context.equipmentType()))
                .filter(context -> "KG".equals(context.unit()))
                .filter(context -> context.availableLevels().stream()
                        .anyMatch(level -> level.compareTo(actualWeight) == 0))
                .toList();
        return exact.size() == 1
                ? new EquipmentResolution(Optional.of(exact.getFirst()), false)
                : new EquipmentResolution(Optional.empty(), true);
    }

    private static boolean requiresUserAttention(ProgressionDecision decision) {
        if (decision.application() == ProgressionDecision.Application.RECOMMENDATION_PENDING) return true;
        if (decision.application() != ProgressionDecision.Application.REVIEW_REQUIRED) return false;
        return switch (decision.reasonCode()) {
            case PAIN_OR_SAFETY_FLAG, ANOMALOUS_INPUT, CONFLICTING_INPUT, LONG_TRAINING_GAP,
                    VARIANT_CHANGED, UNIT_CHANGED, BODYWEIGHT_REQUIRES_CONFIRMATION -> true;
            case INSUFFICIENT_HISTORY, CONSECUTIVE_BELOW_MIN, MULTIPLE_FAILED_SETS,
                    ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR, ALL_SETS_AT_MAX_TWICE_WITHOUT_RIR,
                    RIR_ZERO_AT_MAX, WITHIN_TARGET_RANGE, PARTIAL_AT_MAX, WEIGHT_USER_LOCKED -> false;
        };
    }

    private static EffectiveSetSelector.RawSetFact rawFact(
            AuthenticatedUserId user,
            WorkoutSession session,
            UUID exerciseId,
            WorkoutExerciseSnapshot exercise,
            WorkoutSet fact) {
        return new EffectiveSetSelector.RawSetFact(
                fact.id(), session.id(), user.value(), exerciseId, exercise.exerciseCode(),
                fact.actual().unit(), EffectiveSetSelector.SetKind.valueOf(fact.setType().name()), fact.setOrder(),
                EffectiveSetSelector.SessionOutcome.COMPLETED, factStatus(fact.completionStatus()),
                fact.actual().weight(), fact.actual().reps(), Optional.ofNullable(fact.remainingReps()),
                fact.safetyFlag().map(flag -> ProgressionInput.SafetyFlag.valueOf(flag.name())),
                fact.anomalyStatus().isPresent(), true,
                fact.completedAt().orElse(session.completedAt().orElseThrow()), fact.serverRevision(),
                fact.payloadDigest());
    }

    static UUID stableExerciseId(String exerciseCode) {
        return UUID.nameUUIDFromBytes(
                ("ai-fitness-exercise:" + exerciseCode).getBytes(StandardCharsets.UTF_8));
    }

    private static EffectiveSetSelector.FactStatus factStatus(WorkoutSet.CompletionStatus status) {
        return switch (status) {
            case COMPLETED -> EffectiveSetSelector.FactStatus.COMPLETED;
            case FAILED -> EffectiveSetSelector.FactStatus.FAILED;
            case PLANNED, SKIPPED -> EffectiveSetSelector.FactStatus.SKIPPED;
        };
    }

    private static DerivedSignals derive(
            WorkoutSession sourceSession,
            WorkoutExerciseSnapshot exercise,
            ProgressionInput selected,
            ProgressionRulePolicy policy) {
        UUID sourceSessionId = sourceSession.id();
        List<ProgressionInput.EffectiveSet> effective = selected.effectiveSets();
        List<ProgressionInput.EffectiveSet> current = effective.stream()
                .filter(value -> value.sessionId().equals(sourceSessionId)).toList();
        List<BigDecimal> weights = current.stream().map(ProgressionInput.EffectiveSet::weightKg)
                .map(BigDecimal::stripTrailingZeros).distinct().toList();
        boolean uniqueActual = weights.size() == 1;
        BigDecimal currentWeight = uniqueActual ? weights.getFirst() : BigDecimal.ZERO;
        boolean conflicting = weights.size() > 1 || current.isEmpty();
        boolean anomalous = selected.excludedSets().stream()
                .filter(value -> value.sessionId().equals(sourceSessionId))
                .flatMap(value -> value.reasons().stream())
                .anyMatch(reason -> reason == ProgressionInput.ExclusionReason.ANOMALOUS_INPUT);
        boolean enough = current.size() == exercise.prescription().workSets() && uniqueActual;
        boolean allAtMax = enough
                && current.stream().allMatch(value -> value.reps() >= exercise.prescription().repMax());
        boolean belowMin = current.stream().anyMatch(value -> value.reps() < exercise.prescription().repMin());
        Map<UUID, List<ProgressionInput.EffectiveSet>> sessions = effective.stream().collect(
                java.util.stream.Collectors.groupingBy(ProgressionInput.EffectiveSet::sessionId));
        List<List<ProgressionInput.EffectiveSet>> newestSessions = sessions.values().stream()
                .sorted(Comparator.comparing(CompletedWorkoutProgressionObserver::latestEffectiveAt).reversed())
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
        long failedCount = selected.failedSets().stream()
                .filter(value -> value.sessionId().equals(sourceSessionId)).count();
        boolean safety = selected.safetyFlags().stream()
                .anyMatch(value -> value.sessionId().equals(sourceSessionId));
        Optional<Instant> previousCompletedAt = previousSessionCompletedAt(selected, sourceSessionId);
        Instant currentCompletedAt = sourceSession.completedAt().orElseThrow();
        Optional<Long> actualGapDays = previousCompletedAt.map(previous ->
                Duration.between(previous, currentCompletedAt).toDays());
        if (actualGapDays.filter(value -> value < 0).isPresent()) conflicting = true;
        boolean longGap = actualGapDays.filter(value -> value >= policy.longTrainingGapDays()).isPresent();
        return new DerivedSignals(
                currentWeight, uniqueActual, enough, anomalous, conflicting, safety, longGap, actualGapDays,
                Math.toIntExact(failedCount), failedCount >= policy.multipleFailedSetsThreshold(),
                consecutiveBelow, allAtMax, consecutiveAtMax, belowMin, rir);
    }

    private static Optional<Instant> previousSessionCompletedAt(
            ProgressionInput selected, UUID sourceSessionId) {
        Stream<Map.Entry<UUID, Instant>> effective = selected.effectiveSets().stream()
                .map(value -> Map.entry(value.sessionId(), value.completedAt()));
        Stream<Map.Entry<UUID, Instant>> failed = selected.failedSets().stream()
                .map(value -> Map.entry(value.sessionId(), value.completedAt()));
        Stream<Map.Entry<UUID, Instant>> safety = selected.safetyFlags().stream()
                .map(value -> Map.entry(value.sessionId(), value.completedAt()));
        return Stream.of(effective, failed, safety).flatMap(value -> value)
                .filter(value -> !value.getKey().equals(sourceSessionId))
                .map(Map.Entry::getValue).max(Comparator.naturalOrder());
    }

    private static Instant latestEffectiveAt(List<ProgressionInput.EffectiveSet> values) {
        return values.stream().map(ProgressionInput.EffectiveSet::completedAt)
                .max(Comparator.naturalOrder()).orElseThrow();
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
            ProgressionInput selected,
            RuleEvaluationInput.Progression signals,
            DerivedSignals derived,
            EquipmentResolution resolution,
            List<EquipmentContext> consideredContexts) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "progression-decision-snapshot-v2");
        value.put("selectedInput", selected);
        value.put("signals", signals);
        Map<String, Object> derivation = new LinkedHashMap<>();
        derivation.put("currentWeightSource", derived.hasUniqueActualWeight()
                ? "CURRENT_COMPLETED_WORK_FACTS" : "MISSING_OR_CONFLICTING_ACTUAL_WEIGHT");
        derivation.put("trainingGapEvidence", derived.actualTrainingGapDays().isPresent()
                ? "ACTUAL_COMPLETED_SESSION_GAP" : "NO_PREVIOUS_COMPLETED_SESSION");
        derivation.put("actualTrainingGapDays", derived.actualTrainingGapDays().orElse(null));
        derivation.put("failedWorkSetCount", derived.failedWorkSetCount());
        derivation.put("longTrainingGapDays", policy.longTrainingGapDays());
        derivation.put("multipleFailedSetsThreshold", policy.multipleFailedSetsThreshold());
        value.put("derivedEvidence", derivation);
        value.put("equipmentContext", resolution.context().orElse(null));
        value.put("consideredEquipmentContexts", consideredContexts);
        value.put("ruleConfigVersion", policy.ruleConfigVersion());
        value.put("algorithmVersion", ALGORITHM_VERSION);
        value.put("reductionRate", REDUCTION_RATE);
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("progression snapshot cannot be serialized", exception);
        }
    }

    @FunctionalInterface
    public interface EquipmentContextProvider {
        List<EquipmentContext> contexts(AuthenticatedUserId user, Set<String> exerciseEquipment);
    }

    public record EquipmentContext(
            UUID clientEquipmentKey,
            String equipmentType,
            String unit,
            List<BigDecimal> availableLevels) {
        public EquipmentContext {
            Objects.requireNonNull(clientEquipmentKey, "client equipment key must not be null");
            if (equipmentType == null || equipmentType.isBlank() || !"KG".equals(unit)) {
                throw new IllegalArgumentException("equipment context type and unit are invalid");
            }
            availableLevels = new ArrayList<>(Objects.requireNonNull(
                    availableLevels, "available levels must not be null"));
            if (availableLevels.isEmpty()
                    || availableLevels.stream().anyMatch(level -> level == null || level.signum() <= 0)) {
                throw new IllegalArgumentException("equipment context must contain positive available levels");
            }
            availableLevels = availableLevels.stream().map(BigDecimal::stripTrailingZeros)
                    .distinct().sorted().toList();
        }
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

    private record EquipmentResolution(Optional<EquipmentContext> context, boolean conflicting) {}

    private record DerivedSignals(
            BigDecimal currentWeightKg,
            boolean hasUniqueActualWeight,
            boolean historySufficient,
            boolean anomalousInput,
            boolean conflictingInput,
            boolean painOrSafetyFlag,
            boolean longTrainingGap,
            Optional<Long> actualTrainingGapDays,
            int failedWorkSetCount,
            boolean multipleFailedSets,
            int consecutiveBelowMin,
            boolean allSetsAtMax,
            int consecutiveAllAtMax,
            boolean oneSessionBelowMin,
            Optional<Integer> rir) {}
}
