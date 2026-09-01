package com.aifitness.assistant.plan.application;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-user atomic boundary for warning consumption, immutable plan activation, and receipt completion.
 * Implementations must serialize the user's active-plan decision before invoking the action.
 */
@FunctionalInterface
public interface CandidateCommitTransaction {
    <T> T execute(UUID userId, Supplier<T> action);
}
