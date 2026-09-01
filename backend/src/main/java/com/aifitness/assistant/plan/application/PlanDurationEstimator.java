package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared conservative preset-duration semantics. Rest follows a complete bilateral set. */
public final class PlanDurationEstimator {

    private final PlanRulePolicy.Duration duration;

    public PlanDurationEstimator(PlanRulePolicy.Duration duration) {
        this.duration = Objects.requireNonNull(duration, "duration policy must not be null");
    }

    public int estimateSeconds(PlanDraft.Day day) {
        Objects.requireNonNull(day, "plan day must not be null");
        boolean loadedExercisePresent = day.exercises().stream()
                .anyMatch(exercise -> exercise.weightStatus() != PlanDraft.WeightStatus.BODYWEIGHT);
        int estimatedSeconds = duration.sessionWarmupSeconds(loadedExercisePresent);
        Map<String, List<PlanDraft.Exercise>> groups = new LinkedHashMap<>();
        for (PlanDraft.Exercise exercise : day.exercises()) {
            if (exercise.executionGroup() == null) {
                estimatedSeconds += linearExerciseSeconds(exercise);
            } else {
                groups.computeIfAbsent(exercise.executionGroup(), ignored -> new ArrayList<>()).add(exercise);
            }
        }
        for (List<PlanDraft.Exercise> members : groups.values()) {
            estimatedSeconds += members.size() < 2
                    ? members.stream().mapToInt(this::linearExerciseSeconds).sum()
                    : executionGroupSeconds(members);
        }
        return estimatedSeconds;
    }

    public int linearExerciseSeconds(PlanDraft.Exercise exercise) {
        int workMultiplier = exercise.perSide() ? 2 : 1;
        return exercise.workSets()
                * (workMultiplier * duration.secondsPerWorkSet() + exercise.restSeconds())
                + duration.secondsPerExerciseTransition();
    }

    public int executionGroupSeconds(List<PlanDraft.Exercise> members) {
        int workSeconds = members.stream()
                .mapToInt(exercise -> exercise.workSets()
                        * (exercise.perSide() ? 2 : 1)
                        * duration.secondsPerWorkSet())
                .sum();
        int rounds = members.stream().mapToInt(PlanDraft.Exercise::workSets).max().orElseThrow();
        int restSeconds = members.stream().mapToInt(PlanDraft.Exercise::restSeconds).max().orElseThrow();
        return workSeconds
                + Math.max(0, rounds - 1) * restSeconds
                + duration.secondsPerExerciseTransition();
    }
}
