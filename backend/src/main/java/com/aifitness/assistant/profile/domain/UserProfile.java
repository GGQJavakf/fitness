package com.aifitness.assistant.profile.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record UserProfile(UUID userId, Details details, long version) {

    public UserProfile {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(details, "details must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    public record Details(
            ExperienceLevel experience,
            FitnessGoal goal,
            int weeklyFrequency,
            int sessionMinutes,
            TrainingLocation location) {

        private static final Set<Integer> ALLOWED_SESSION_MINUTES = Set.of(30, 45, 60, 75, 90);

        public Details {
            Objects.requireNonNull(experience, "experience must not be null");
            Objects.requireNonNull(goal, "goal must not be null");
            Objects.requireNonNull(location, "location must not be null");
            if (weeklyFrequency < 2 || weeklyFrequency > 6) {
                throw new IllegalArgumentException("weeklyFrequency must be between 2 and 6");
            }
            if (!ALLOWED_SESSION_MINUTES.contains(sessionMinutes)) {
                throw new IllegalArgumentException("sessionMinutes is not supported");
            }
        }
    }

    public enum ExperienceLevel {
        BEGINNER,
        INTERMEDIATE,
        ADVANCED
    }

    public enum FitnessGoal {
        STRENGTH,
        HYPERTROPHY,
        FAT_LOSS,
        GENERAL_FITNESS
    }

    public enum TrainingLocation {
        HOME,
        GYM,
        OTHER
    }
}
