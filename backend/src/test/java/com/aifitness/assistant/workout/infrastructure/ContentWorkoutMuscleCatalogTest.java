package com.aifitness.assistant.workout.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContentWorkoutMuscleCatalogTest {

    @Test
    void keepsReleasedOneSixWorkoutFactsReadableAfterTheCatalogUpgrade() {
        ExerciseQueryService exercises = mock(ExerciseQueryService.class);
        when(exercises.version()).thenReturn("1.8.0");
        ContentWorkoutMuscleCatalog catalog = new ContentWorkoutMuscleCatalog(exercises, new ObjectMapper());

        assertThat(catalog.primaryMuscles("CONTRALATERAL_LIMB_RAISE", "1.6.0"))
                .contains(Set.of("BACK", "GLUTES", "SHOULDERS"));
        assertThat(catalog.primaryMuscles("DUMBBELL_BICEPS_CURL", "1.6.0")).isEmpty();
    }
}
