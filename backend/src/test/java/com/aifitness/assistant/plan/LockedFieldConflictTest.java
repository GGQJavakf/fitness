package com.aifitness.assistant.plan;

import static com.aifitness.assistant.plan.PlanVersionImmutabilityTest.draft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.FieldLock;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import com.aifitness.assistant.plan.infrastructure.InMemoryPlanRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LockedFieldConflictTest {

    private static final AuthenticatedUserId USER = new AuthenticatedUserId(
            UUID.fromString("00000000-0000-0000-0000-000000000102"));
    private static final String REST_PATH = "/days/DAY_A/exercises/SQUAT/restSeconds";

    @Test
    void userLockedValueIsNeverSilentlyOverwritten() {
        PlanVersionImmutabilityTest.StubPolicy policy =
                new PlanVersionImmutabilityTest.StubPolicy(draft(90));
        PlanVersionService service = service(policy);
        TrainingPlanVersion first = service.createInitial(USER, "candidate-1").activeVersion();

        PlanVersionService.VersionResult result = service.createVersion(
                USER, first.planId(), 1, draft(180),
                Map.of(REST_PATH, FieldLock.Status.USER_LOCKED), null);

        assertThat(result.version()).get().satisfies(version -> {
            assertThat(version.plan().valueAt(REST_PATH)).contains(180);
            assertThat(version.plan().locks()).containsEntry(REST_PATH, FieldLock.Status.USER_LOCKED);
        });

        PlanVersionService.VersionResult next = service.createVersion(
                USER, first.planId(), 2, draft(240), Map.of(), null);
        assertThat(next.version()).get().satisfies(version -> {
            assertThat(version.plan().valueAt(REST_PATH)).contains(180);
            assertThat(version.plan().locks()).containsEntry(REST_PATH, FieldLock.Status.USER_LOCKED);
        });
    }

    @Test
    void warningRequiresAnExactSecondConfirmationAndErrorNeverSaves() {
        PlanVersionImmutabilityTest.StubPolicy policy =
                new PlanVersionImmutabilityTest.StubPolicy(draft(90));
        PlanVersionService service = service(policy);
        TrainingPlanVersion first = service.createInitial(USER, "candidate-1").activeVersion();
        policy.issues(new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.WARNING, "VOLUME_NEAR_LIMIT", "/days/DAY_A"));

        PlanVersionService.VersionResult warning = service.createVersion(
                USER, first.planId(), 1, draft(120), Map.of(), null);

        assertThat(warning.status()).isEqualTo(PlanVersionService.VersionStatus.WARNING_CONFIRMATION_REQUIRED);
        assertThat(warning.warningConfirmationToken()).isPresent();
        assertThat(service.getActive(USER).activeVersion().versionNumber()).isEqualTo(1);

        PlanVersionService.VersionResult created = service.createVersion(
                USER, first.planId(), 1, draft(120), Map.of(),
                warning.warningConfirmationToken().orElseThrow());
        assertThat(created.status()).isEqualTo(PlanVersionService.VersionStatus.CREATED);
        assertThat(created.version()).get().extracting(TrainingPlanVersion::versionNumber).isEqualTo(2);

        policy.issues(new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.ERROR, "RECOVERY_INVALID", "/days/DAY_A"));
        PlanVersionService.VersionResult rejected = service.createVersion(
                USER, first.planId(), 2, draft(150), Map.of(), null);
        assertThat(rejected.status()).isEqualTo(PlanVersionService.VersionStatus.VALIDATION_ERROR);
        assertThat(rejected.version()).isEmpty();
        assertThat(service.getActive(USER).activeVersion().versionNumber()).isEqualTo(2);
    }

    @Test
    void warningConfirmationTokenCannotConfirmDifferentPlanContent() {
        PlanVersionImmutabilityTest.StubPolicy policy =
                new PlanVersionImmutabilityTest.StubPolicy(draft(90));
        PlanVersionService service = service(policy);
        TrainingPlanVersion first = service.createInitial(USER, "candidate-1").activeVersion();
        policy.issues(new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.WARNING, "VOLUME_NEAR_LIMIT", "/days/DAY_A"));

        PlanVersionService.VersionResult warning = service.createVersion(
                USER, first.planId(), 1, draft(120), Map.of(), null);
        PlanVersionService.VersionResult changedDraft = service.createVersion(
                USER, first.planId(), 1, draft(150), Map.of(),
                warning.warningConfirmationToken().orElseThrow());

        assertThat(changedDraft.status())
                .isEqualTo(PlanVersionService.VersionStatus.WARNING_CONFIRMATION_REQUIRED);
        assertThat(service.getActive(USER).activeVersion().versionNumber()).isEqualTo(1);
    }

    @Test
    void ruleLockedFieldCannotBeUnlockedOrReclassifiedByAClientEdit() {
        var ruleLocked = new com.aifitness.assistant.plan.domain.PlanDraft(
                "FULL_BODY_3D", "全身训练",
                draft(90).days(), Map.of(REST_PATH, FieldLock.Status.RULE_LOCKED));
        PlanVersionImmutabilityTest.StubPolicy policy =
                new PlanVersionImmutabilityTest.StubPolicy(ruleLocked);
        PlanVersionService service = service(policy);
        TrainingPlanVersion first = service.createInitial(USER, "candidate-1").activeVersion();

        assertThatThrownBy(() -> service.createVersion(
                USER, first.planId(), 1, draft(180),
                Map.of(REST_PATH, FieldLock.Status.UNLOCKED), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rule locked field cannot be changed");
        assertThatThrownBy(() -> service.createVersion(
                USER, first.planId(), 1, draft(180),
                Map.of(REST_PATH, FieldLock.Status.USER_LOCKED), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rule locked field cannot be changed");
        assertThat(service.getActive(USER).activeVersion().plan().locks())
                .containsEntry(REST_PATH, FieldLock.Status.RULE_LOCKED);
    }

    @Test
    void warningConfirmationTokenExpiresAndCannotBeReusedForAnotherBaseVersion() {
        PlanVersionImmutabilityTest.StubPolicy policy =
                new PlanVersionImmutabilityTest.StubPolicy(draft(90));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T00:00:00Z"));
        PlanVersionService service = new PlanVersionService(new InMemoryPlanRepository(), policy, clock);
        TrainingPlanVersion first = service.createInitial(USER, "candidate-1").activeVersion();
        policy.issues(new PlanVersionService.ValidationIssue(
                PlanVersionService.Severity.WARNING, "VOLUME_NEAR_LIMIT", "/days/DAY_A"));

        PlanVersionService.VersionResult expiring = service.createVersion(
                USER, first.planId(), 1, draft(120), Map.of(), null);
        clock.advanceSeconds(601);
        PlanVersionService.VersionResult expired = service.createVersion(
                USER, first.planId(), 1, draft(120), Map.of(),
                expiring.warningConfirmationToken().orElseThrow());
        assertThat(expired.status())
                .isEqualTo(PlanVersionService.VersionStatus.WARNING_CONFIRMATION_REQUIRED);
        assertThat(service.getActive(USER).activeVersionNumber()).isEqualTo(1);

        PlanVersionService.VersionResult created = service.createVersion(
                USER, first.planId(), 1, draft(120), Map.of(),
                expired.warningConfirmationToken().orElseThrow());
        assertThat(created.status()).isEqualTo(PlanVersionService.VersionStatus.CREATED);
        PlanVersionService.VersionResult reused = service.createVersion(
                USER, first.planId(), 2, draft(120), Map.of(),
                expired.warningConfirmationToken().orElseThrow());
        assertThat(reused.status())
                .isEqualTo(PlanVersionService.VersionStatus.WARNING_CONFIRMATION_REQUIRED);
        assertThat(service.getActive(USER).activeVersionNumber()).isEqualTo(2);
    }

    private static PlanVersionService service(PlanVersionImmutabilityTest.StubPolicy policy) {
        return new PlanVersionService(
                new InMemoryPlanRepository(), policy,
                Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
