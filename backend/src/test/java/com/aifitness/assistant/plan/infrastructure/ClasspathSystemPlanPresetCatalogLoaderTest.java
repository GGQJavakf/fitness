package com.aifitness.assistant.plan.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.plan.application.PlanDurationEstimator;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.SystemPlanPresetCatalog;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClasspathSystemPlanPresetCatalogLoaderTest {

    @Test
    void rejectsMissingNonTextualAndUnknownAvailabilityStatusInsteadOfDefaultingToAvailable()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThatThrownBy(() -> ClasspathSystemPlanPresetCatalogLoader.availabilityStatus(
                objectMapper.readTree("{}").path("availabilityStatus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availabilityStatus");
        assertThatThrownBy(() -> ClasspathSystemPlanPresetCatalogLoader.availabilityStatus(
                objectMapper.readTree("{\"availabilityStatus\":true}").path("availabilityStatus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availabilityStatus");
        assertThatThrownBy(() -> ClasspathSystemPlanPresetCatalogLoader.availabilityStatus(
                objectMapper.readTree("{\"availabilityStatus\":\"UNKNOWN\"}").path("availabilityStatus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availabilityStatus")
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void rejectsMissingOrUnsupportedTrainingSplitInsteadOfLoadingAnUnspecifiedPlan() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThatThrownBy(() -> ClasspathSystemPlanPresetCatalogLoader.trainingSplit(
                objectMapper.readTree("{}").path("trainingSplit")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trainingSplit");
        assertThatThrownBy(() -> ClasspathSystemPlanPresetCatalogLoader.trainingSplit(
                objectMapper.readTree("{\"trainingSplit\":\"BRO_SPLIT\"}").path("trainingSplit")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BRO_SPLIT");
    }

    @Test
    void exposesAiValidatedPresetsOnlyInApprovedNonPublicEnvironments() {
        ObjectMapper objectMapper = new ObjectMapper();

        var localPresets = ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.LOCAL).presets();

        assertThat(localPresets).hasSize(5);
        assertThat(localPresets)
                .extracting(preset -> preset.code())
                .contains(
                        "INTERMEDIATE_4_DAY_HYPERTROPHY_GYM_V1",
                        "BEGINNER_3_DAY_HYPERTROPHY_GYM_LOWER_FOCUS_V1",
                        "BEGINNER_3_DAY_FAT_LOSS_HOME_V1",
                        "BEGINNER_4_DAY_FAT_LOSS_HOME_LOW_IMPACT_V1");
        assertThat(localPresets.stream()
                .filter(preset -> preset.code().equals("BEGINNER_3_DAY_FAT_LOSS_HOME_V1"))
                .findFirst().orElseThrow())
                .satisfies(preset -> {
                    assertThat(preset.experience()).isEqualTo("BEGINNER");
                    assertThat(preset.goal()).isEqualTo("FAT_LOSS");
                    assertThat(preset.plan().trainingSplit())
                            .isEqualTo(com.aifitness.assistant.plan.domain.PlanDraft.TrainingSplit.FULL_BODY);
                });
        assertThat(localPresets.stream()
                .filter(preset -> preset.code().equals("BEGINNER_3_DAY_HYPERTROPHY_GYM_LOWER_FOCUS_V1"))
                .findFirst().orElseThrow().plan().trainingSplit())
                .isEqualTo(com.aifitness.assistant.plan.domain.PlanDraft.TrainingSplit.FULL_BODY);
        assertThat(localPresets.stream()
                .filter(preset -> preset.code().equals("BEGINNER_4_DAY_FAT_LOSS_HOME_LOW_IMPACT_V1"))
                .findFirst().orElseThrow()
                .plan().executionRules())
                .contains("日常活动与饮食共同影响能量缺口；本系统不提供具体饮食处方、热量数字、菜单、药物或补剂建议")
                .allSatisfy(rule -> assertThat(rule)
                        .doesNotMatch(".*\\d+\\s*(?:千卡|大卡|[kK][cC][aA][lL]).*"));
        assertThat(ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.TEST).presets()).hasSize(5);
        assertThat(ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.STAGING_EXPERIENCE).presets()).hasSize(5);
        assertThat(ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.PUBLIC).presets()).isEmpty();
    }

    @Test
    void resolvesPerPresetProvenanceWithoutClaimingProfessionalApproval() {
        var presets = ClasspathSystemPlanPresetCatalogLoader.load(
                new ObjectMapper(), ContentEnvironment.LOCAL).presets();

        var personal = preset(presets, "PERSONAL_5_DAY_HYPERTROPHY_V1");
        assertThat(personal.contentStatus())
                .isEqualTo(SystemPlanPresetCatalog.ContentStatus.AI_VALIDATED);
        assertThat(personal.professionalReviewStatus())
                .isEqualTo(SystemPlanPresetCatalog.ProfessionalReviewStatus.PENDING);
        assertThat(personal.reviewRecordId()).isNull();
        assertThat(personal.reviewedAt()).isNull();
        assertThat(personal.sources()).extracting(SystemPlanPresetCatalog.Source::id)
                .containsExactly("INTERNAL_USER_PLAN_20260821");
        assertThat(personal.explanationSources()).isEmpty();

        var fatLoss = preset(presets, "BEGINNER_3_DAY_FAT_LOSS_HOME_V1");
        assertThat(fatLoss.sources()).extracting(SystemPlanPresetCatalog.Source::id)
                .containsExactly(
                        "ACSM_RT_POSITION_2026",
                        "ACSM_WEIGHT_CONSENSUS_2024",
                        "HHS_PAG2_2018");
        assertThat(fatLoss.explanationSources()).extracting(SystemPlanPresetCatalog.Source::id)
                .containsExactly(
                        "CDC_WEIGHT_ACTIVITY_20260407",
                        "CDC_ADULT_ACTIVITY_20231220",
                        "ACSM_RT_SUMMARY_20260317");
        assertThat(fatLoss.sources()).allSatisfy(source -> {
            assertThat(source.title()).isNotBlank();
            assertThat(source.url()).startsWith("https://");
            assertThat(source.usageBoundary()).isNotBlank();
        });
        assertThat(presets.stream()
                .filter(candidate -> !candidate.code().equals("PERSONAL_5_DAY_HYPERTROPHY_V1"))
                .flatMap(candidate -> java.util.stream.Stream.concat(
                        candidate.sources().stream(), candidate.explanationSources().stream())))
                .extracting(SystemPlanPresetCatalog.Source::id)
                .doesNotContain("INTERNAL_USER_PLAN_20260821");
    }

    @Test
    void loadsTheReviewedPersonaPrescriptionsAndKeepsTheBandPlanFailClosed() {
        var presets = ClasspathSystemPlanPresetCatalogLoader.load(
                new ObjectMapper(), ContentEnvironment.LOCAL).presets();

        var intermediate = preset(presets, "INTERMEDIATE_4_DAY_HYPERTROPHY_GYM_V1");
        assertThat(intermediate.name()).isEqualTo("中级四日增肌");
        assertThat(intermediate.availabilityStatus())
                .isEqualTo(SystemPlanPresetCatalog.AvailabilityStatus.AVAILABLE);
        assertThat(intermediate.plan().days()).extracting(day -> day.exercises().size())
                .containsExactly(6, 5, 6, 6);
        assertThat(intermediate.plan().days().get(3).exercises())
                .extracting(PlanDraft.Exercise::exerciseCode)
                .containsExactly(
                        "SEATED_LEG_PRESS", "MACHINE_HIP_THRUST", "DUMBBELL_REVERSE_LUNGE",
                        "SEATED_LEG_EXTENSION", "STANDING_CALF_RAISE", "DEAD_BUG");

        var beginnerGym = preset(presets, "BEGINNER_3_DAY_HYPERTROPHY_GYM_LOWER_FOCUS_V1");
        assertThat(beginnerGym.name()).isEqualTo("新手三日全身增肌");
        assertThat(beginnerGym.introductoryPhase()).isNotNull();
        assertThat(beginnerGym.introductoryPhase().weeks()).isEqualTo(2);
        assertThat(beginnerGym.introductoryPhase().workSets()).isEqualTo(2);
        assertThat(beginnerGym.introductoryPhase().targetRirMin()).isEqualTo(3);
        assertThat(beginnerGym.introductoryPhase().targetRirMax()).isEqualTo(4);
        assertThat(beginnerGym.plan().days()).extracting(day -> day.exercises().size())
                .containsExactly(4, 4, 4);
        assertThat(beginnerGym.plan().days().get(0).exercises())
                .extracting(PlanDraft.Exercise::exerciseCode)
                .containsExactly("GOBLET_SQUAT", "DUMBBELL_BENCH_PRESS", "SEATED_CABLE_ROW", "DEAD_BUG");
        assertThat(beginnerGym.plan().days().get(1).exercises())
                .extracting(PlanDraft.Exercise::exerciseCode)
                .containsExactly("DUMBBELL_ROMANIAN_DEADLIFT", "DUMBBELL_OVERHEAD_PRESS", "LAT_PULLDOWN", "GLUTE_BRIDGE_EXERCISE");
        assertThat(beginnerGym.plan().days().get(2).exercises())
                .extracting(PlanDraft.Exercise::exerciseCode)
                .containsExactly("GOBLET_SQUAT", "DUMBBELL_FLOOR_PRESS", "SEATED_CABLE_ROW", "BIRD_DOG");

        var noJump = preset(presets, "BEGINNER_4_DAY_FAT_LOSS_HOME_LOW_IMPACT_V1");
        assertThat(noJump.name()).isEqualTo("新手四日无跳跃居家基础体能");
        assertThat(noJump.plan().movementImpactConstraint())
                .isEqualTo(PlanDraft.MovementImpactConstraint.NO_JUMP);
        assertThat(noJump.plan().days()).extracting(day -> day.exercises().size())
                .containsExactly(3, 3, 4, 3);
        assertThat(noJump.plan().days()).allSatisfy(day -> assertThat(day.exercises().stream()
                .map(PlanDraft.Exercise::exerciseCode)
                .filter(code -> code.endsWith("PUSH_UP"))
                .count()).isLessThanOrEqualTo(1));

        var blockedBand = preset(presets, "BEGINNER_3_DAY_FAT_LOSS_HOME_V1");
        assertThat(blockedBand.name()).isEqualTo("新手三日弹力带推拉腿（能力待补齐）");
        assertThat(blockedBand.availabilityStatus())
                .isEqualTo(SystemPlanPresetCatalog.AvailabilityStatus.BLOCKED_CAPABILITY);
        assertThat(blockedBand.unavailableReason())
                .contains("BANDS")
                .contains("固定锚点")
                .contains("Equipment Inventory V2");
    }

    @Test
    void everyAvailablePersonaDayFitsItsAdvertisedAndProfileDurationConservatively() {
        var estimator = new PlanDurationEstimator(new PlanRulePolicy.Duration(45, 75));
        var presets = ClasspathSystemPlanPresetCatalogLoader.load(
                new ObjectMapper(), ContentEnvironment.LOCAL).presets().stream()
                .filter(preset -> preset.code().startsWith("INTERMEDIATE_4_DAY_")
                        || preset.code().startsWith("BEGINNER_3_DAY_HYPERTROPHY_")
                        || preset.code().startsWith("BEGINNER_4_DAY_FAT_LOSS_"))
                .toList();

        assertThat(presets).allSatisfy(preset -> assertThat(preset.plan().days()).allSatisfy(day -> {
            int estimatedSeconds = estimator.estimateSeconds(day);
            assertThat(estimatedSeconds).isLessThanOrEqualTo(day.estimatedMinutesMax() * 60);
            assertThat(estimatedSeconds).isLessThanOrEqualTo(preset.sessionMinutes() * 60);
        }));
    }

    @Test
    void rejectsDuplicateAndUnknownSourceReferencesDuringLoading() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        var source = objectMapper.readTree("""
                [{"id":"SOURCE_A","title":"Source A","url":"https://example.com/a",
                  "usageBoundary":"Only supports a general principle",
                  "sourceKind":"GOVERNMENT_GUIDELINE"}]
                """);
        Map<String, SystemPlanPresetCatalog.Source> registry =
                ClasspathSystemPlanPresetCatalogLoader.sourceRegistry(source);

        assertThatThrownBy(() -> ClasspathSystemPlanPresetCatalogLoader.resolveSources(
                objectMapper.readTree("[\"SOURCE_A\",\"SOURCE_A\"]"), registry, "sourceIds"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceIds")
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> ClasspathSystemPlanPresetCatalogLoader.resolveSources(
                objectMapper.readTree("[\"UNKNOWN_SOURCE\"]"), registry, "explanationSourceIds"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_SOURCE");
        assertThatThrownBy(() -> ClasspathSystemPlanPresetCatalogLoader.sourceRegistry(
                objectMapper.readTree("""
                        [{"id":"SOURCE_A","title":"Source A","url":"https://example.com/a",
                          "usageBoundary":"Only supports a general principle",
                          "sourceKind":"GOVERNMENT_GUIDELINE"},
                         {"id":"SOURCE_A","title":"Source A again","url":"https://example.com/b",
                          "usageBoundary":"Only supports another principle",
                          "sourceKind":"GOVERNMENT_GUIDELINE"}]
                        """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOURCE_A")
                .hasMessageContaining("duplicate");
    }

    private static SystemPlanPresetCatalog.Preset preset(
            java.util.List<SystemPlanPresetCatalog.Preset> presets,
            String code) {
        return presets.stream().filter(candidate -> candidate.code().equals(code)).findFirst().orElseThrow();
    }
}
