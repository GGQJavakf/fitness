package com.aifitness.assistant.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import com.aifitness.assistant.privacy.domain.DeletionRequest;
import com.aifitness.assistant.privacy.infrastructure.InMemoryPrivacyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrivacyRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());
    private final InMemoryPrivacyRepository repository = new InMemoryPrivacyRepository();
    private final RecordingDataLifecycle data = new RecordingDataLifecycle();
    private final PrivacyRequestService service = new PrivacyRequestService(
            repository,
            (subject, proof) -> proof.equals("fresh-proof") && subject.equals(user),
            (subject, action, requestId) -> data.auditCount++,
            CLOCK);

    @Test
    void exportRequiresFreshIdentityProofAndContainsOnlyTheAuthenticatedUsersDeclaration() {
        assertThatThrownBy(() -> service.export(user, "stale-proof"))
                .isInstanceOf(PrivacyRequestService.ReauthenticationRequiredException.class);

        var export = service.export(user, "fresh-proof");

        assertThat(export.userId()).isEqualTo(user.value());
        assertThat(export.scope()).containsExactly(
                "PROFILE", "EQUIPMENT", "PREFERENCES", "PLANS", "WORKOUTS");
        assertThat(export.excludedRetentionCategories()).containsExactly("SECURITY_AUDIT", "LEGAL_HOLD");
        assertThat(data.auditCount).isEqualTo(1);
    }

    @Test
    void deletionRequiresExactSecondConfirmationAndFreshIdentityProof() {
        assertThatThrownBy(() -> service.requestDeletion(user, "fresh-proof", "delete"))
                .isInstanceOf(PrivacyRequestService.SecondConfirmationRequiredException.class);
        assertThatThrownBy(() -> service.requestDeletion(user, "stale-proof", "DELETE"))
                .isInstanceOf(PrivacyRequestService.ReauthenticationRequiredException.class);
        assertThat(repository.findActiveByUser(user.value())).isEmpty();
    }

    @Test
    void repeatedDeletionApplicationIsIdempotentForTheSameUser() {
        DeletionRequest first = service.requestDeletion(user, "fresh-proof", "DELETE");
        DeletionRequest duplicate = service.requestDeletion(user, "fresh-proof", "DELETE");

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(duplicate.status()).isEqualTo(DeletionRequest.Status.REQUESTED);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(data.auditCount).isEqualTo(1);
    }

    @Test
    void workerUsesControlledTransitionsAndSeparatesRetentionBeforeCompletion() {
        DeletionRequest request = service.requestDeletion(user, "fresh-proof", "DELETE");
        PrivacyDeletionWorker worker = new PrivacyDeletionWorker(repository, data, data, CLOCK);

        assertThatThrownBy(() -> worker.process(request.id(), false))
                .isInstanceOf(PrivacyDeletionWorker.ExecutionNotApprovedException.class);
        assertThat(data.accessRevoked).isFalse();

        DeletionRequest completed = worker.process(request.id(), true);

        assertThat(completed.status()).isEqualTo(DeletionRequest.Status.COMPLETED);
        assertThat(data.transitions).containsExactly(
                "ACCESS_REVOKED", "BUSINESS_DATA_ANONYMIZED", "RETENTION_SEPARATED");
        assertThat(data.ordinaryBusinessDataPresent).isFalse();
        assertThat(data.legalRetentionPresent).isTrue();
        assertThat(data.auditPresent).isTrue();
        assertThat(data.auditCount).isEqualTo(5);
    }

    private static final class RecordingDataLifecycle
            implements PrivacyDeletionWorker.DataLifecyclePort, PrivacyRequestService.AuditPort {
        private boolean accessRevoked;
        private boolean ordinaryBusinessDataPresent = true;
        private boolean legalRetentionPresent = true;
        private boolean auditPresent = true;
        private int auditCount;
        private final java.util.List<String> transitions = new java.util.ArrayList<>();

        @Override
        public void revokeAccess(UUID userId) {
            accessRevoked = true;
            transitions.add("ACCESS_REVOKED");
        }

        @Override
        public void anonymizeOrdinaryBusinessData(UUID userId) {
            ordinaryBusinessDataPresent = false;
            transitions.add("BUSINESS_DATA_ANONYMIZED");
        }

        @Override
        public void separateRequiredRetention(UUID userId) {
            transitions.add("RETENTION_SEPARATED");
        }

        @Override
        public void record(UUID userId, String action, UUID requestId) {
            auditCount++;
        }
    }
}
