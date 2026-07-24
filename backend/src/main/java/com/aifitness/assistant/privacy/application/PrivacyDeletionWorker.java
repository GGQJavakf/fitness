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
        DeletionRequest request = repository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("deletion request not found"));
        if (!explicitlyApproved) {
            audit.recordAttempt(request.userId(), "PRIVACY_DELETION_PROCESS", "NOT_APPROVED", requestId);
            throw new ExecutionNotApprovedException();
        }
        if (request.status() == DeletionRequest.Status.COMPLETED) {
            audit.recordAttempt(request.userId(), "PRIVACY_DELETION_PROCESS", "DUPLICATE", requestId);
            return request;
        }
        try {
            return advance(request);
        } catch (RuntimeException failure) {
            audit.recordAttempt(request.userId(), "PRIVACY_DELETION_PROCESS", "FAILED", requestId);
            throw failure;
        }
    }

    private DeletionRequest advance(DeletionRequest request) {
        UUID userId = request.userId();
        if (request.status() == DeletionRequest.Status.REQUESTED) {
            dataLifecycle.execute(new LifecycleCommand(
                    request.id(), userId, LifecycleStep.REVOKE_ACCESS));
            audit.recordStepOnce(userId, "PRIVACY_ACCESS_REVOKED", request.id());
            request = save(request.transitionTo(DeletionRequest.Status.ACCESS_REVOKED, clock.instant()));
        }
        if (request.status() == DeletionRequest.Status.ACCESS_REVOKED) {
            dataLifecycle.execute(new LifecycleCommand(
                    request.id(), userId, LifecycleStep.ANONYMIZE_BUSINESS_DATA));
            audit.recordStepOnce(userId, "PRIVACY_BUSINESS_DATA_ANONYMIZED", request.id());
            request = save(request.transitionTo(
                    DeletionRequest.Status.BUSINESS_DATA_ANONYMIZED, clock.instant()));
        }
        if (request.status() == DeletionRequest.Status.BUSINESS_DATA_ANONYMIZED) {
            dataLifecycle.execute(new LifecycleCommand(
                    request.id(), userId, LifecycleStep.SEPARATE_REQUIRED_RETENTION));
            audit.recordStepOnce(userId, "PRIVACY_RETENTION_SEPARATED", request.id());
            request = save(request.transitionTo(
                    DeletionRequest.Status.RETENTION_SEPARATED, clock.instant()));
        }
        if (request.status() == DeletionRequest.Status.RETENTION_SEPARATED) {
            audit.recordStepOnce(userId, "PRIVACY_DELETION_COMPLETED", request.id());
            request = save(request.transitionTo(DeletionRequest.Status.COMPLETED, clock.instant()));
        }
        return request;
    }

    private DeletionRequest save(DeletionRequest request) {
        return repository.save(request);
    }

    public interface DataLifecyclePort {
        void execute(LifecycleCommand command);
    }

    public record LifecycleCommand(UUID requestId, UUID userId, LifecycleStep step) {
        public LifecycleCommand {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(userId);
            Objects.requireNonNull(step);
        }
    }

    public enum LifecycleStep {
        REVOKE_ACCESS, ANONYMIZE_BUSINESS_DATA, SEPARATE_REQUIRED_RETENTION
    }

    public static final class ExecutionNotApprovedException extends RuntimeException {
        public ExecutionNotApprovedException() {
            super("deletion execution requires explicit approval");
        }
    }
}
