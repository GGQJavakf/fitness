package com.aifitness.assistant.content;

import com.aifitness.assistant.content.application.ContentCatalogRepository;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.content.application.UserEquipmentProvider;
import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.aifitness.assistant.content.domain.ReleaseMetadata;
import com.aifitness.assistant.content.domain.ReleaseStatus;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EligibleContentFilterTest {

    private static final AuthenticatedUserId USER = new AuthenticatedUserId(UUID.randomUUID());

    @Test
    void excludesDraftRetiredDisabledWrongEnvironmentAndInvalidRights() {
        assertThat(exercises(catalog(ReleaseStatus.AI_DRAFT, true, Set.of(ContentEnvironment.LOCAL), validExercise())))
                .isEmpty();
        assertThat(exercises(catalog(ReleaseStatus.RETIRED, true, Set.of(ContentEnvironment.LOCAL), validExercise())))
                .isEmpty();
        assertThat(exercises(catalog(ReleaseStatus.AI_VALIDATED, false, Set.of(ContentEnvironment.LOCAL), validExercise())))
                .isEmpty();
        assertThat(exercises(catalog(ReleaseStatus.AI_VALIDATED, true, Set.of(ContentEnvironment.TEST), validExercise())))
                .isEmpty();
        assertThat(exercises(catalog(ReleaseStatus.AI_VALIDATED, true, Set.of(ContentEnvironment.LOCAL),
                exercise("SQUAT", "UNKNOWN_RIGHTS", true, List.of())))).isEmpty();
    }

    @Test
    void publicEnvironmentRequiresExplicitPublicApproval() {
        ExerciseCatalog aiValidated = catalog(
                ReleaseStatus.AI_VALIDATED, true, Set.of(ContentEnvironment.PUBLIC), validExercise());
        ExerciseCatalog approved = catalog(
                ReleaseStatus.PUBLIC_RELEASE_APPROVED, true, Set.of(ContentEnvironment.PUBLIC), validExercise());

        assertThat(service(aiValidated, ContentEnvironment.PUBLIC).list(USER, ExerciseQueryService.Filter.none()))
                .isEmpty();
        assertThat(service(approved, ContentEnvironment.PUBLIC).list(USER, ExerciseQueryService.Filter.none()))
                .extracting(ExerciseCatalog.Exercise::code)
                .containsExactly("SQUAT");
    }

    @Test
    void requiresPlainLanguageAndReturnsOnlyEligibleSameLevelAlternatives() {
        ExerciseCatalog.Exercise source = exercise("SQUAT", "ORIGINAL_SUMMARY", true, List.of(
                new ExerciseCatalog.Alternative("VALID_ALT", 1, ReleaseStatus.AI_VALIDATED),
                new ExerciseCatalog.Alternative("DRAFT_ALT", 2, ReleaseStatus.AI_DRAFT),
                new ExerciseCatalog.Alternative("INACTIVE_ALT", 3, ReleaseStatus.AI_VALIDATED)));
        ExerciseCatalog catalog = catalog(
                ReleaseStatus.AI_VALIDATED,
                true,
                Set.of(ContentEnvironment.LOCAL),
                source,
                exercise("VALID_ALT", "ORIGINAL_SUMMARY", true, List.of()),
                exercise("DRAFT_ALT", "ORIGINAL_SUMMARY", true, List.of()),
                exercise("INACTIVE_ALT", "ORIGINAL_SUMMARY", false, List.of()));

        ExerciseCatalog.Exercise result = service(catalog, ContentEnvironment.LOCAL).get(USER, "SQUAT").orElseThrow();

        assertThat(result.plainLanguage()).isNotBlank();
        assertThat(result.alternatives()).extracting(ExerciseCatalog.Alternative::exerciseCode)
                .containsExactly("VALID_ALT");
        assertThat(result.image().fallbackRef()).isEqualTo("asset://exercise-placeholder");
    }

    @Test
    void filtersExercisesAndTemplatesAgainstAuthenticatedUsersEquipment() {
        ExerciseCatalog exercises = catalog(
                ReleaseStatus.AI_VALIDATED,
                true,
                Set.of(ContentEnvironment.LOCAL),
                validExercise(),
                exercise("CABLE_ROW", "ORIGINAL_SUMMARY", true, List.of(), Set.of("CABLE")));
        PlanTemplateCatalog templates = new PlanTemplateCatalog(
                metadata(ReleaseStatus.AI_VALIDATED, true, Set.of(ContentEnvironment.LOCAL)),
                "1.0.0",
                List.of(
                        new PlanTemplateCatalog.Template("DUMBBELL_ONLY", "哑铃模板", 3, Set.of("SQUAT")),
                        new PlanTemplateCatalog.Template("NEEDS_CABLE", "绳索模板", 3, Set.of("CABLE_ROW"))));
        ContentCatalogRepository repository = repository(exercises, templates);
        UserEquipmentProvider equipment = userId -> Set.of("DUMBBELL");

        ExerciseQueryService exerciseService = new ExerciseQueryService(repository, equipment, ContentEnvironment.LOCAL);
        TemplateQueryService templateService = new TemplateQueryService(repository, exerciseService, ContentEnvironment.LOCAL);

        assertThat(exerciseService.list(USER, ExerciseQueryService.Filter.none()))
                .extracting(ExerciseCatalog.Exercise::code)
                .containsExactly("SQUAT");
        assertThat(templateService.list(USER, Optional.of(3)))
                .extracting(PlanTemplateCatalog.Template::code)
                .containsExactly("DUMBBELL_ONLY");
    }

    private static List<ExerciseCatalog.Exercise> exercises(ExerciseCatalog catalog) {
        return service(catalog, ContentEnvironment.LOCAL).list(USER, ExerciseQueryService.Filter.none());
    }

    private static ExerciseQueryService service(ExerciseCatalog catalog, ContentEnvironment environment) {
        return new ExerciseQueryService(
                repository(catalog, new PlanTemplateCatalog(metadata(
                        ReleaseStatus.AI_VALIDATED, true, Set.of(ContentEnvironment.LOCAL)), "1.0.0", List.of())),
                userId -> Set.of("DUMBBELL"),
                environment);
    }

    private static ContentCatalogRepository repository(
            ExerciseCatalog exercises, PlanTemplateCatalog templates) {
        return new ContentCatalogRepository() {
            @Override
            public ExerciseCatalog exercises() {
                return exercises;
            }

            @Override
            public PlanTemplateCatalog templates() {
                return templates;
            }
        };
    }

    private static ExerciseCatalog catalog(
            ReleaseStatus status,
            boolean enabled,
            Set<ContentEnvironment> environments,
            ExerciseCatalog.Exercise... exercises) {
        return new ExerciseCatalog(metadata(status, enabled, environments), List.of(exercises));
    }

    private static ReleaseMetadata metadata(
            ReleaseStatus status, boolean enabled, Set<ContentEnvironment> environments) {
        return new ReleaseMetadata("1.0.0", status, enabled, environments, List.of("SOURCE"));
    }

    private static ExerciseCatalog.Exercise validExercise() {
        return exercise("SQUAT", "ORIGINAL_SUMMARY", true, List.of());
    }

    private static ExerciseCatalog.Exercise exercise(
            String code, String rightsStatus, boolean active, List<ExerciseCatalog.Alternative> alternatives) {
        return exercise(code, rightsStatus, active, alternatives, Set.of("DUMBBELL"));
    }

    private static ExerciseCatalog.Exercise exercise(
            String code,
            String rightsStatus,
            boolean active,
            List<ExerciseCatalog.Alternative> alternatives,
            Set<String> equipment) {
        return new ExerciseCatalog.Exercise(
                code,
                code + " 中文名",
                "用日常语言解释这个动作",
                "SQUAT",
                "BEGINNER",
                equipment,
                Set.of("LEGS"),
                List.of("保持稳定", "动作可控"),
                List.of("疼痛时停止"),
                rightsStatus,
                active,
                new ExerciseCatalog.Image("asset://" + code, "asset://exercise-placeholder"),
                alternatives);
    }
}
