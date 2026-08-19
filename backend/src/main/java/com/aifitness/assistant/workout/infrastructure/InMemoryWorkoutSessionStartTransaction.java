package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSessionStartTransaction;
import java.util.Objects;
import java.util.function.Supplier;

/** Serializes the local/test adapter's recovery-check, token-consume, and create sequence. */
public final class InMemoryWorkoutSessionStartTransaction implements WorkoutSessionStartTransaction {
    @Override
    public synchronized <T> T execute(Supplier<T> action) {
        return Objects.requireNonNull(action, "action must not be null").get();
    }
}
