package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.plan.application.CandidateCommitTransaction;
import com.aifitness.assistant.plan.application.InMemoryWarningConfirmationStore;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Copy-on-write rollback boundary for the local/test plan, warning, and receipt adapters. */
public final class InMemoryCandidateCommitTransaction implements CandidateCommitTransaction {
    private final InMemoryPlanRepository plans;
    private final InMemoryWarningConfirmationStore warnings;
    private final InMemoryCandidateCommitReceiptStore receipts;

    public InMemoryCandidateCommitTransaction(
            InMemoryPlanRepository plans,
            InMemoryWarningConfirmationStore warnings,
            InMemoryCandidateCommitReceiptStore receipts) {
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.warnings = Objects.requireNonNull(warnings, "warnings must not be null");
        this.receipts = Objects.requireNonNull(receipts, "receipts must not be null");
    }

    @Override
    public synchronized <T> T execute(UUID userId, Supplier<T> action) {
        Objects.requireNonNull(userId, "userId must not be null");
        Supplier<T> required = Objects.requireNonNull(action, "action must not be null");
        synchronized (plans) {
            synchronized (warnings) {
                synchronized (receipts) {
                    InMemoryPlanRepository.Snapshot planSnapshot = plans.snapshot();
                    InMemoryWarningConfirmationStore.Snapshot warningSnapshot = warnings.snapshot();
                    InMemoryCandidateCommitReceiptStore.Snapshot receiptSnapshot = receipts.snapshot();
                    try {
                        return required.get();
                    } catch (RuntimeException | Error failure) {
                        plans.restore(planSnapshot);
                        warnings.restore(warningSnapshot);
                        receipts.restore(receiptSnapshot);
                        throw failure;
                    }
                }
            }
        }
    }
}
