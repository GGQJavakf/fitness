package com.aifitness.assistant.plan.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.infrastructure.ClasspathContentCatalogRepository;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.SystemPlanPresetCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class PlanConfigurationTest {

    private static final String NO_JUMP_PRESET = "BEGINNER_4_DAY_FAT_LOSS_HOME_LOW_IMPACT_V1";

    @Test
    void acceptsNoJumpPresetWhenEveryReferencedExerciseIsClassifiedNoJump() {
        Fixture fixture = fixture();

        assertThat(PlanConfiguration.validateNoJumpPresetExercises(fixture.presets(), fixture.exercises()))
                .isSameAs(fixture.presets());
    }

    @Test
    void rejectsNoJumpPresetWhenAReferencedExerciseIsMissingFromTheCatalog() {
        Fixture fixture = fixture();
        ExerciseCatalog invalidCatalog = new ExerciseCatalog(
                fixture.exercises().metadata(),
                fixture.exercises().exercises().stream()
                        .filter(exercise -> !exercise.code().equals(fixture.referenceCode()))
                        .toList());

        assertThatThrownBy(() -> PlanConfiguration.validateNoJumpPresetExercises(
                fixture.presets(), invalidCatalog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(NO_JUMP_PRESET)
                .hasMessageContaining(fixture.referenceCode())
                .hasMessageContaining("missing from ExerciseCatalog");
    }

    @Test
    void rejectsNoJumpPresetWhenAReferencedExerciseHasUnknownImpactClass() {
        Fixture fixture = fixture();
        ExerciseCatalog invalidCatalog = replace(
                fixture.exercises(), fixture.referenceCode(), exercise -> withImpactClass(exercise, null));

        assertThatThrownBy(() -> PlanConfiguration.validateNoJumpPresetExercises(
                fixture.presets(), invalidCatalog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(NO_JUMP_PRESET)
                .hasMessageContaining(fixture.referenceCode())
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void rejectsNoJumpPresetWhenAReferencedExerciseIsClassifiedJumping() {
        Fixture fixture = fixture();
        ExerciseCatalog invalidCatalog = replace(
                fixture.exercises(), fixture.referenceCode(),
                exercise -> withImpactClass(exercise, ExerciseCatalog.ImpactClass.JUMPING));

        assertThatThrownBy(() -> PlanConfiguration.validateNoJumpPresetExercises(
                fixture.presets(), invalidCatalog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(NO_JUMP_PRESET)
                .hasMessageContaining(fixture.referenceCode())
                .hasMessageContaining("JUMPING");
    }

    private static Fixture fixture() {
        ObjectMapper objectMapper = new ObjectMapper();
        SystemPlanPresetCatalog presets = ClasspathSystemPlanPresetCatalogLoader.load(
                objectMapper, ContentEnvironment.LOCAL);
        ExerciseCatalog exercises = new ClasspathContentCatalogRepository(objectMapper).exercises();
        String referenceCode = presets.find(NO_JUMP_PRESET).orElseThrow().plan().days().stream()
                .flatMap(day -> day.exercises().stream())
                .map(PlanDraft.Exercise::exerciseCode)
                .findFirst()
                .orElseThrow();
        return new Fixture(presets, exercises, referenceCode);
    }

    private static ExerciseCatalog replace(
            ExerciseCatalog catalog,
            String code,
            UnaryOperator<ExerciseCatalog.Exercise> replacement) {
        List<ExerciseCatalog.Exercise> exercises = catalog.exercises().stream()
                .map(exercise -> exercise.code().equals(code) ? replacement.apply(exercise) : exercise)
                .toList();
        return new ExerciseCatalog(catalog.metadata(), exercises);
    }

    private static ExerciseCatalog.Exercise withImpactClass(
            ExerciseCatalog.Exercise exercise,
            ExerciseCatalog.ImpactClass impactClass) {
        return new ExerciseCatalog.Exercise(
                exercise.code(),
                exercise.name(),
                exercise.plainLanguage(),
                exercise.movementPattern(),
                exercise.difficulty(),
                exercise.equipment(),
                exercise.primaryMuscles(),
                exercise.instructions(),
                exercise.safetyCues(),
                exercise.rightsStatus(),
                exercise.active(),
                exercise.image(),
                exercise.alternatives(),
                impactClass);
    }

    private record Fixture(
            SystemPlanPresetCatalog presets,
            ExerciseCatalog exercises,
            String referenceCode) {}
}
