package com.aifitness.assistant.privacy.application;

import com.aifitness.assistant.privacy.domain.DeletionRequest;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class PrivacyDeletionWorker {

    private final PrivacyRepository repository;
    private final DataLifecyclePort dataLifecycle;
    private final PrivacyRequestService.AuditPort audit;
    private final Clock clock;

    public PrivacyDeletionWorker(
            PrivacyRepository repository,
            DataLifecyclePort dataLifecycle,
            PrivacyRequestService.AuditPort audit,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.dataLifecycle = Objects.requireNonNull(dataLifecycle);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized DeletionRequest process(UUID requestId, boolean explicitlyApproved) {
        if (!explicitlyApproved) {
            throw new ExecutionNotApprovedException();
        }
        DeletionRequest request = repository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("deletion request not found"));
        if (request.status() == DeletionRequest.Status.COMPLETED) {
            return request;
        }
        request = advance(request);
        return request;
    }

    private DeletionRequest advance(DeletionRequest request) {
        UUID userId = request.userId();
        if (request.status() == DeletionRequest.Status.REQUESTED) {
            dataLifecycle.revokeAccess(userId);
            request = save(request.transitionTo(DeletionRequest.Status.ACCESS_REVOKED, clock.instant()));
            audit.record(userId, "PRIVACY_ACCESS_REVOKED", request.id());
        }
        if (request.status() == DeletionRequest.Status.ACCESS_REVOKED) {
            dataLifecycle.anonymizeOrdinaryBusinessData(userId);
            request = save(request.transitionTo(
                    DeletionRequest.Status.BUSINESS_DATA_ANONYMIZED, clock.instant()));
            audit.record(userId, "PRIVACY_BUSINESS_DATA_ANONYMIZED", request.id());
        }
        if (request.status() == DeletionRequest.Status.BUSINESS_DATA_ANONYMIZED) {
            dataLifecycle.separateRequiredRetention(userId);
            request = save(request.transitionTo(
                    DeletionRequest.Status.RETENTION_SEPARATED, clock.instant()));
            audit.record(userId, "PRIVACY_RETENTION_SEPARATED", request.id());
        }
        if (request.status() == DeletionRequest.Status.RETENTION_SEPARATED) {
            request = save(request.transitionTo(DeletionRequest.Status.COMPLETED, clock.instant()));
            audit.record(userId, "PRIVACY_DELETION_COMPLETED", request.id());
        }
        return request;
    }

    private DeletionRequest save(DeletionRequest request) {
        return repository.save(request);
    }

    public interface DataLifecyclePort {
        void revokeAccess(UUID userId);

        void anonymizeOrdinaryBusinessData(UUID userId);

        void separateRequiredRetention(UUID userId);
    }

    public static final class ExecutionNotApprovedException extends RuntimeException {
        public ExecutionNotApprovedException() {
            super("deletion execution requires explicit approval");
        }
    }
}
