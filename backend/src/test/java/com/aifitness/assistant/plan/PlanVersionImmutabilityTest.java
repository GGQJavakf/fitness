package com.aifitness.assistant.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.FieldLock;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import com.aifitness.assistant.plan.infrastructure.InMemoryPlanRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanVersionImmutabilityTest {

    private static final AuthenticatedUserId USER = new AuthenticatedUserId(
            UUID.fromString("00000000-0000-0000-0000-000000000101"));
    private static final RuleReference RULES = new RuleReference("rule-v1", "template-v1", "content-v1");

    @Test
    void confirmingAnEditCreatesANewVersionWithoutMutatingTheOldVersion() {
        PlanDraft original = draft(90);
        StubPolicy policy = new StubPolicy(original);
        PlanVersionService service = service(policy);

        TrainingPlanVersion first = service.createInitial(USER, "candidate-1").activeVersion();
        PlanDraft edited = draft(120);
        PlanVersionService.VersionResult result = service.createVersion(
                USER, first.planId(), 1, edited, Map.of(), null);

        assertThat(result.status()).isEqualTo(PlanVersionService.VersionStatus.CREATED);
        assertThat(result.version()).get().extracting(TrainingPlanVersion::versionNumber).isEqualTo(2);
        assertThat(service.getVersion(USER, first.planId(), 1).plan()).isEqualTo(original);
        assertThat(service.getActive(USER).activeVersion().plan()).isEqualTo(edited);
    }

    @Test
    void staleBaseVersionCannotCreateAnotherVersion() {
        PlanVersionService service = service(new StubPolicy(draft(90)));
        TrainingPlanVersion first = service.createInitial(USER, "candidate-1").activeVersion();
        service.createVersion(USER, first.planId(), 1, draft(120), Map.of(), null);

        assertThatThrownBy(() -> service.createVersion(
                USER, first.planId(), 1, draft(150), Map.of(), null))
                .isInstanceOf(PlanVersionService.VersionConflictException.class)
                .extracting("currentVersion")
                .isEqualTo(2);
    }

    @Test
    void initialVersionPreservesCandidateFieldLocks() {
        String path = "/days/DAY_A/exercises/SQUAT/restSeconds";
        PlanDraft locked = new PlanDraft(
                "FULL_BODY_3D", "全身训练", draft(90).days(),
                Map.of(path, FieldLock.Status.USER_LOCKED));
        PlanVersionService service = service(new StubPolicy(locked));

        TrainingPlanVersion first = service.createInitial(USER, "candidate-1").activeVersion();

        assertThat(first.plan().locks()).containsEntry(path, FieldLock.Status.USER_LOCKED);
    }

    @Test
    void repeatedInitialCreationForTheSameCandidateReplaysTheCreatedPlan() {
        PlanVersionService service = service(new StubPolicy(draft(90)));

        TrainingPlan first = service.createInitial(USER, "candidate-response-lost");
        TrainingPlan replay = service.createInitial(USER, "candidate-response-lost");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.activeVersion().id()).isEqualTo(first.activeVersion().id());
        assertThat(replay.activeVersionNumber()).isEqualTo(1);
    }

    @Test
    void stableFieldPathsRequireUniqueSlashFreeCodes() {
        PlanDraft.Day day = draft(90).days().getFirst();

        assertThatThrownBy(() -> new PlanDraft(
                "FULL_BODY_3D", "全身训练", List.of(day, day), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("day codes must be unique");
        assertThatThrownBy(() -> new PlanDraft.Day(
                "DAY/A", "训练 A", day.exercises()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("day code must not contain slash");
        assertThatThrownBy(() -> new PlanDraft.Exercise(
                "SQUAT/ALT", 3, 8, 12, 90, PlanDraft.WeightStatus.KNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("exercise code must not contain slash");
        assertThatThrownBy(() -> new PlanDraft(
                "FULL_BODY_3D", "全身训练",
                List.of(new PlanDraft.Day(
                        "DAY_A", "训练 A",
                        List.of(day.exercises().getFirst(), day.exercises().getFirst()))),
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("exercise codes must be unique within a day");
        assertThatThrownBy(() -> new PlanDraft(
                "FULL_BODY_3D", "全身训练", List.of(day),
                Map.of("/days/DAY_A/exercises/UNKNOWN/restSeconds", FieldLock.Status.USER_LOCKED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("locked field target does not exist");
    }

    private static PlanVersionService service(StubPolicy policy) {
        return new PlanVersionService(
                new InMemoryPlanRepository(), policy,
                Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC));
    }

    static PlanDraft draft(int restSeconds) {
        return new PlanDraft(
                "FULL_BODY_3D", "全身训练",
                List.of(new PlanDraft.Day(
                        "DAY_A", "训练 A",
                        List.of(new PlanDraft.Exercise(
                                "SQUAT", 3, 8, 12, restSeconds,
                                PlanDraft.WeightStatus.NEEDS_CALIBRATION)))),
                Map.of());
    }

    static final class StubPolicy implements PlanVersionService.PlanPolicy {
        private final PlanDraft candidate;
        private List<PlanVersionService.ValidationIssue> issues = List.of();

        StubPolicy(PlanDraft candidate) {
            this.candidate = candidate;
        }

        @Override
        public PlanVersionService.CandidatePlan candidate(AuthenticatedUserId user, String candidateId) {
            return new PlanVersionService.CandidatePlan(candidateId, candidate, RULES);
        }

        @Override
        public List<PlanVersionService.ValidationIssue> validate(
                AuthenticatedUserId user, PlanDraft plan, RuleReference reference) {
            return issues;
        }

        void issues(PlanVersionService.ValidationIssue... values) {
            issues = List.of(values);
        }
    }
}
