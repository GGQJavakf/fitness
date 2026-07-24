package com.aifitness.assistant.privacy.application;

import com.aifitness.assistant.identity.application.ResourceOwnershipGuard;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.domain.DeletionRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PrivacyRequestService {

    private static final String DELETION_CONFIRMATION = "DELETE";
    private static final List<String> ORDINARY_DATA_CATEGORIES =
            List.of("PROFILE", "EQUIPMENT", "PREFERENCES", "PLANS", "WORKOUTS");
    private static final List<String> RETAINED_CATEGORIES =
            List.of("SECURITY_AUDIT", "LEGAL_HOLD");

    private final PrivacyRepository repository;
    private final ReauthenticationPort reauthentication;
    private final AuditPort audit;
    private final PrivacyDataPort data;
    private final PrivacyRateLimitPort rateLimit;
    private final Clock clock;

    public PrivacyRequestService(
            PrivacyRepository repository,
            ReauthenticationPort reauthentication,
            AuditPort audit,
            Clock clock) {
        this(repository, reauthentication, audit, clock,
                userId -> ordinaryDataCategories().stream()
                        .map(category -> new PrivacyDataPort.ResourceSummary(
                                PrivacyDataPort.Category.valueOf(category), 0))
                        .toList(),
                (userId, action, now) -> true);
    }

    public PrivacyRequestService(
            PrivacyRepository repository,
            ReauthenticationPort reauthentication,
            AuditPort audit,
            Clock clock,
            PrivacyDataPort data,
            PrivacyRateLimitPort rateLimit) {
        this.repository = Objects.requireNonNull(repository);
        this.reauthentication = Objects.requireNonNull(reauthentication);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        this.data = Objects.requireNonNull(data);
        this.rateLimit = Objects.requireNonNull(rateLimit);
    }

    public PrivacyExport export(AuthenticatedUserId user, String proof) {
        Instant now = clock.instant();
        requireAllowed(user, PrivacyRateLimitPort.Action.EXPORT, null);
        requireReauthentication(user, proof, "PRIVACY_EXPORT", null);
        UUID exportId = UUID.randomUUID();
        List<PrivacyDataPort.ResourceSummary> resources = data.summarize(user.value());
        audit.recordAttempt(user.value(), "PRIVACY_EXPORT", "SUCCEEDED", exportId);
        return new PrivacyExport(
                exportId,
                user.value(),
                ExportStatus.READY,
                now,
                resources,
                ordinaryDataCategories(),
                requiredRetentionCategories());
    }

    public synchronized DeletionRequest requestDeletion(
            AuthenticatedUserId user, String proof, String confirmationText) {
        requireAllowed(user, PrivacyRateLimitPort.Action.DELETE_REQUEST, null);
        if (!DELETION_CONFIRMATION.equals(confirmationText)) {
            audit.recordAttempt(user.value(), "PRIVACY_DELETION", "CONFIRMATION_REJECTED", null);
            throw new SecondConfirmationRequiredException();
        }
        requireReauthentication(user, proof, "PRIVACY_DELETION", null);
        var existing = repository.findActiveByUser(user.value());
        if (existing.isPresent()) {
            audit.recordAttempt(user.value(), "PRIVACY_DELETION", "DUPLICATE", existing.get().id());
            return existing.get();
        }
        DeletionRequest request = repository.save(DeletionRequest.requested(user.value(), clock.instant()));
        audit.recordAttempt(user.value(), "PRIVACY_DELETION", "SUCCEEDED", request.id());
        return request;
    }

    public DeletionRequest getDeletionRequest(AuthenticatedUserId user, UUID requestId) {
        requireAllowed(user, PrivacyRateLimitPort.Action.DELETE_STATUS, requestId);
        DeletionRequest request = ownedRequest(user, requestId, "PRIVACY_DELETION_STATUS");
        audit.recordAttempt(user.value(), "PRIVACY_DELETION_STATUS", "SUCCEEDED", requestId);
        return request;
    }

    public DeletionRequest authorizeDeletionProcessing(
            AuthenticatedUserId user, UUID requestId, String proof) {
        requireAllowed(user, PrivacyRateLimitPort.Action.DELETE_PROCESS, requestId);
        DeletionRequest request = ownedRequest(user, requestId, "PRIVACY_DELETION_PROCESS");
        requireReauthentication(user, proof, "PRIVACY_DELETION_PROCESS", requestId);
        audit.recordAttempt(user.value(), "PRIVACY_DELETION_PROCESS", "AUTHORIZED", requestId);
        return request;
    }

    public static List<String> ordinaryDataCategories() {
        return ORDINARY_DATA_CATEGORIES;
    }

    public static List<String> requiredRetentionCategories() {
        return RETAINED_CATEGORIES;
    }

    private void requireAllowed(
            AuthenticatedUserId user, PrivacyRateLimitPort.Action action, UUID resourceId) {
        if (!rateLimit.allow(user.value(), action, clock.instant())) {
            audit.recordAttempt(user.value(), action.name(), "RATE_LIMITED", resourceId);
            throw new PrivacyRateLimitedException();
        }
    }

    private DeletionRequest ownedRequest(
            AuthenticatedUserId user, UUID requestId, String action) {
        return repository.findById(requestId)
                .filter(candidate -> candidate.userId().equals(user.value()))
                .orElseThrow(() -> {
                    audit.recordAttempt(user.value(), action, "NOT_FOUND", requestId);
                    return new ResourceOwnershipGuard.ResourceNotFoundException();
                });
    }

    private void requireReauthentication(
            AuthenticatedUserId user, String proof, String action, UUID resourceId) {
        if (proof == null || proof.isBlank() || proof.length() > 2048
                || !reauthentication.verify(user, proof)) {
            audit.recordAttempt(user.value(), action, "REAUTHENTICATION_REJECTED", resourceId);
            throw new ReauthenticationRequiredException();
        }
    }

    @FunctionalInterface
    public interface ReauthenticationPort {
        boolean verify(AuthenticatedUserId user, String oneTimeProof);
    }

    @FunctionalInterface
    public interface AuditPort {
        void record(UUID userId, String action, UUID requestId);

        /**
         * Records one lifecycle fact for a request. Implementations must make this operation
         * idempotent for the same user, action and request id so a worker retry cannot duplicate
         * or lose a completed-step audit record.
         */
        default void recordStepOnce(UUID userId, String action, UUID requestId) {
            record(userId, action, requestId);
        }

        default void recordAttempt(UUID userId, String action, String outcome, UUID resourceId) {
            record(userId, action + "_" + outcome, resourceId);
        }
    }

    public record PrivacyExport(
            UUID id,
            UUID userId,
            ExportStatus status,
            Instant generatedAt,
            List<PrivacyDataPort.ResourceSummary> resources,
            List<String> scope,
            List<String> excludedRetentionCategories) {
        public PrivacyExport {
            resources = List.copyOf(resources);
            scope = List.copyOf(scope);
            excludedRetentionCategories = List.copyOf(excludedRetentionCategories);
        }
    }

    public enum ExportStatus { READY }

    public static final class ReauthenticationRequiredException extends RuntimeException {
        public ReauthenticationRequiredException() {
            super("identity reauthentication required");
        }
    }

    public static final class SecondConfirmationRequiredException extends RuntimeException {
        public SecondConfirmationRequiredException() {
            super("exact deletion confirmation required");
        }
    }


    public static final class PrivacyRateLimitedException extends RuntimeException {
        public PrivacyRateLimitedException() {
            super("privacy action rate limited");
        }
    }
}
