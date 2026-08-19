package com.aifitness.assistant.workout.application;

import java.util.Optional;
import java.util.Set;

/** Resolves the versioned exercise catalog identity to its primary muscles. */
@FunctionalInterface
public interface WorkoutMuscleCatalog {
    Optional<Set<String>> primaryMuscles(String exerciseCode, String contentVersion);
}
