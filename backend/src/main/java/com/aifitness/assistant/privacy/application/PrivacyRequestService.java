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
    private final Clock clock;

    public PrivacyRequestService(
            PrivacyRepository repository,
            ReauthenticationPort reauthentication,
            AuditPort audit,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.reauthentication = Objects.requireNonNull(reauthentication);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public PrivacyExport export(AuthenticatedUserId user, String proof) {
        requireReauthentication(user, proof);
        Instant now = clock.instant();
        audit.record(user.value(), "PRIVACY_EXPORT_CREATED", null);
        return new PrivacyExport(
                user.value(), now, ordinaryDataCategories(), requiredRetentionCategories());
    }

    public synchronized DeletionRequest requestDeletion(
            AuthenticatedUserId user, String proof, String confirmationText) {
        if (!DELETION_CONFIRMATION.equals(confirmationText)) {
            throw new SecondConfirmationRequiredException();
        }
        requireReauthentication(user, proof);
        return repository.findActiveByUser(user.value()).orElseGet(() -> {
            DeletionRequest request = repository.save(DeletionRequest.requested(user.value(), clock.instant()));
            audit.record(user.value(), "PRIVACY_DELETION_REQUESTED", request.id());
            return request;
        });
    }

    public DeletionRequest getDeletionRequest(AuthenticatedUserId user, UUID requestId) {
        DeletionRequest request = repository.findById(requestId)
                .filter(candidate -> candidate.userId().equals(user.value()))
                .orElseThrow(ResourceOwnershipGuard.ResourceNotFoundException::new);
        return request;
    }

    public static List<String> ordinaryDataCategories() {
        return ORDINARY_DATA_CATEGORIES;
    }

    public static List<String> requiredRetentionCategories() {
        return RETAINED_CATEGORIES;
    }

    private void requireReauthentication(AuthenticatedUserId user, String proof) {
        if (proof == null || proof.isBlank() || proof.length() > 2048
                || !reauthentication.verify(user, proof)) {
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
    }

    public record PrivacyExport(
            UUID userId,
            Instant generatedAt,
            List<String> scope,
            List<String> excludedRetentionCategories) {
        public PrivacyExport {
            scope = List.copyOf(scope);
            excludedRetentionCategories = List.copyOf(excludedRetentionCategories);
        }
    }

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
}
