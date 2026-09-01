package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.SystemPlanPresetCatalog;
import com.aifitness.assistant.profile.domain.UserProfile;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanPresetMatchPolicyTest {

    private final PlanPresetMatchPolicy policy = new PlanPresetMatchPolicy();

    @Test
    void matchesAllFiveProfileFieldsAndReportsEveryMismatchExplicitly() {
        UserProfile.Details profile = profile(
                UserProfile.ExperienceLevel.INTERMEDIATE,
                UserProfile.FitnessGoal.HYPERTROPHY,
                4,
                60,
                UserProfile.TrainingLocation.GYM);

        PlanPresetMatchPolicy.Match exact = policy.match(preset(
                "EXACT", "INTERMEDIATE", "HYPERTROPHY", 4, 60, "GYM"), profile);
        PlanPresetMatchPolicy.Match partial = policy.match(preset(
                "PARTIAL", "BEGINNER", "FAT_LOSS", 3, 45, "HOME"), profile);

        assertThat(exact.status()).isEqualTo(PlanPresetMatchPolicy.MatchStatus.EXACT);
        assertThat(exact.mismatchFields()).isEmpty();
        assertThat(partial.status()).isEqualTo(PlanPresetMatchPolicy.MatchStatus.PARTIAL);
        assertThat(partial.mismatchFields()).containsExactly(
                PlanPresetMatchPolicy.MismatchField.EXPERIENCE,
                PlanPresetMatchPolicy.MismatchField.GOAL,
                PlanPresetMatchPolicy.MismatchField.WEEKLY_FREQUENCY,
                PlanPresetMatchPolicy.MismatchField.SESSION_MINUTES,
                PlanPresetMatchPolicy.MismatchField.LOCATION);
    }

    @Test
    void ranksDeterministicallyAndMarksOnlyTheFirstResultRecommended() {
        UserProfile.Details profile = profile(
                UserProfile.ExperienceLevel.INTERMEDIATE,
                UserProfile.FitnessGoal.HYPERTROPHY,
                4,
                60,
                UserProfile.TrainingLocation.GYM);
        List<SystemPlanPresetCatalog.Preset> presets = List.of(
                preset("GOAL_MISMATCH", "INTERMEDIATE", "FAT_LOSS", 4, 60, "GYM"),
                preset("LOCATION_MISMATCH", "INTERMEDIATE", "HYPERTROPHY", 4, 60, "HOME"),
                preset("EXPERIENCE_ONLY", "BEGINNER", "HYPERTROPHY", 4, 60, "GYM"),
                preset("FREQUENCY_ONLY", "INTERMEDIATE", "HYPERTROPHY", 5, 60, "GYM"),
                preset("MINUTES_ONLY", "INTERMEDIATE", "HYPERTROPHY", 4, 45, "GYM"),
                preset("TIE_B", "INTERMEDIATE", "HYPERTROPHY", 5, 45, "GYM"),
                preset("TIE_A", "INTERMEDIATE", "HYPERTROPHY", 5, 45, "GYM"),
                preset("EXACT", "INTERMEDIATE", "HYPERTROPHY", 4, 60, "GYM"));

        List<PlanPresetMatchPolicy.RankedPreset> ranked = policy.rank(presets, profile);

        assertThat(ranked).extracting(value -> value.preset().code()).containsExactly(
                "EXACT",
                "MINUTES_ONLY",
                "FREQUENCY_ONLY",
                "TIE_A",
                "TIE_B",
                "EXPERIENCE_ONLY",
                "LOCATION_MISMATCH",
                "GOAL_MISMATCH");
        assertThat(ranked).filteredOn(PlanPresetMatchPolicy.RankedPreset::recommended)
                .extracting(value -> value.preset().code())
                .containsExactly("EXACT");
    }

    @Test
    void doesNotRecommendAPartialMatchWhenNoExactPresetExists() {
        UserProfile.Details profile = profile(
                UserProfile.ExperienceLevel.BEGINNER,
                UserProfile.FitnessGoal.FAT_LOSS,
                3,
                60,
                UserProfile.TrainingLocation.HOME);

        List<PlanPresetMatchPolicy.RankedPreset> ranked = policy.rank(List.of(
                preset("GOAL_MISMATCH", "BEGINNER", "HYPERTROPHY", 3, 60, "HOME"),
                preset("MINUTES_ONLY", "BEGINNER", "FAT_LOSS", 3, 45, "HOME")), profile);

        assertThat(ranked.getFirst().preset().code()).isEqualTo("MINUTES_ONLY");
        assertThat(ranked.getFirst().match().status()).isEqualTo(PlanPresetMatchPolicy.MatchStatus.PARTIAL);
        assertThat(ranked.getFirst().match().mismatchFields())
                .containsExactly(PlanPresetMatchPolicy.MismatchField.SESSION_MINUTES);
        assertThat(ranked).noneMatch(PlanPresetMatchPolicy.RankedPreset::recommended);
    }

    @Test
    void ranksAnExactBlockedPresetBeforeAvailablePartialMatchesButRecommendsNeither() {
        UserProfile.Details profile = profile(
                UserProfile.ExperienceLevel.BEGINNER,
                UserProfile.FitnessGoal.FAT_LOSS,
                3,
                45,
                UserProfile.TrainingLocation.HOME);
        SystemPlanPresetCatalog.Preset blocked = blockedPreset(
                "BLOCKED", "BEGINNER", "FAT_LOSS", 3, 45, "HOME");
        SystemPlanPresetCatalog.Preset available = preset(
                "AVAILABLE", "BEGINNER", "FAT_LOSS", 4, 30, "HOME");

        List<PlanPresetMatchPolicy.RankedPreset> ranked = policy.rank(List.of(blocked, available), profile);

        assertThat(ranked).extracting(value -> value.preset().code())
                .containsExactly("BLOCKED", "AVAILABLE");
        assertThat(ranked).noneMatch(PlanPresetMatchPolicy.RankedPreset::recommended);
    }

    @Test
    void recommendsTheFirstAvailableExactPresetEvenWhenABlockedExactPresetSortsFirst() {
        UserProfile.Details profile = profile(
                UserProfile.ExperienceLevel.BEGINNER,
                UserProfile.FitnessGoal.FAT_LOSS,
                3,
                45,
                UserProfile.TrainingLocation.HOME);
        SystemPlanPresetCatalog.Preset blocked = blockedPreset(
                "A_BLOCKED", "BEGINNER", "FAT_LOSS", 3, 45, "HOME");
        SystemPlanPresetCatalog.Preset available = preset(
                "B_AVAILABLE", "BEGINNER", "FAT_LOSS", 3, 45, "HOME");

        List<PlanPresetMatchPolicy.RankedPreset> ranked = policy.rank(List.of(available, blocked), profile);

        assertThat(ranked).extracting(value -> value.preset().code())
                .containsExactly("A_BLOCKED", "B_AVAILABLE");
        assertThat(ranked).filteredOn(PlanPresetMatchPolicy.RankedPreset::recommended)
                .extracting(value -> value.preset().code())
                .containsExactly("B_AVAILABLE");
    }

    private static UserProfile.Details profile(
            UserProfile.ExperienceLevel experience,
            UserProfile.FitnessGoal goal,
            int weeklyFrequency,
            int sessionMinutes,
            UserProfile.TrainingLocation location) {
        return new UserProfile.Details(experience, goal, weeklyFrequency, sessionMinutes, location);
    }

    private static SystemPlanPresetCatalog.Preset preset(
            String code,
            String experience,
            String goal,
            int weeklyFrequency,
            int sessionMinutes,
            String location) {
        List<PlanDraft.Day> days = IntStream.range(0, weeklyFrequency)
                .mapToObj(index -> new PlanDraft.Day(
                        "DAY_" + (index + 1),
                        "Day " + (index + 1),
                        List.of(new PlanDraft.Exercise(
                                "EXERCISE_" + (index + 1),
                                1,
                                1,
                                1,
                                0,
                                PlanDraft.WeightStatus.BODYWEIGHT))))
                .toList();
        PlanDraft plan = new PlanDraft(
                code,
                PlanDraft.TrainingSplit.FULL_BODY,
                code,
                days,
                Map.of(),
                code,
                "1.0.0",
                List.of(),
                List.of());
        return new SystemPlanPresetCatalog.Preset(
                code, "1.0.0", code, experience, goal,
                weeklyFrequency, sessionMinutes, location, plan,
                SystemPlanPresetCatalog.ContentStatus.AI_VALIDATED,
                SystemPlanPresetCatalog.ProfessionalReviewStatus.PENDING,
                null,
                null,
                List.of(new SystemPlanPresetCatalog.Source(
                        "TEST_SOURCE",
                        "Test source",
                        "https://example.com/source",
                        null,
                        "Supports test fixture principles only",
                        SystemPlanPresetCatalog.SourceKind.GOVERNMENT_GUIDELINE)),
                List.of());
    }

    private static SystemPlanPresetCatalog.Preset blockedPreset(
            String code,
            String experience,
            String goal,
            int weeklyFrequency,
            int sessionMinutes,
            String location) {
        SystemPlanPresetCatalog.Preset available =
                preset(code, experience, goal, weeklyFrequency, sessionMinutes, location);
        return new SystemPlanPresetCatalog.Preset(
                available.code(), available.version(), available.name(), available.experience(), available.goal(),
                available.weeklyFrequency(), available.sessionMinutes(), available.location(), available.plan(),
                available.contentStatus(), available.professionalReviewStatus(), null, null,
                SystemPlanPresetCatalog.AvailabilityStatus.BLOCKED_CAPABILITY,
                "BANDS 与固定锚点需要 Equipment Inventory V2", null,
                available.sources(), available.explanationSources());
    }
}
