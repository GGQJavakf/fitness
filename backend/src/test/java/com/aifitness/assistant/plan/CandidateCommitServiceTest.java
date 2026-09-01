package com.aifitness.assistant.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.CandidateCommitReceiptStore;
import com.aifitness.assistant.plan.application.CandidateCommitService;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.application.InMemoryWarningConfirmationStore;
import com.aifitness.assistant.plan.domain.FieldLock;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.infrastructure.InMemoryCandidateCommitReceiptStore;
import com.aifitness.assistant.plan.infrastructure.InMemoryCandidateCommitTransaction;
import com.aifitness.assistant.plan.infrastructure.InMemoryPlanRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CandidateCommitServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T01:00:00Z"), ZoneOffset.UTC);
    private static final RuleReference REFERENCE = new RuleReference("rules-1", "templates-1", "content-1");
    private static final AuthenticatedUserId USER = new AuthenticatedUserId(
            UUID.fromString("00000000-0000-0000-0000-000000000101"));
    private static final String CANDIDATE_ID = "00000000-0000-0000-0000-000000000201";

    @Test
    void idempotencyKeyMustMatchTheOpenApiCharacterContract() {
        Fixture fixture = fixture(List.of());

        assertThatThrownBy(() -> fixture.service().commit(
                USER, CANDIDATE_ID, 0, plan("unsafe key", 60), Map.of(), null,
                "candidate commit 0001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe ASCII");

        assertThat(fixture.plans().findActiveByUser(USER.value())).isEmpty();
    }

    @Test
    void firstEditedCandidateCommitCreatesOnlyTheFinalVersionAndReplaysIt() {
        Fixture fixture = fixture(List.of());
        PlanDraft edited = plan("final edited plan", 120);

        PlanVersionService.VersionResult first = fixture.service().commit(
                USER, CANDIDATE_ID, 0, edited, Map.of(), null, "candidate-commit-0001");
        PlanVersionService.VersionResult replay = fixture.service().commit(
                USER, CANDIDATE_ID, 0, edited, Map.of(), null, "candidate-commit-0001");

        assertThat(first.status()).isEqualTo(PlanVersionService.VersionStatus.CREATED);
        assertThat(first.version()).isPresent();
        assertThat(first.version().orElseThrow().versionNumber()).isEqualTo(1);
        assertThat(first.version().orElseThrow().plan().name()).isEqualTo("final edited plan");
        assertThat(first.version().orElseThrow().plan().days().getFirst().exercises().getFirst().restSeconds())
                .isEqualTo(120);
        assertThat(fixture.plans().findActiveByUser(USER.value()).orElseThrow().versions()).hasSize(1);
        assertThat(replay.version().orElseThrow().id()).isEqualTo(first.version().orElseThrow().id());
    }

    @Test
    void differentPayloadCannotReuseACompletedIdempotencyKey() {
        Fixture fixture = fixture(List.of());
        fixture.service().commit(
                USER, CANDIDATE_ID, 0, plan("first", 90), Map.of(), null, "candidate-commit-0002");

        assertThatThrownBy(() -> fixture.service().commit(
                USER, CANDIDATE_ID, 0, plan("different", 90), Map.of(), null, "candidate-commit-0002"))
                .isInstanceOf(CandidateCommitService.IdempotencyKeyReusedException.class);
        assertThat(fixture.plans().findActiveByUser(USER.value()).orElseThrow().versions()).hasSize(1);
    }

    @Test
    void concurrentSameKeyRetriesReturnOneImmutableVersion() throws Exception {
        Fixture fixture = fixture(List.of());
        PlanDraft edited = plan("concurrent", 90);
        Callable<UUID> commit = () -> fixture.service().commit(
                        USER, CANDIDATE_ID, 0, edited, Map.of(), null, "candidate-commit-concurrent-0001")
                .version().orElseThrow().id();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(commit);
            var second = executor.submit(commit);
            UUID firstVersion = first.get(5, TimeUnit.SECONDS);
            UUID secondVersion = second.get(5, TimeUnit.SECONDS);

            assertThat(secondVersion).isEqualTo(firstVersion);
            assertThat(fixture.plans().findActiveByUser(USER.value()).orElseThrow().versions()).hasSize(1);
        }
    }

    @Test
    void concurrentDifferentKeysWithTheSameExpectedVersionCreateOneVersionAndReturnOneTypedConflict()
            throws Exception {
        Fixture fixture = fixture(List.of());
        PlanDraft edited = plan("concurrent different keys", 90);
        Callable<Object> firstCommit = () -> commitOrFailure(
                fixture, edited, "candidate-commit-concurrent-different-0001");
        Callable<Object> secondCommit = () -> commitOrFailure(
                fixture, edited, "candidate-commit-concurrent-different-0002");

        try (var executor = Executors.newFixedThreadPool(2)) {
            Object first = executor.submit(firstCommit).get(5, TimeUnit.SECONDS);
            Object second = executor.submit(secondCommit).get(5, TimeUnit.SECONDS);

            assertThat(List.of(first, second))
                    .filteredOn(PlanVersionService.VersionResult.class::isInstance)
                    .singleElement()
                    .extracting(PlanVersionService.VersionResult.class::cast)
                    .extracting(PlanVersionService.VersionResult::status)
                    .isEqualTo(PlanVersionService.VersionStatus.CREATED);
            assertThat(List.of(first, second))
                    .filteredOn(PlanVersionService.VersionConflictException.class::isInstance)
                    .singleElement()
                    .extracting(PlanVersionService.VersionConflictException.class::cast)
                    .extracting(PlanVersionService.VersionConflictException::getCurrentVersion)
                    .isEqualTo(1);
            assertThat(fixture.plans().findActiveByUser(USER.value()).orElseThrow().versions()).hasSize(1);
        }
    }

    @Test
    void existingActivePlanAdvancesExactlyOneVersionFromTheExpectedNumber() {
        Fixture fixture = fixture(List.of());
        fixture.service().commit(
                USER, CANDIDATE_ID, 0, plan("v1", 90), Map.of(), null, "candidate-commit-existing-0001");

        PlanVersionService.VersionResult edited = fixture.service().commit(
                USER, CANDIDATE_ID, 1, plan("final v2", 105), Map.of(), null,
                "candidate-commit-existing-0002");

        assertThat(edited.version().orElseThrow().versionNumber()).isEqualTo(2);
        assertThat(edited.version().orElseThrow().sourceType())
                .isEqualTo(com.aifitness.assistant.plan.domain.TrainingPlanVersion.SourceType.USER_EDIT);
        assertThat(edited.plan().name()).isEqualTo("final v2");
        assertThat(fixture.plans().findActiveByUser(USER.value()).orElseThrow().versions()).hasSize(2);
    }

    @Test
    void validationErrorAndUnconfirmedWarningWriteNoPlan() {
        Fixture invalid = fixture(List.of(new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.ERROR, "INVALID_PLAN", "/days/0")));
        PlanVersionService.VersionResult validation = invalid.service().commit(
                USER, CANDIDATE_ID, 0, plan("invalid", 90), Map.of(), null, "candidate-commit-0003");
        PlanVersionService.VersionResult differentInvalidPayload = invalid.service().commit(
                USER, CANDIDATE_ID, 0, plan("different invalid payload", 90), Map.of(), null,
                "candidate-commit-0003");

        assertThat(validation.status()).isEqualTo(PlanVersionService.VersionStatus.VALIDATION_ERROR);
        assertThat(differentInvalidPayload.status())
                .isEqualTo(PlanVersionService.VersionStatus.VALIDATION_ERROR);
        assertThat(invalid.plans().findActiveByUser(USER.value())).isEmpty();

        Fixture warning = fixture(List.of(new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.WARNING, "LONG_SESSION", "/days/0")));
        PlanVersionService.VersionResult confirmation = warning.service().commit(
                USER, CANDIDATE_ID, 0, plan("warning", 120), Map.of(), null, "candidate-commit-0004");
        PlanVersionService.VersionResult differentWarningPayload = warning.service().commit(
                USER, CANDIDATE_ID, 0, plan("different warning payload", 120), Map.of(), null,
                "candidate-commit-0004");

        assertThat(confirmation.status())
                .isEqualTo(PlanVersionService.VersionStatus.WARNING_CONFIRMATION_REQUIRED);
        assertThat(confirmation.warningConfirmationToken()).isPresent();
        assertThat(differentWarningPayload.status())
                .isEqualTo(PlanVersionService.VersionStatus.WARNING_CONFIRMATION_REQUIRED);
        assertThat(warning.plans().findActiveByUser(USER.value())).isEmpty();
    }

    @Test
    void warningConsumptionPlanWriteAndReceiptRollBackTogetherOnStorageFailure() {
        InMemoryPlanRepository plans = new InMemoryPlanRepository();
        InMemoryWarningConfirmationStore warnings = new InMemoryWarningConfirmationStore(CLOCK);
        InMemoryCandidateCommitReceiptStore receipts = new InMemoryCandidateCommitReceiptStore();
        InMemoryCandidateCommitTransaction transaction =
                new InMemoryCandidateCommitTransaction(plans, warnings, receipts);
        PlanVersionService.PlanPolicy policy = policy(List.of(new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.WARNING, "LONG_SESSION", "/days/0")));
        CandidateCommitService issuing = new CandidateCommitService(
                plans, policy, warnings, receipts, transaction, CLOCK);
        PlanDraft edited = plan("warning", 120);
        String token = issuing.commit(
                        USER, CANDIDATE_ID, 0, edited, Map.of(), null, "candidate-commit-0005")
                .warningConfirmationToken().orElseThrow();

        CandidateCommitReceiptStore failingReceipts = new CandidateCommitReceiptStore() {
            @Override
            public java.util.Optional<Receipt> find(UUID userId, String keyDigest, String payloadDigest) {
                return receipts.find(userId, keyDigest, payloadDigest);
            }

            @Override
            public Claim claim(UUID userId, String keyDigest, String payloadDigest) {
                return receipts.claim(userId, keyDigest, payloadDigest);
            }

            @Override
            public void complete(
                    UUID userId, String keyDigest, String payloadDigest,
                    UUID planId, int versionNumber, UUID versionId) {
                throw new IllegalStateException("injected receipt failure");
            }
        };
        CandidateCommitService failing = new CandidateCommitService(
                plans, policy, warnings, failingReceipts, transaction, CLOCK);

        assertThatThrownBy(() -> failing.commit(
                USER, CANDIDATE_ID, 0, edited, Map.of(), token, "candidate-commit-0005"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected receipt failure");
        assertThat(plans.findActiveByUser(USER.value())).isEmpty();

        PlanVersionService.VersionResult retried = issuing.commit(
                USER, CANDIDATE_ID, 0, edited, Map.of(), token, "candidate-commit-0005");
        assertThat(retried.status()).isEqualTo(PlanVersionService.VersionStatus.CREATED);
        assertThat(plans.findActiveByUser(USER.value()).orElseThrow().versions()).hasSize(1);
    }

    @Test
    void staleExpectedVersionAndForeignCandidateWriteNothing() {
        Fixture fixture = fixture(List.of());
        fixture.service().commit(
                USER, CANDIDATE_ID, 0, plan("v1", 90), Map.of(), null, "candidate-commit-0006");

        assertThatThrownBy(() -> fixture.service().commit(
                USER, CANDIDATE_ID, 0, plan("stale", 90), Map.of(), null, "candidate-commit-0007"))
                .isInstanceOf(PlanVersionService.VersionConflictException.class);
        assertThatThrownBy(() -> fixture.service().commit(
                new AuthenticatedUserId(UUID.fromString("00000000-0000-0000-0000-000000000999")),
                CANDIDATE_ID, 0, plan("foreign", 90), Map.of(), null, "candidate-commit-0008"))
                .isInstanceOf(com.aifitness.assistant.plan.application.PlanCandidateService
                        .CandidateNotFoundException.class);
        assertThat(fixture.plans().findActiveByUser(USER.value()).orElseThrow().versions()).hasSize(1);
    }

    private static Fixture fixture(List<PlanVersionService.ValidationIssue> issues) {
        InMemoryPlanRepository plans = new InMemoryPlanRepository();
        InMemoryWarningConfirmationStore warnings = new InMemoryWarningConfirmationStore(CLOCK);
        InMemoryCandidateCommitReceiptStore receipts = new InMemoryCandidateCommitReceiptStore();
        return new Fixture(
                plans,
                new CandidateCommitService(
                        plans, policy(issues), warnings, receipts,
                        new InMemoryCandidateCommitTransaction(plans, warnings, receipts), CLOCK));
    }

    private static Object commitOrFailure(Fixture fixture, PlanDraft plan, String idempotencyKey) {
        try {
            return fixture.service().commit(
                    USER, CANDIDATE_ID, 0, plan, Map.of(), null, idempotencyKey);
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static PlanVersionService.PlanPolicy policy(List<PlanVersionService.ValidationIssue> issues) {
        return new PlanVersionService.PlanPolicy() {
            @Override
            public PlanVersionService.CandidatePlan candidate(AuthenticatedUserId user, String candidateId) {
                if (!USER.equals(user) || !CANDIDATE_ID.equals(candidateId)) {
                    throw new com.aifitness.assistant.plan.application.PlanCandidateService
                            .CandidateNotFoundException();
                }
                return new PlanVersionService.CandidatePlan(candidateId, plan("candidate", 90), REFERENCE);
            }

            @Override
            public List<PlanVersionService.ValidationIssue> validate(
                    AuthenticatedUserId user, PlanDraft plan, RuleReference reference) {
                return issues;
            }
        };
    }

    private static PlanDraft plan(String name, int restSeconds) {
        return new PlanDraft(
                "FULL_BODY_2_DAY_V1", PlanDraft.TrainingSplit.FULL_BODY, name,
                List.of(
                        new PlanDraft.Day("DAY_1", "Day 1", List.of(new PlanDraft.Exercise(
                                "SQUAT", 3, 8, 12, restSeconds,
                                PlanDraft.WeightStatus.NEEDS_CALIBRATION))),
                        new PlanDraft.Day("DAY_2", "Day 2", List.of(new PlanDraft.Exercise(
                                "ROW", 3, 8, 12, restSeconds,
                                PlanDraft.WeightStatus.NEEDS_CALIBRATION)))),
                Map.<String, FieldLock.Status>of());
    }

    private record Fixture(InMemoryPlanRepository plans, CandidateCommitService service) {}
}
