package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class InMemoryPrivacyAudit implements PrivacyRequestService.AuditPort {

    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<StepKey> lifecycleSteps = new HashSet<>();

    InMemoryPrivacyAudit(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void record(UUID userId, String action, UUID requestId) {
        entries.add(new Entry(userId, action, requestId, clock.instant()));
    }

    @Override
    public synchronized void recordStepOnce(UUID userId, String action, UUID requestId) {
        if (lifecycleSteps.add(new StepKey(userId, action, requestId))) {
            record(userId, action, requestId);
        }
    }

    record Entry(UUID userId, String action, UUID requestId, Instant createdAt) {}

    private record StepKey(UUID userId, String action, UUID requestId) {}
}
