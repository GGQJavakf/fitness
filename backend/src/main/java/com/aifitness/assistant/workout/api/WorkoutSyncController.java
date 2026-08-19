package com.aifitness.assistant.workout.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutSyncService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.SyncConflict;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sync")
@Profile({"local", "test", "staging-experience"})
public final class WorkoutSyncController {
    private final WorkoutSyncService sync;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkoutSyncController(WorkoutSyncService sync, ObjectMapper json, Clock clock) {
        this.sync = sync;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping("/workout-operations")
    public ApiResponse<BatchData> apply(AuthenticatedUserId user, @RequestBody BatchRequest request) {
        if (request == null || request.operations() == null) {
            throw new IllegalArgumentException("sync operations are required");
        }
        List<WorkoutSyncService.Operation> operations = request.operations().stream().map(this::toOperation).toList();
        return response(new BatchData(sync.apply(user, operations).stream().map(ItemData::from).toList()));
    }

    @GetMapping("/conflicts")
    public ApiResponse<ConflictListData> list(AuthenticatedUserId user) {
        return response(new ConflictListData(sync.listOpenConflicts(user).stream().map(ConflictData::from).toList()));
    }

    @PostMapping("/conflicts/{id}/resolve")
    public ApiResponse<ConflictResolutionData> resolve(
            AuthenticatedUserId user, @PathVariable UUID id, @RequestBody ResolveRequest request) {
        if (request == null || request.resolution() == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("conflict resolution and version are required");
        }
        return response(ConflictResolutionData.from(
                sync.resolveConflict(user, id, request.resolution(), request.expectedVersion())));
    }

    private WorkoutSyncService.Operation toOperation(OperationData operation) {
        if (operation == null || operation.clientOperationSeq() == null) {
            return WorkoutSyncService.Operation.rejected(1, "INVALID_OPERATION");
        }
        long sequence = operation.clientOperationSeq();
        try {
            if (!"UPSERT_SET".equals(operation.operationType())) {
                return WorkoutSyncService.Operation.rejected(sequence, "UNSUPPORTED_OPERATION_TYPE");
            }
            SetPayload payload = json.treeToValue(operation.payload(), SetPayload.class);
            return WorkoutSyncService.Operation.upsert(sequence, payload.sessionId(), operation.clientKey(),
                    payload.expectedSessionVersion(), payload.toCommand(sequence));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            return WorkoutSyncService.Operation.rejected(sequence, "INVALID_OPERATION");
        }
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record BatchRequest(List<OperationData> operations) {}
    public record OperationData(Long clientOperationSeq, String operationType, String clientKey, JsonNode payload) {}
    public record BatchData(List<ItemData> results) {}
    public record ItemData(long clientOperationSeq, WorkoutSyncService.ItemStatus status,
                           Optional<UUID> conflictId, Optional<String> reasonCode) {
        static ItemData from(WorkoutSyncService.ItemResult value) {
            return new ItemData(value.clientOperationSeq(), value.status(), value.conflictId(), value.reasonCode());
        }
    }

    public record SetPayload(
            UUID sessionId, UUID sessionExerciseId, WorkoutSet.SetType setType, int setOrder,
            WorkoutSetController.PerformanceData target, WorkoutSetController.PerformanceData actual,
            Integer remainingReps, WorkoutSet.CompletionStatus completionStatus, Optional<Instant> completedAt,
            Optional<WorkoutSet.SafetyFlag> safetyFlag,
            Long expectedSessionVersion, boolean confirmAnomaly) {

        public SetPayload(
                UUID sessionId,
                UUID sessionExerciseId,
                WorkoutSet.SetType setType,
                int setOrder,
                WorkoutSetController.PerformanceData target,
                WorkoutSetController.PerformanceData actual,
                Integer remainingReps,
                WorkoutSet.CompletionStatus completionStatus,
                Optional<Instant> completedAt,
                Long expectedSessionVersion,
                boolean confirmAnomaly) {
            this(sessionId, sessionExerciseId, setType, setOrder, target, actual, remainingReps,
                    completionStatus, completedAt, Optional.empty(), expectedSessionVersion, confirmAnomaly);
        }

        WorkoutSetService.Command toCommand(long sequence) {
            return new WorkoutSetService.Command(sessionExerciseId, sequence, setType, setOrder,
                    target.toDomain(), actual.toDomain(), remainingReps, completionStatus,
                    completedAt == null ? Optional.empty() : completedAt,
                    safetyFlag == null ? Optional.empty() : safetyFlag, confirmAnomaly);
        }
    }

    public record ConflictListData(List<ConflictData> items) {}
    public record ConflictResolutionData(
            UUID conflictId,
            long clientOperationSeq,
            String clientKey,
            SyncConflict.Resolution resolution,
            WorkoutSyncService.ConflictResolutionOutcome outcome,
            long authoritativeSessionVersion,
            Map<String, Object> authoritativePayload,
            Optional<Map<String, Object>> rebuiltPayload) {
        static ConflictResolutionData from(WorkoutSyncService.ConflictResolutionResult value) {
            return new ConflictResolutionData(
                    value.conflictId(), value.clientOperationSeq(), value.clientKey(), value.resolution(),
                    value.outcome(), value.authoritativeSessionVersion(), value.authoritativePayload(),
                    value.rebuiltPayload());
        }
    }
    public record ConflictData(
            UUID id, String entityType, String entityKey, Map<String, String> localEvidence,
            Map<String, String> serverEvidence, SyncConflict.Status status,
            Optional<SyncConflict.Resolution> resolution, long version, Instant createdAt,
            Optional<Instant> resolvedAt) {
        static ConflictData from(SyncConflict value) {
            return new ConflictData(value.id(), value.entityType(), value.entityKey(), value.localEvidence(),
                    value.serverEvidence(), value.status(), value.resolution(), value.version(),
                    value.createdAt(), value.resolvedAt());
        }
    }
    public record ResolveRequest(SyncConflict.Resolution resolution, Long expectedVersion) {}
}
