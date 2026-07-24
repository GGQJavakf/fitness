package com.aifitness.assistant.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.FieldLock;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import com.aifitness.assistant.plan.infrastructure.InMemoryPlanRepository;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import com.aifitness.assistant.progression.infrastructure.InMemoryRecommendationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationPlanVersionTest {
    private static final AuthenticatedUserId USER = new AuthenticatedUserId(UUID.fromString(
            "20000000-0000-0000-0000-000000000001"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T11:00:00Z"), ZoneOffset.UTC);
    private static final String WEIGHT_PATH = "/days/DAY_A/exercises/GOBLET_SQUAT/targetWeightKg";

    @Test
    void applyingRecommendationCreatesOneProgressionVersionAndDuplicateApplyIsRejected() {
        Fixture fixture = fixture(Map.of());
        ProgressionRecommendation recommendation = fixture.saveRecommendation();

        ProgressionRecommendation applied = fixture.service.apply(
                USER, recommendation.id(), 1, new BigDecimal("42.5"), "apply-once");

        assertThat(applied.status()).isEqualTo(ProgressionRecommendation.Status.APPLIED);
        assertThat(applied.appliedPlanVersionId()).isPresent();
        TrainingPlanVersion active = fixture.plans.getActive(USER).activeVersion();
        assertThat(active.versionNumber()).isEqualTo(2);
        assertThat(active.sourceType()).isEqualTo(TrainingPlanVersion.SourceType.PROGRESSION);
        assertThat(active.plan().weightAt(WEIGHT_PATH)).hasValueSatisfying(
                value -> assertThat(value).isEqualByComparingTo("42.5"));
        assertThat(fixture.plans.getVersion(USER, active.planId(), 1).plan().weightAt(WEIGHT_PATH))
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("40"));
        assertThatThrownBy(() -> fixture.service.apply(
                USER, recommendation.id(), 2, new BigDecimal("42.5"), "apply-twice"))
                .isInstanceOf(RecommendationService.RecommendationAlreadyDecidedException.class);
        assertThat(fixture.plans.getActive(USER).activeVersionNumber()).isEqualTo(2);
    }

    @Test
    void modifiedAcceptedWeightIsRecordedButLockedWeightCannotBeSilentlyChanged() {
        Fixture unlocked = fixture(Map.of());
        ProgressionRecommendation recommendation = unlocked.saveRecommendation();

        ProgressionRecommendation modified = unlocked.service.apply(
                USER, recommendation.id(), 1, new BigDecimal("45"), "modified-once");

        assertThat(modified.status()).isEqualTo(ProgressionRecommendation.Status.MODIFIED);
        assertThat(unlocked.plans.getActive(USER).activeVersion().plan().weightAt(WEIGHT_PATH))
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("45"));

        Fixture locked = fixture(Map.of(WEIGHT_PATH, FieldLock.Status.USER_LOCKED));
        ProgressionRecommendation lockedRecommendation = locked.saveRecommendation();
        assertThatThrownBy(() -> locked.service.apply(
                USER, lockedRecommendation.id(), 1, new BigDecimal("42.5"), "locked-attempt"))
                .isInstanceOf(RecommendationService.LockedWeightException.class);
        assertThat(locked.plans.getActive(USER).activeVersionNumber()).isEqualTo(1);
        assertThat(locked.service.get(USER, lockedRecommendation.id()).status())
                .isEqualTo(ProgressionRecommendation.Status.PENDING);
    }

    @Test
    void modifiedWeightMustRespectTheSavedEquipmentIncrementBeforeCreatingAPlanVersion() {
        Fixture fixture = fixture(Map.of());
        ProgressionRecommendation recommendation = fixture.saveRecommendation();

        assertThatThrownBy(() -> fixture.service.apply(
                USER, recommendation.id(), 1, new BigDecimal("41.25"), "invalid-increment"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("equipment increment");
        assertThat(fixture.plans.getActive(USER).activeVersionNumber()).isEqualTo(1);
        assertThat(fixture.service.get(USER, recommendation.id()).status())
                .isEqualTo(ProgressionRecommendation.Status.PENDING);
    }

    private static Fixture fixture(Map<String, FieldLock.Status> locks) {
        InMemoryPlanRepository repository = new InMemoryPlanRepository();
        UUID planId = UUID.randomUUID();
        PlanDraft plan = new PlanDraft("FULL_BODY", "基础计划", List.of(new PlanDraft.Day(
                "DAY_A", "训练 A", List.of(new PlanDraft.Exercise(
                        "GOBLET_SQUAT", 3, 8, 12, 90, PlanDraft.WeightStatus.KNOWN,
                        Optional.of(new BigDecimal("40")))))), locks);
        repository.create(USER.value(), new TrainingPlanVersion(
                UUID.randomUUID(), planId, 1, TrainingPlanVersion.SourceType.INITIAL, plan,
                new RuleReference("1.0.0", "1.0.0", "1.0.0"), java.util.Set.of(), CLOCK.instant()));
        PlanVersionService.PlanPolicy policy = new PlanVersionService.PlanPolicy() {
            @Override
            public PlanVersionService.CandidatePlan candidate(AuthenticatedUserId user, String candidateId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<PlanVersionService.ValidationIssue> validate(
                    AuthenticatedUserId user, PlanDraft draft, RuleReference reference) {
                return List.of();
            }
        };
        PlanVersionService plans = new PlanVersionService(repository, policy, CLOCK);
        return new Fixture(plans, new RecommendationService(
                new InMemoryRecommendationRepository(), plans, CLOCK, UUID::randomUUID));
    }

    private record Fixture(PlanVersionService plans, RecommendationService service) {
        ProgressionRecommendation saveRecommendation() {
            return service.save(USER, UUID.randomUUID(), "GOBLET_SQUAT", UUID.randomUUID(),
                    RecommendationLifecycleTest.increaseDecision(), "{\"schemaVersion\":\"1.0.0\"}");
        }
    }
}
