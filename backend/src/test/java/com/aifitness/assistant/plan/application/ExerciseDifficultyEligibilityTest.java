package com.aifitness.assistant.plan.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.profile.domain.UserProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExerciseDifficultyEligibilityTest {

    @ParameterizedTest
    @CsvSource({
            "BEGINNER, BEGINNER, true",
            "BEGINNER, INTERMEDIATE, false",
            "BEGINNER, ADVANCED, false",
            "INTERMEDIATE, BEGINNER, true",
            "INTERMEDIATE, INTERMEDIATE, true",
            "INTERMEDIATE, ADVANCED, false",
            "ADVANCED, BEGINNER, true",
            "ADVANCED, INTERMEDIATE, true",
            "ADVANCED, ADVANCED, true"
    })
    void neverSelectsAnExerciseAboveTheUsersExperience(
            UserProfile.ExperienceLevel experience,
            String exerciseDifficulty,
            boolean expected) {
        assertThat(ExerciseDifficultyEligibility.allows(experience, exerciseDifficulty))
                .isEqualTo(expected);
    }
}
