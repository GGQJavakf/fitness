package com.aifitness.assistant.profile.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record PreferenceProfile(UUID userId, List<Preference> preferences, long version) {

    public PreferenceProfile {
        Objects.requireNonNull(userId, "userId must not be null");
        preferences = List.copyOf(Objects.requireNonNull(preferences, "preferences must not be null"));
        if (preferences.size() > 500) {
            throw new IllegalArgumentException("too many exercise preferences");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        Set<UUID> exerciseIds = new HashSet<>();
        if (preferences.stream().anyMatch(preference -> !exerciseIds.add(preference.exerciseId()))) {
            throw new IllegalArgumentException("an exercise cannot have contradictory preferences");
        }
    }

    public record Preference(UUID exerciseId, PreferenceType preferenceType) {
        public Preference {
            Objects.requireNonNull(exerciseId, "exerciseId must not be null");
            Objects.requireNonNull(preferenceType, "preferenceType must not be null");
        }
    }

    public enum PreferenceType {
        PREFERRED,
        EXCLUDED
    }
}
