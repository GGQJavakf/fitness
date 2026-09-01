package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSessionStartTransaction;
import java.util.Objects;
import java.util.function.Supplier;

/** Serializes and rolls back the local/test adapter's recovery, replacement, and create sequence. */
public final class InMemoryWorkoutSessionStartTransaction implements WorkoutSessionStartTransaction {
    private final InMemoryWorkoutSessionRepository sessions;
    private final InMemoryWorkoutRecoveryConfirmationStore confirmations;

    public InMemoryWorkoutSessionStartTransaction(
            InMemoryWorkoutSessionRepository sessions,
            InMemoryWorkoutRecoveryConfirmationStore confirmations) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations must not be null");
    }

    @Override
    public synchronized <T> T execute(Supplier<T> action) {
        Supplier<T> required = Objects.requireNonNull(action, "action must not be null");
        // Repository methods synchronize independently. Retaining both adapter monitors makes
        // the whole command indivisible from direct local/test repository calls as well.
        synchronized (sessions) {
            synchronized (confirmations) {
                InMemoryWorkoutSessionRepository.Snapshot sessionSnapshot = sessions.snapshot();
                InMemoryWorkoutRecoveryConfirmationStore.Snapshot confirmationSnapshot = confirmations.snapshot();
                try {
                    return required.get();
                } catch (RuntimeException | Error failure) {
                    sessions.restore(sessionSnapshot);
                    confirmations.restore(confirmationSnapshot);
                    throw failure;
                }
            }
        }
    }
}
