package com.aifitness.assistant.workout.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workout-sessions")
@Profile({"local", "test", "staging-experience"})
public final class WorkoutSetController {
    private final WorkoutSetService sets;
    private final Clock clock;

    public WorkoutSetController(WorkoutSetService sets, Clock clock) {
        this.sets = sets;
        this.clock = clock;
    }

    @PutMapping("/{id}/sets/{clientSetKey}")
    public ApiResponse<SetData> upsert(
            AuthenticatedUserId user,
            @PathVariable UUID id,
            @PathVariable String clientSetKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody UpsertRequest request) {
        if (!clientSetKey.equals(idempotencyKey) || request == null || request.expectedSessionVersion() == null) {
            throw new IllegalArgumentException("set idempotency key and session version are required");
        }
        WorkoutSetRepository.SaveResult saved = sets.upsert(
                user, id, clientSetKey, request.expectedSessionVersion(), request.toCommand());
        return response(SetData.from(saved));
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record UpsertRequest(
            UUID sessionExerciseId,
            long clientOperationSeq,
            WorkoutSet.SetType setType,
            int setOrder,
            PerformanceData target,
            PerformanceData actual,
            Integer remainingReps,
            WorkoutSet.CompletionStatus completionStatus,
            Optional<Instant> completedAt,
            Long expectedSessionVersion,
            boolean confirmAnomaly) {
        WorkoutSetService.Command toCommand() {
            if (target == null || actual == null) {
                throw new IllegalArgumentException("set target and actual performance are required");
            }
            return new WorkoutSetService.Command(
                    sessionExerciseId, clientOperationSeq, setType, setOrder,
                    target.toDomain(), actual.toDomain(), remainingReps, completionStatus,
                    completedAt == null ? Optional.empty() : completedAt, confirmAnomaly);
        }
    }

    public record PerformanceData(WeightData weight, Integer reps) {
        WorkoutSet.Performance toDomain() {
            if (weight == null) throw new IllegalArgumentException("weight must not be null");
            return new WorkoutSet.Performance(weight.value(), weight.unit(), reps);
        }
    }

    public record WeightData(BigDecimal value, String unit) {}

    public record SetData(
            UUID setId,
            UUID sessionExerciseId,
            String clientSetKey,
            long clientOperationSeq,
            WorkoutSet.SetType setType,
            int setOrder,
            PerformanceData target,
            PerformanceData actual,
            Integer remainingReps,
            WorkoutSet.CompletionStatus completionStatus,
            Optional<Instant> completedAt,
            long serverRevision,
            long sessionVersion,
            Optional<WorkoutSet.AnomalyStatus> anomalyStatus,
            String syncStatus) {
        static SetData from(WorkoutSetRepository.SaveResult saved) {
            WorkoutSet set = saved.set();
            return new SetData(
                    set.id(), set.sessionExerciseId(), set.clientSetKey(), set.clientOperationSeq(),
                    set.setType(), set.setOrder(), from(set.target()), from(set.actual()), set.remainingReps(),
                    set.completionStatus(), set.completedAt(), set.serverRevision(), saved.sessionVersion(),
                    set.anomalyStatus(), "APPLIED");
        }

        private static PerformanceData from(WorkoutSet.Performance value) {
            return new PerformanceData(new WeightData(value.weight(), value.unit()), value.reps());
        }
    }
}
