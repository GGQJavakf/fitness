package com.aifitness.assistant.workout.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutRecoveryCheckService;
import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only preflight; it never creates, reorders, or modifies a workout session. */
@RestController
@RequestMapping("/api/v1/workout-recovery-checks")
@Profile({"local", "test", "staging-experience"})
public final class WorkoutRecoveryController {
    private final WorkoutRecoveryCheckService recovery;
    private final Clock clock;

    public WorkoutRecoveryController(WorkoutRecoveryCheckService recovery, Clock clock) {
        this.recovery = recovery;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<RecoveryCheckData> check(
            AuthenticatedUserId user,
            @RequestParam UUID planId,
            @RequestParam int planVersionNo,
            @RequestParam String trainingDayCode) {
        WorkoutRecoveryAssessment assessment = recovery.check(
                user, planId, planVersionNo, trainingDayCode);
        return new ApiResponse<>(RecoveryCheckData.from(assessment), new ResponseMeta(requestId(), clock.instant()));
    }

    private static String requestId() {
        String requestId = MDC.get("requestId");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    public record RecoveryCheckData(
            WorkoutRecoveryAssessment.Decision decision,
            String policyVersion,
            Instant checkedAt,
            int minimumRecoveryHours,
            List<AffectedMuscleData> affectedMuscles) {
        public static RecoveryCheckData from(WorkoutRecoveryAssessment assessment) {
            return new RecoveryCheckData(
                    assessment.decision(),
                    assessment.policyVersion(),
                    assessment.checkedAt(),
                    assessment.minimumRecoveryHours(),
                    assessment.affectedMuscles().stream().map(AffectedMuscleData::from).toList());
        }
    }

    public record AffectedMuscleData(
            String muscleGroup,
            long elapsedHours,
            int minimumRecoveryHours,
            Instant lastCompletedAt) {
        static AffectedMuscleData from(WorkoutRecoveryAssessment.AffectedMuscle affected) {
            return new AffectedMuscleData(
                    affected.muscleGroup(),
                    affected.elapsedHours(),
                    affected.minimumRecoveryHours(),
                    affected.lastCompletedAt());
        }
    }
}
