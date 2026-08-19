package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.profile.domain.UserProfile;
import java.util.Objects;

final class ExerciseDifficultyEligibility {

    private ExerciseDifficultyEligibility() {
    }

    static boolean allows(UserProfile.ExperienceLevel experience, String exerciseDifficulty) {
        Objects.requireNonNull(experience, "experience must not be null");
        Difficulty difficulty = Difficulty.valueOf(Objects.requireNonNull(
                exerciseDifficulty, "exercise difficulty must not be null"));
        return rank(difficulty) <= rank(experience);
    }

    private static int rank(UserProfile.ExperienceLevel experience) {
        return switch (experience) {
            case BEGINNER -> 1;
            case INTERMEDIATE -> 2;
            case ADVANCED -> 3;
        };
    }

    private static int rank(Difficulty difficulty) {
        return switch (difficulty) {
            case BEGINNER -> 1;
            case INTERMEDIATE -> 2;
            case ADVANCED -> 3;
        };
    }

    private enum Difficulty { BEGINNER, INTERMEDIATE, ADVANCED }
}
