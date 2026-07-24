package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class InMemoryPrivacyAudit implements PrivacyRequestService.AuditPort {

    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();

    InMemoryPrivacyAudit(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void record(UUID userId, String action, UUID requestId) {
        entries.add(new Entry(userId, action, requestId, clock.instant()));
    }

    record Entry(UUID userId, String action, UUID requestId, Instant createdAt) {}
}
