package com.aifitness.assistant.workout.api;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @PutMapping("/{sessionId}/sets/{setId}")
    public ApiResponse<SetData> upsert(
            AuthenticatedUserId user,
            @PathVariable("sessionId") UUID id,
            @PathVariable("setId") String clientSetKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody UpsertRequest request) {
        if (!clientSetKey.equals(idempotencyKey) || request == null || request.expectedSessionVersion() == null) {
            throw new IllegalArgumentException("set idempotency key and session version are required");
        }
        WorkoutSetRepository.SaveResult saved = sets.upsert(
                user, id, clientSetKey, request.expectedSessionVersion(), request.toCommand());
        return response(SetData.from(saved));
    }

    @DeleteMapping("/{sessionId}/sets/{setId}")
    public ApiResponse<VoidData> voidSet(
            AuthenticatedUserId user,
            @PathVariable UUID sessionId,
            @PathVariable UUID setId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DeleteRequest request) {
        if (request == null || request.expectedSessionVersion() == null) {
            throw new IllegalArgumentException("expectedSessionVersion is required");
        }
        return response(VoidData.from(sets.voidSet(
                user, sessionId, setId, idempotencyKey, request.expectedSessionVersion())));
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
            Optional<WorkoutSet.SafetyFlag> safetyFlag,
            Long expectedSessionVersion,
            boolean confirmAnomaly) {

        public UpsertRequest(
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
            this(sessionExerciseId, clientOperationSeq, setType, setOrder, target, actual, remainingReps,
                    completionStatus, completedAt, Optional.empty(), expectedSessionVersion, confirmAnomaly);
        }

        WorkoutSetService.Command toCommand() {
            if (target == null || actual == null) {
                throw new IllegalArgumentException("set target and actual performance are required");
            }
            return new WorkoutSetService.Command(
                    sessionExerciseId, clientOperationSeq, setType, setOrder,
                    target.toDomain(), actual.toDomain(), remainingReps, completionStatus,
                    completedAt == null ? Optional.empty() : completedAt,
                    safetyFlag == null ? Optional.empty() : safetyFlag, confirmAnomaly);
        }
    }

    public record PerformanceData(WeightData weight, Integer reps) {
        WorkoutSet.Performance toDomain() {
            if (weight == null) throw new IllegalArgumentException("weight must not be null");
            return new WorkoutSet.Performance(weight.value(), weight.unit(), reps);
        }
    }

    public record WeightData(BigDecimal value, String unit) {}

    public record DeleteRequest(Long expectedSessionVersion) {}

    public record VoidData(
            UUID voidId,
            UUID setId,
            String reason,
            Instant voidedAt,
            long sessionVersion,
            boolean duplicate) {
        static VoidData from(WorkoutSetRepository.VoidResult result) {
            return new VoidData(
                    result.voidFact().id(), result.voidFact().workoutSetId(), result.voidFact().reason().name(),
                    result.voidFact().voidedAt(), result.sessionVersion(), result.duplicate());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
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
            Optional<WorkoutSet.SafetyFlag> safetyFlag,
            Optional<WorkoutSet.AnomalyStatus> anomalyStatus,
            String syncStatus) {
        static SetData from(WorkoutSetRepository.SaveResult saved) {
            WorkoutSet set = saved.set();
            return from(set, saved.sessionVersion());
        }

        static SetData from(WorkoutSet set, long sessionVersion) {
            return new SetData(
                    set.id(), set.sessionExerciseId(), set.clientSetKey(), set.clientOperationSeq(),
                    set.setType(), set.setOrder(), from(set.target()), from(set.actual()), set.remainingReps(),
                    set.completionStatus(), set.completedAt(), set.serverRevision(), sessionVersion,
                    set.safetyFlag(), set.anomalyStatus(), "APPLIED");
        }

        private static PerformanceData from(WorkoutSet.Performance value) {
            return new PerformanceData(new WeightData(value.weight(), value.unit()), value.reps());
        }
    }
}
