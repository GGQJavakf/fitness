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
    void editingAPlanFromAnOlderRuleSnapshotCreatesANewCurrentRuleVersion() {
        RuleReference currentRules = new RuleReference("rule-v2", "template-v2", "content-v1");
        StubPolicy policy = new StubPolicy(draft(90));
        policy.activeRules(currentRules);
        PlanVersionService service = service(policy);
        TrainingPlanVersion first = service.createInitial(USER, "candidate-old-rule").activeVersion();

        PlanVersionService.VersionResult warning = service.createVersion(
                USER, first.planId(), 1, draft(120), Map.of(), null);

        assertThat(warning.status())
                .isEqualTo(PlanVersionService.VersionStatus.WARNING_CONFIRMATION_REQUIRED);
        assertThat(warning.validationIssues())
                .extracting(PlanVersionService.ValidationIssue::reasonCode)
                .contains("RULE_REFERENCE_UPGRADED");

        PlanVersionService.VersionResult created = service.createVersion(
                USER, first.planId(), 1, draft(120), Map.of(),
                warning.warningConfirmationToken().orElseThrow());

        assertThat(created.status()).isEqualTo(PlanVersionService.VersionStatus.CREATED);
        assertThat(created.version()).get()
                .extracting(TrainingPlanVersion::ruleReference)
                .isEqualTo(currentRules);
        assertThat(service.getVersion(USER, first.planId(), 1).ruleReference()).isEqualTo(RULES);
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
    void activatingANewCandidateAppendsAnImmutableVersionAndReplaysIt() {
        PlanDraft original = draft(90);
        PlanDraft regenerated = draft(150);
        StubPolicy policy = new StubPolicy(original);
        PlanVersionService service = service(policy);

        TrainingPlan first = service.createInitial(USER, "candidate-original");
        policy.candidate(regenerated);
        TrainingPlan replacement = service.createInitial(USER, "candidate-regenerated");
        UUID replacementVersionId = replacement.activeVersion().id();

        assertThat(replacement.id()).isEqualTo(first.id());
        assertThat(replacement.activeVersionNumber()).isEqualTo(2);
        assertThat(replacement.activeVersion().sourceType())
                .isEqualTo(TrainingPlanVersion.SourceType.USER_EDIT);
        assertThat(replacement.activeVersion().plan()).isEqualTo(regenerated);
        assertThat(service.getVersion(USER, first.id(), 1).plan()).isEqualTo(original);

        policy.rejectCandidateLookups();
        TrainingPlan replay = service.createInitial(USER, "candidate-regenerated");

        assertThat(replay.activeVersionNumber()).isEqualTo(2);
        assertThat(replay.activeVersion().id()).isEqualTo(replacementVersionId);
        assertThat(replay.versions()).hasSize(2);
    }

    @Test
    void regeneratedCandidatePreservesExistingUserLockedValues() {
        String lockedPath = "/days/DAY_A/exercises/SQUAT/restSeconds";
        PlanDraft original = new PlanDraft(
                "FULL_BODY_3D", "全身训练", draft(90).days(),
                Map.of(lockedPath, FieldLock.Status.USER_LOCKED));
        StubPolicy policy = new StubPolicy(original);
        PlanVersionService service = service(policy);
        TrainingPlan first = service.createInitial(USER, "candidate-locked-original");
        policy.candidate(draft(150));

        TrainingPlan replacement = service.createInitial(USER, "candidate-locked-regenerated");

        assertThat(replacement.id()).isEqualTo(first.id());
        assertThat(replacement.activeVersion().plan().valueAt(lockedPath)).contains(90);
        assertThat(replacement.activeVersion().plan().locks())
                .containsEntry(lockedPath, FieldLock.Status.USER_LOCKED);
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
        assertThatThrownBy(() -> new PlanDraft.Exercise(
                "SQUAT", 3, 8, 12, 90, PlanDraft.WeightStatus.KNOWN,
                java.util.Optional.empty(), 2, 11, 2, false, null, 0, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("target RIR range is invalid");
        assertThatThrownBy(() -> new PlanDraft.Exercise(
                "SQUAT", 3, 8, 12, 90, PlanDraft.WeightStatus.KNOWN,
                java.util.Optional.empty(), 2, 2, 11, false, null, 0, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("eccentric seconds must be between 1 and 10");
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

    @Test
    void fixedPresetExecutionMetadataSurvivesLockPreservationAndVersionCreation() {
        PlanDraft.Exercise press = new PlanDraft.Exercise(
                "SMITH_FLAT_BENCH_PRESS", 4, 6, 10, 120,
                PlanDraft.WeightStatus.NEEDS_CALIBRATION, java.util.Optional.empty(),
                2, 2, 2, false, null, 0, null,
                List.of("胸部发力，稳定下放"));
        PlanDraft.Exercise lateralRaise = new PlanDraft.Exercise(
                "DUMBBELL_LATERAL_RAISE", 3, 12, 20, 60,
                PlanDraft.WeightStatus.NEEDS_CALIBRATION, java.util.Optional.empty(),
                1, 2, null, false, "MONDAY_ARMS", 1, null, List.of());
        PlanDraft.Exercise pushdown = new PlanDraft.Exercise(
                "CABLE_TRICEPS_PUSHDOWN", 3, 10, 15, 60,
                PlanDraft.WeightStatus.NEEDS_CALIBRATION, java.util.Optional.empty(),
                1, 2, null, false, "MONDAY_ARMS", 2,
                new PlanDraft.OptionalSetRule("TUESDAY_UNDER_42_GOOD_STATE", "TUESDAY_BONUS", 1),
                List.of());
        PlanDraft.Day monday = new PlanDraft.Day(
                "MONDAY_PUSH", "周一｜推", List.of(press, lateralRaise, pushdown),
                "MONDAY", "胸＋肩＋三头", 44, 46,
                List.of(new PlanDraft.WarmupStep("跑步机快走或慢跑", "1 分钟", false)),
                List.of("推类训练可以佩戴护腕"));
        PlanDraft preset = new PlanDraft(
                "PERSONAL_5_DAY_HYPERTROPHY_V1", PlanDraft.TrainingSplit.BODY_PART_FIVE_DAY,
                "一周完整增肌训练", List.of(monday), Map.of(),
                "PERSONAL_5_DAY_HYPERTROPHY", "1.0.0",
                List.of("复合动作保留约 2 次余力"), List.of("使用双进阶法"));
        PlanVersionService service = service(new StubPolicy(preset));

        TrainingPlan initial = service.createInitial(USER, "candidate-preset");
        TrainingPlanVersion version = initial.activeVersion();

        assertThat(version.plan().presetCode()).isEqualTo("PERSONAL_5_DAY_HYPERTROPHY");
        assertThat(version.plan().days().getFirst().warmup()).hasSize(1);
        assertThat(version.plan().days().getFirst().exercises().get(1).executionGroup())
                .isEqualTo("MONDAY_ARMS");
        assertThat(version.plan().days().getFirst().exercises().get(2).optionalSetRule())
                .extracting(PlanDraft.OptionalSetRule::exclusiveChoiceGroup)
                .isEqualTo("TUESDAY_BONUS");

        PlanDraft legacyClientEdit = new PlanDraft(
                preset.templateCode(), preset.trainingSplit(), "一周完整增肌训练（已编辑）",
                preset.days(), Map.of());
        PlanVersionService.VersionResult edited = service.createVersion(
                USER, initial.id(), 1, legacyClientEdit, Map.of(), null);

        assertThat(edited.version()).isPresent();
        assertThat(edited.version().orElseThrow().versionNumber()).isEqualTo(2);
        assertThat(edited.plan().name()).isEqualTo("一周完整增肌训练（已编辑）");
        assertThat(edited.plan().presetCode()).isEqualTo("PERSONAL_5_DAY_HYPERTROPHY");
        assertThat(edited.plan().executionRules()).containsExactly("复合动作保留约 2 次余力");
        assertThat(edited.plan().progressionRules()).containsExactly("使用双进阶法");
        assertThat(edited.plan().days().getFirst().warmup()).hasSize(1);
        assertThat(edited.plan().days().getFirst().exercises().get(1).executionGroup())
                .isEqualTo("MONDAY_ARMS");
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
        private PlanDraft candidate;
        private boolean candidateLookupsRejected;
        private List<PlanVersionService.ValidationIssue> issues = List.of();
        private RuleReference activeRules = RULES;

        StubPolicy(PlanDraft candidate) {
            this.candidate = candidate;
        }

        @Override
        public PlanVersionService.CandidatePlan candidate(AuthenticatedUserId user, String candidateId) {
            if (candidateLookupsRejected) {
                throw new AssertionError("candidate replay must not require the expired candidate cache entry");
            }
            return new PlanVersionService.CandidatePlan(candidateId, candidate, RULES);
        }

        @Override
        public List<PlanVersionService.ValidationIssue> validate(
                AuthenticatedUserId user, PlanDraft plan, RuleReference reference) {
            return issues;
        }

        @Override
        public RuleReference effectiveReference(RuleReference sourceReference) {
            return activeRules;
        }

        void issues(PlanVersionService.ValidationIssue... values) {
            issues = List.of(values);
        }

        void activeRules(RuleReference value) {
            activeRules = value;
        }

        void candidate(PlanDraft value) {
            candidate = value;
        }

        void rejectCandidateLookups() {
            candidateLookupsRejected = true;
        }
    }
}
