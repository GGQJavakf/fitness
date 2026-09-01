package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.plan.domain.SystemPlanPresetCatalog;
import com.aifitness.assistant.profile.domain.UserProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic five-field matching and ranking for selectable system plan presets. */
public final class PlanPresetMatchPolicy {

    public Match match(SystemPlanPresetCatalog.Preset preset, UserProfile.Details profile) {
        Objects.requireNonNull(preset, "preset must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        List<MismatchField> mismatches = new ArrayList<>(MismatchField.values().length);
        if (!preset.experience().equals(profile.experience().name())) {
            mismatches.add(MismatchField.EXPERIENCE);
        }
        if (!preset.goal().equals(profile.goal().name())) {
            mismatches.add(MismatchField.GOAL);
        }
        if (preset.weeklyFrequency() != profile.weeklyFrequency()) {
            mismatches.add(MismatchField.WEEKLY_FREQUENCY);
        }
        if (preset.sessionMinutes() != profile.sessionMinutes()) {
            mismatches.add(MismatchField.SESSION_MINUTES);
        }
        if (!preset.location().equals(profile.location().name())) {
            mismatches.add(MismatchField.LOCATION);
        }
        List<MismatchField> immutableMismatches = List.copyOf(mismatches);
        return new Match(
                immutableMismatches.isEmpty() ? MatchStatus.EXACT : MatchStatus.PARTIAL,
                immutableMismatches);
    }

    public List<RankedPreset> rank(
            List<SystemPlanPresetCatalog.Preset> presets,
            UserProfile.Details profile) {
        Objects.requireNonNull(presets, "presets must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        // Keep the order explainable: exact, goal, location, experience distance,
        // weekly-frequency distance, session-duration distance, then stable code.
        List<ScoredPreset> ranked = presets.stream()
                .map(preset -> scored(preset, profile))
                .sorted(Comparator
                        .comparing((ScoredPreset value) -> value.match().status() != MatchStatus.EXACT)
                        .thenComparing(value -> !value.goalMatches())
                        .thenComparing(value -> !value.locationMatches())
                        .thenComparingInt(ScoredPreset::experienceDistance)
                        .thenComparingInt(ScoredPreset::weeklyFrequencyDistance)
                        .thenComparingInt(ScoredPreset::sessionMinutesDistance)
                        .thenComparing(value -> value.preset().code()))
                .toList();
        int recommendedIndex = java.util.stream.IntStream.range(0, ranked.size())
                .filter(index -> ranked.get(index).preset().availabilityStatus()
                        == SystemPlanPresetCatalog.AvailabilityStatus.AVAILABLE)
                .filter(index -> ranked.get(index).match().status() == MatchStatus.EXACT)
                .findFirst()
                .orElse(-1);
        return java.util.stream.IntStream.range(0, ranked.size())
                .mapToObj(index -> new RankedPreset(
                        ranked.get(index).preset(), ranked.get(index).match(), index == recommendedIndex))
                .toList();
    }

    private ScoredPreset scored(SystemPlanPresetCatalog.Preset preset, UserProfile.Details profile) {
        Objects.requireNonNull(preset, "preset must not be null");
        return new ScoredPreset(
                preset,
                match(preset, profile),
                preset.goal().equals(profile.goal().name()),
                preset.location().equals(profile.location().name()),
                experienceDistance(preset.experience(), profile.experience()),
                Math.abs(preset.weeklyFrequency() - profile.weeklyFrequency()),
                Math.abs(preset.sessionMinutes() - profile.sessionMinutes()));
    }

    private static int experienceDistance(
            String presetExperience,
            UserProfile.ExperienceLevel profileExperience) {
        try {
            return Math.abs(
                    UserProfile.ExperienceLevel.valueOf(presetExperience).ordinal()
                            - profileExperience.ordinal());
        } catch (IllegalArgumentException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private record ScoredPreset(
            SystemPlanPresetCatalog.Preset preset,
            Match match,
            boolean goalMatches,
            boolean locationMatches,
            int experienceDistance,
            int weeklyFrequencyDistance,
            int sessionMinutesDistance) {}

    public record Match(MatchStatus status, List<MismatchField> mismatchFields) {
        public Match {
            Objects.requireNonNull(status, "match status must not be null");
            mismatchFields = List.copyOf(Objects.requireNonNull(
                    mismatchFields, "mismatch fields must not be null"));
            if ((status == MatchStatus.EXACT) != mismatchFields.isEmpty()) {
                throw new IllegalArgumentException("exact match status must agree with mismatch fields");
            }
        }
    }

    public record RankedPreset(
            SystemPlanPresetCatalog.Preset preset,
            Match match,
            boolean recommended) {
        public RankedPreset {
            Objects.requireNonNull(preset, "preset must not be null");
            Objects.requireNonNull(match, "match must not be null");
        }
    }

    public enum MatchStatus { EXACT, PARTIAL }

    public enum MismatchField {
        EXPERIENCE,
        GOAL,
        WEEKLY_FREQUENCY,
        SESSION_MINUTES,
        LOCATION
    }
}
