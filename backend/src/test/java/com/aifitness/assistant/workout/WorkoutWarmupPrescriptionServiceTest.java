package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.EquipmentProfile;
import com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine;
import com.aifitness.assistant.rules.infrastructure.ClasspathPlanRulePolicyLoader;
import com.aifitness.assistant.workout.application.WorkoutWarmupPrescriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkoutWarmupPrescriptionServiceTest {

    @Test
    void duplicateEquipmentProfilesRequireConcreteSelectionInsteadOfUnioningLevels() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        ExerciseQueryService exercises = mock(ExerciseQueryService.class);
        ProfileService profiles = mock(ProfileService.class);
        ExerciseCatalog.Exercise squat = new ExerciseCatalog.Exercise(
                "DB_SQUAT",
                "哑铃深蹲",
                "持哑铃完成深蹲。",
                "SQUAT",
                "BEGINNER",
                Set.of("DUMBBELL"),
                Set.of("QUADRICEPS"),
                List.of("稳定下蹲"),
                List.of("疼痛时停止"),
                "ORIGINAL_SUMMARY",
                true,
                new ExerciseCatalog.Image("asset://squat", "asset://fallback"),
                List.of());
        when(exercises.catalog()).thenReturn(List.of(squat));
        when(profiles.getEquipment(new AuthenticatedUserId(userId))).thenReturn(new EquipmentProfile(
                userId,
                List.of(
                        equipment("00000000-0000-0000-0000-000000000402", "2.5", "5", "10", "15"),
                        equipment("00000000-0000-0000-0000-000000000403", "2.5", "7.5", "12.5", "20")),
                1));
        var service = new WorkoutWarmupPrescriptionService(
                exercises,
                profiles,
                new WorkoutWarmupPrescriptionEngine(ClasspathPlanRulePolicyLoader.load(new ObjectMapper())));

        var result = service.prescribe(
                new AuthenticatedUserId(userId),
                List.of(new PlanWorkoutSnapshotQuery.ExerciseSource(
                        UUID.fromString("00000000-0000-0000-0000-000000000404"),
                        1,
                        "DB_SQUAT",
                        "哑铃深蹲",
                        "content-v1",
                        Set.of("DUMBBELL"),
                        3,
                        8,
                        12,
                        90,
                        "KNOWN",
                        Optional.of(new BigDecimal("20")),
                        "KG")));

        assertThat(result.rampWarmup()).get().satisfies(ramp -> {
            assertThat(ramp.status()).isEqualTo(WorkoutWarmupPrescriptionEngine.RampStatus.CALIBRATION_REQUIRED);
            assertThat(ramp.calibrationCode()).contains("EQUIPMENT_PROFILE_AMBIGUOUS");
            assertThat(ramp.sets()).isEmpty();
        });
    }

    private static EquipmentProfile.Item equipment(String id, String increment, String... levels) {
        return new EquipmentProfile.Item(
                UUID.fromString(id),
                "DUMBBELL",
                new BigDecimal(increment),
                "KG",
                java.util.Arrays.stream(levels).map(BigDecimal::new).toList());
    }
}
