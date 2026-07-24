package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class WorkoutFactSummary {
    private WorkoutFactSummary() {}

    static List<WorkoutSet> completedPrescribedWorkSets(
            WorkoutSession session, List<WorkoutSet> facts) {
        Map<UUID, Integer> prescribed = session.exercises().stream().collect(Collectors.toUnmodifiableMap(
                exercise -> exercise.id(), exercise -> exercise.prescription().workSets()));
        Set<Position> seen = new HashSet<>();
        return facts.stream()
                .filter(set -> set.setType() == WorkoutSet.SetType.WORK)
                .filter(set -> set.completionStatus() == WorkoutSet.CompletionStatus.COMPLETED)
                .filter(set -> set.setOrder() <= prescribed.getOrDefault(set.sessionExerciseId(), 0))
                .filter(set -> seen.add(new Position(set.sessionExerciseId(), set.setOrder())))
                .toList();
    }

    static boolean hasFailedOrSkippedWorkSet(List<WorkoutSet> facts) {
        return facts.stream()
                .filter(set -> set.setType() == WorkoutSet.SetType.WORK)
                .anyMatch(set -> set.completionStatus() == WorkoutSet.CompletionStatus.FAILED
                        || set.completionStatus() == WorkoutSet.CompletionStatus.SKIPPED);
    }

    private record Position(UUID exerciseId, int setOrder) {}
}
