package com.aifitness.assistant.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import com.aifitness.assistant.privacy.application.PrivacyRequestService;
import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import com.aifitness.assistant.privacy.application.PrivacyRepository;
import com.aifitness.assistant.privacy.domain.DeletionRequest;
import com.aifitness.assistant.privacy.infrastructure.InMemoryPrivacyRepository;
import com.aifitness.assistant.privacy.infrastructure.InMemoryPrivacyExportRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.concurrent.atomic.AtomicReference;

class PrivacyRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());
    private final InMemoryPrivacyRepository repository = new InMemoryPrivacyRepository();
    private final InMemoryPrivacyExportRepository exportRepository = new InMemoryPrivacyExportRepository();
    private final RecordingDataLifecycle data = new RecordingDataLifecycle();
    private final PrivacyRequestService service = new PrivacyRequestService(
            repository,
            exportRepository,
            (subject, proof) -> proof.equals("fresh-proof") && subject.equals(user),
            data,
            CLOCK,
            data,
            (subject, action, now) -> true);

    @Test
    void exportRequiresFreshIdentityProofAndContainsOnlyTheAuthenticatedUsersDeclaration() {
        assertThatThrownBy(() -> service.export(user, "stale-proof"))
                .isInstanceOf(PrivacyRequestService.ReauthenticationRequiredException.class);

        var export = service.export(user, "fresh-proof");

        assertThat(export.userId()).isEqualTo(user.value());
        assertThat(export.scope()).containsExactly(
                "PROFILE", "EQUIPMENT", "PREFERENCES", "PLANS", "WORKOUTS");
        assertThat(export.excludedRetentionCategories()).containsExactly("SECURITY_AUDIT", "LEGAL_HOLD");
        assertThat(data.auditCount).isEqualTo(2);
        assertThat(data.auditActions).containsExactly(
                "PRIVACY_EXPORT_REAUTHENTICATION_REJECTED", "PRIVACY_EXPORT_SUCCEEDED");
    }

    @Test
    void deletionRequiresExactSecondConfirmationAndFreshIdentityProof() {
        assertThatThrownBy(() -> service.requestDeletion(user, "fresh-proof", "delete"))
                .isInstanceOf(PrivacyRequestService.SecondConfirmationRequiredException.class);
        assertThatThrownBy(() -> service.requestDeletion(user, "stale-proof", "DELETE"))
                .isInstanceOf(PrivacyRequestService.ReauthenticationRequiredException.class);
        assertThat(repository.findActiveByUser(user.value())).isEmpty();
        assertThat(data.auditActions).containsExactly(
                "PRIVACY_DELETION_CONFIRMATION_REJECTED",
                "PRIVACY_DELETION_REAUTHENTICATION_REJECTED");
    }

    @Test
    void repeatedDeletionApplicationIsIdempotentForTheSameUser() {
        DeletionRequest first = service.requestDeletion(user, "fresh-proof", "DELETE");
        DeletionRequest duplicate = service.requestDeletion(user, "fresh-proof", "DELETE");

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(duplicate.status()).isEqualTo(DeletionRequest.Status.REQUESTED);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(data.auditCount).isEqualTo(2);
        assertThat(data.auditActions).containsExactly(
                "PRIVACY_DELETION_SUCCEEDED", "PRIVACY_DELETION_DUPLICATE");
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
        assertThat(data.auditCount).isEqualTo(6);
        assertThat(data.auditActions).containsExactly(
                "PRIVACY_DELETION_SUCCEEDED",
                "PRIVACY_DELETION_PROCESS_NOT_APPROVED",
                "PRIVACY_ACCESS_REVOKED",
                "PRIVACY_BUSINESS_DATA_ANONYMIZED",
                "PRIVACY_RETENTION_SEPARATED",
                "PRIVACY_DELETION_COMPLETED");
    }

    @Test
    void rateLimitedAttemptIsAuditedWithoutEvaluatingOrRecordingTheProof() {
        AtomicReference<String> auditAction = new AtomicReference<>();
        PrivacyRequestService limited = new PrivacyRequestService(
                repository,
                exportRepository,
                (subject, proof) -> { throw new AssertionError("proof must not be evaluated"); },
                new PrivacyRequestService.AuditPort() {
                    @Override public void record(UUID subject, String action, UUID requestId) {
                        auditAction.set(action);
                    }
                    @Override public void recordStepOnce(UUID subject, String action, UUID requestId) {
                        auditAction.set(action);
                    }
                },
                CLOCK,
                subject -> java.util.List.of(new PrivacyDataPort.ResourceExport(
                        PrivacyDataPort.Category.PROFILE,
                        java.util.List.of(new PrivacyDataPort.ExportRecord("profile", "档案")))),
                (subject, action, now) -> false);

        assertThatThrownBy(() -> limited.export(user, "sensitive-proof"))
                .isInstanceOf(PrivacyRequestService.PrivacyRateLimitedException.class);
        assertThat(auditAction.get())
                .isEqualTo("EXPORT_RATE_LIMITED")
                .doesNotContain("sensitive-proof");
    }

    @Test
    void exportDataFailureIsAuditedWithoutProofAndPropagatedUnchanged() {
        RecordingDataLifecycle audit = new RecordingDataLifecycle();
        IllegalStateException expected = new IllegalStateException("fixture export failed");
        PrivacyRequestService failing = new PrivacyRequestService(
                repository,
                exportRepository,
                (subject, proof) -> true,
                audit,
                CLOCK,
                subject -> { throw expected; },
                (subject, action, now) -> true);

        assertThatThrownBy(() -> failing.export(user, "sensitive-proof"))
                .isSameAs(expected);
        assertThat(audit.auditActions)
                .containsExactly("PRIVACY_EXPORT_FAILED")
                .allMatch(action -> !action.contains("sensitive-proof"));
    }

    @Test
    void deletionRepositorySaveFailureIsAuditedWithoutProofAndPropagatedUnchanged() {
        RecordingDataLifecycle audit = new RecordingDataLifecycle();
        IllegalStateException expected = new IllegalStateException("repository save failed");
        PrivacyRepository failingRepository = new PrivacyRepository() {
            @Override public Optional<DeletionRequest> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<DeletionRequest> findActiveByUser(UUID userId) {
                return Optional.empty();
            }
            @Override public DeletionRequest save(DeletionRequest request) { throw expected; }
        };
        PrivacyRequestService failing = new PrivacyRequestService(
                failingRepository,
                exportRepository,
                (subject, proof) -> true,
                audit,
                CLOCK,
                audit,
                (subject, action, now) -> true);

        assertThatThrownBy(() -> failing.requestDeletion(user, "sensitive-proof", "DELETE"))
                .isSameAs(expected);
        assertThat(audit.auditActions)
                .containsExactly("PRIVACY_DELETION_FAILED")
                .allMatch(action -> !action.contains("sensitive-proof"));
    }

    @ParameterizedTest
    @EnumSource(value = DeletionRequest.Status.class, names = {
            "ACCESS_REVOKED", "BUSINESS_DATA_ANONYMIZED", "RETENTION_SEPARATED"
    })
    void workerRetriesAfterEachStepSaveFailureWithoutRepeatingExternalSteps(
            DeletionRequest.Status failedStatus) {
        DeletionRequest requested = DeletionRequest.requested(user.value(), NOW);
        FailOnceRepository failing = new FailOnceRepository(requested, failedStatus);
        RecordingDataLifecycle lifecycle = new RecordingDataLifecycle();
        PrivacyDeletionWorker worker = new PrivacyDeletionWorker(failing, lifecycle, lifecycle, CLOCK);

        assertThatThrownBy(() -> worker.process(requested.id(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated state save failure");

        DeletionRequest completed = worker.process(requested.id(), true);

        assertThat(completed.status()).isEqualTo(DeletionRequest.Status.COMPLETED);
        assertThat(lifecycle.transitions).containsExactly(
                "ACCESS_REVOKED", "BUSINESS_DATA_ANONYMIZED", "RETENTION_SEPARATED");
        assertThat(lifecycle.auditActions).contains("PRIVACY_DELETION_PROCESS_FAILED");
        assertThat(lifecycle.auditActions).filteredOn(action -> action.startsWith("PRIVACY_")
                        && !action.equals("PRIVACY_DELETION_PROCESS_FAILED"))
                .doesNotHaveDuplicates();
    }

    private static final class RecordingDataLifecycle
            implements PrivacyDeletionWorker.DataLifecyclePort,
            PrivacyRequestService.AuditPort,
            PrivacyDataPort {
        private boolean accessRevoked;
        private boolean ordinaryBusinessDataPresent = true;
        private boolean legalRetentionPresent = true;
        private boolean auditPresent = true;
        private int auditCount;
        private final java.util.List<String> auditActions = new java.util.ArrayList<>();
        private final java.util.List<String> transitions = new java.util.ArrayList<>();
        private final java.util.Set<String> executed = new java.util.HashSet<>();
        private final java.util.Set<String> auditedSteps = new java.util.HashSet<>();

        @Override
        public void execute(PrivacyDeletionWorker.LifecycleCommand command) {
            if (!executed.add(command.requestId() + ":" + command.step())) {
                return;
            }
            switch (command.step()) {
                case REVOKE_ACCESS -> {
                    accessRevoked = true;
                    transitions.add("ACCESS_REVOKED");
                }
                case ANONYMIZE_BUSINESS_DATA -> {
                    ordinaryBusinessDataPresent = false;
                    transitions.add("BUSINESS_DATA_ANONYMIZED");
                }
                case SEPARATE_REQUIRED_RETENTION -> transitions.add("RETENTION_SEPARATED");
            }
        }

        @Override
        public java.util.List<ResourceExport> export(UUID userId) {
            return java.util.List.of(new ResourceExport(
                    Category.PROFILE,
                    java.util.List.of(new ExportRecord("profile-1", "训练档案"))));
        }

        @Override
        public void record(UUID userId, String action, UUID requestId) {
            auditCount++;
            auditActions.add(action);
        }

        @Override
        public void recordStepOnce(UUID userId, String action, UUID requestId) {
            if (auditedSteps.add(userId + ":" + action + ":" + requestId)) {
                record(userId, action, requestId);
            }
        }
    }

    private static final class FailOnceRepository implements PrivacyRepository {
        private DeletionRequest request;
        private final DeletionRequest.Status failedStatus;
        private boolean failed;

        private FailOnceRepository(DeletionRequest request, DeletionRequest.Status failedStatus) {
            this.request = request;
            this.failedStatus = failedStatus;
        }

        @Override public Optional<DeletionRequest> findById(UUID id) {
            return request.id().equals(id) ? Optional.of(request) : Optional.empty();
        }

        @Override public Optional<DeletionRequest> findActiveByUser(UUID userId) {
            return request.userId().equals(userId) && request.active()
                    ? Optional.of(request) : Optional.empty();
        }

        @Override public DeletionRequest save(DeletionRequest next) {
            if (!failed && next.status() == failedStatus) {
                failed = true;
                throw new IllegalStateException("simulated state save failure");
            }
            request = next;
            return next;
        }
    }
}
