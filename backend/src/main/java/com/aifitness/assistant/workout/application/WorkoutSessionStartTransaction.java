package com.aifitness.assistant.workout.application;

import java.util.function.Supplier;

@FunctionalInterface
public interface WorkoutSessionStartTransaction {
    <T> T execute(Supplier<T> action);
}
