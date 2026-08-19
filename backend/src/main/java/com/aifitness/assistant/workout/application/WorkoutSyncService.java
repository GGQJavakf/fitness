package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.SyncConflict;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class WorkoutSyncService {
    private final WorkoutSetService setService;
    private final WorkoutSetRepository sets;
    private final WorkoutSessionRepository sessions;
    private final SyncConflictRepository conflicts;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public WorkoutSyncService(
            WorkoutSetService setService,
            WorkoutSetRepository sets,
            WorkoutSessionRepository sessions,
            SyncConflictRepository conflicts,
            Clock clock,
            Supplier<UUID> ids) {
        this.setService = Objects.requireNonNull(setService);
        this.sets = Objects.requireNonNull(sets);
        this.sessions = Objects.requireNonNull(sessions);
        this.conflicts = Objects.requireNonNull(conflicts);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public List<ItemResult> apply(AuthenticatedUserId user, List<Operation> operations) {
        Objects.requireNonNull(user, "user must not be null");
        if (operations == null || operations.isEmpty() || operations.size() > 100) {
            throw new IllegalArgumentException("sync batch must contain between 1 and 100 operations");
        }
        List<ItemResult> results = new ArrayList<>(operations.size());
        for (Operation operation : operations) results.add(applyOne(user, operation));
        return List.copyOf(results);
    }

    private ItemResult applyOne(AuthenticatedUserId user, Operation operation) {
        if (operation.command().isEmpty()) {
            return new ItemResult(operation.clientOperationSeq(), ItemStatus.REJECTED, Optional.empty(),
                    Optional.of(operation.rejectionReason().orElse("INVALID_OPERATION")));
        }
        WorkoutSetService.Command command = operation.command().orElseThrow();
        try {
            var saved = setService.upsert(
                    user, operation.sessionId(), operation.clientKey(), operation.expectedSessionVersion(), command);
            return new ItemResult(operation.clientOperationSeq(),
                    saved.duplicate() ? ItemStatus.DUPLICATE : ItemStatus.APPLIED,
                    Optional.empty(), Optional.empty());
        } catch (WorkoutSessionService.IdempotencyConflictException exception) {
            WorkoutSession session = ownedSession(user, operation.sessionId());
            WorkoutSet server = sets.find(user.value(), operation.sessionId(),
                            command.sessionExerciseId(), operation.clientKey())
                    .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
            WorkoutSetService.Command effective = setService.normalize(command);
            SyncConflict conflict = conflicts.save(new SyncConflict(
                    ids.get(), user.value(), "WORKOUT_SET", operation.clientKey(),
                    evidence(operation, effective, command.completedAt().isPresent()),
                    evidence(server, session.version()), SyncConflict.Status.OPEN, Optional.empty(), 0,
                    clock.instant(), Optional.empty()));
            return new ItemResult(operation.clientOperationSeq(), ItemStatus.CONFLICT,
                    Optional.of(conflict.id()), Optional.of("IDEMPOTENCY_KEY_REUSED"));
        } catch (WorkoutSetService.SessionNotAcceptingSetsException exception) {
            WorkoutSession session = ownedSession(user, operation.sessionId());
            WorkoutSetService.Command effective = setService.normalize(command);
            SyncConflict conflict = conflicts.save(new SyncConflict(
                    ids.get(), user.value(), "WORKOUT_SET", operation.clientKey(),
                    evidence(operation, effective, command.completedAt().isPresent()),
                    terminalEvidence(session), SyncConflict.Status.OPEN, Optional.empty(), 0,
                    clock.instant(), Optional.empty()));
            return new ItemResult(operation.clientOperationSeq(), ItemStatus.CONFLICT,
                    Optional.of(conflict.id()), Optional.of("SESSION_TERMINAL"));
        } catch (WorkoutSessionService.VersionConflictException
                | WorkoutSessionService.SessionNotFoundException
                | WorkoutSetService.AnomalyConfirmationRequiredException
                | IllegalArgumentException exception) {
            return new ItemResult(operation.clientOperationSeq(), ItemStatus.REJECTED, Optional.empty(),
                    Optional.of(reason(exception)));
        }
    }

    public List<SyncConflict> listOpenConflicts(AuthenticatedUserId user) {
        Objects.requireNonNull(user, "user must not be null");
        return conflicts.listOpen(user.value());
    }

    public ConflictResolutionResult resolveConflict(
            AuthenticatedUserId user,
            UUID conflictId,
            SyncConflict.Resolution resolution,
            long expectedVersion) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(conflictId, "conflict id must not be null");
        Objects.requireNonNull(resolution, "conflict resolution must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expected conflict version must not be negative");
        }
        return conflicts.resolve(
                        user.value(), conflictId, resolution, expectedVersion,
                        (conflict, replayed) -> resolveSelected(user, conflict, resolution, replayed))
                .value();
    }

    private SyncConflictRepository.ResolutionActionResult<ConflictResolutionResult> resolveSelected(
            AuthenticatedUserId user,
            SyncConflict conflict,
            SyncConflict.Resolution resolution,
            boolean replayed) {
        TrustedLocalEvidence local = TrustedLocalEvidence.parse(conflict.localEvidence());
        if (replayed && "true".equals(conflict.serverEvidence().get("resolutionSnapshot"))) {
            long sessionVersion = Long.parseLong(
                    TrustedLocalEvidence.required(conflict.serverEvidence(), "authoritativeSessionVersion"));
            ConflictResolutionResult result = new ConflictResolutionResult(
                    conflict.id(), local.clientOperationSeq(), conflict.entityKey(), resolution,
                    outcome(resolution), sessionVersion,
                    authoritativePayload(conflict.entityKey(), conflict.serverEvidence()), Optional.empty());
            return new SyncConflictRepository.ResolutionActionResult<>(result, conflict.serverEvidence());
        }
        WorkoutSession session = ownedSession(user, local.sessionId());
        Optional<WorkoutSet> server = sets.find(
                user.value(), local.sessionId(), local.command().sessionExerciseId(), conflict.entityKey());

        if (!replayed && resolution == SyncConflict.Resolution.KEEP_LOCAL) {
            if (session.status().terminal()) {
                throw new WorkoutSetService.SessionNotAcceptingSetsException();
            }
            WorkoutSet existing = server.orElseThrow(() -> new IllegalArgumentException(
                    "conflict does not reference a correctable workout set"));
            WorkoutSetRepository.SaveResult corrected = setService.correct(
                    user, existing, session.version(), conflict.id(), local.command(),
                    local.completedAtWasProvided());
            session = ownedSession(user, local.sessionId());
            server = Optional.of(corrected.set());
        }

        ConflictResolutionOutcome outcome = outcome(resolution);
        Map<String, Object> authoritative = server.isPresent()
                ? authoritativePayload(server.orElseThrow(), session.version())
                : authoritativePayload(session, conflict.serverEvidence());
        Map<String, String> finalEvidence = server.isPresent()
                ? new LinkedHashMap<>(evidence(server.orElseThrow(), session.version()))
                : new LinkedHashMap<>(terminalEvidence(session));
        finalEvidence.put("kind", server.isPresent() ? "WORKOUT_SET" : "WORKOUT_SESSION");
        finalEvidence.put("resolutionSnapshot", "true");
        ConflictResolutionResult result = new ConflictResolutionResult(
                conflict.id(), local.clientOperationSeq(), conflict.entityKey(), resolution, outcome,
                session.version(), authoritative, Optional.empty());
        return new SyncConflictRepository.ResolutionActionResult<>(result, Map.copyOf(finalEvidence));
    }

    private static ConflictResolutionOutcome outcome(SyncConflict.Resolution resolution) {
        return resolution == SyncConflict.Resolution.KEEP_LOCAL
                ? ConflictResolutionOutcome.ACKNOWLEDGED
                : ConflictResolutionOutcome.ABANDONED;
    }

    private WorkoutSession ownedSession(AuthenticatedUserId user, UUID sessionId) {
        return sessions.findByIdAndUser(sessionId, user.value())
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
    }

    private static Map<String, String> evidence(
            Operation operation, WorkoutSetService.Command command, boolean completedAtWasProvided) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sessionId", operation.sessionId().toString());
        values.put("clientOperationSeq", Long.toString(operation.clientOperationSeq()));
        values.put("expectedSessionVersion", Long.toString(operation.expectedSessionVersion()));
        values.put("sessionExerciseId", command.sessionExerciseId().toString());
        values.put("setType", command.setType().name());
        values.put("setOrder", Integer.toString(command.setOrder()));
        values.put("targetWeightKg", command.target().weight().toPlainString());
        values.put("targetUnit", command.target().unit());
        values.put("targetReps", command.target().reps().toString());
        values.put("actualWeightKg", command.actual().weight().toPlainString());
        values.put("actualUnit", command.actual().unit());
        values.put("actualReps", command.actual().reps().toString());
        values.put("remainingReps", command.remainingReps() == null ? "NONE" : command.remainingReps().toString());
        values.put("completionStatus", command.completionStatus().name());
        values.put("completedAt", command.completedAt().map(Instant::toString).orElse("NONE"));
        values.put("safetyFlag", command.safetyFlag().map(Enum::name).orElse("NONE"));
        values.put("completedAtWasProvided", Boolean.toString(completedAtWasProvided));
        values.put("confirmAnomaly", Boolean.toString(command.confirmAnomaly()));
        return values;
    }

    private static Map<String, String> evidence(WorkoutSet set, long authoritativeSessionVersion) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sessionId", set.sessionId().toString());
        values.put("authoritativeSessionVersion", Long.toString(authoritativeSessionVersion));
        values.put("sessionExerciseId", set.sessionExerciseId().toString());
        values.put("setType", set.setType().name());
        values.put("setOrder", Integer.toString(set.setOrder()));
        values.put("targetWeightKg", set.target().weight().toPlainString());
        values.put("targetUnit", set.target().unit());
        values.put("targetReps", set.target().reps().toString());
        values.put("actualWeightKg", set.actual().weight().toPlainString());
        values.put("actualUnit", set.actual().unit());
        values.put("actualReps", set.actual().reps().toString());
        values.put("remainingReps", set.remainingReps() == null ? "NONE" : set.remainingReps().toString());
        values.put("completionStatus", set.completionStatus().name());
        values.put("completedAt", set.completedAt().map(Instant::toString).orElse("NONE"));
        values.put("serverRevision", Long.toString(set.serverRevision()));
        values.put("safetyFlag", set.safetyFlag().map(Enum::name).orElse("NONE"));
        values.put("payloadDigest", set.payloadDigest());
        return values;
    }

    private static Map<String, String> terminalEvidence(WorkoutSession session) {
        return Map.of(
                "sessionId", session.id().toString(),
                "authoritativeSessionVersion", Long.toString(session.version()),
                "status", session.status().name(),
                "reasonCode", "SESSION_TERMINAL");
    }

    private static Map<String, Object> authoritativePayload(WorkoutSet set, long sessionVersion) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("targetWeightKg", canonicalWeight(set.target().weight()));
        target.put("unit", set.target().unit());
        target.put("targetReps", set.target().reps());
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("actualWeightKg", canonicalWeight(set.actual().weight()));
        actual.put("unit", set.actual().unit());
        actual.put("actualReps", set.actual().reps());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", "WORKOUT_SET");
        values.put("sessionId", set.sessionId());
        values.put("sessionExerciseId", set.sessionExerciseId());
        values.put("clientSetKey", set.clientSetKey());
        values.put("setType", set.setType().name());
        values.put("setOrder", set.setOrder());
        values.put("target", Collections.unmodifiableMap(target));
        values.put("actual", Collections.unmodifiableMap(actual));
        values.put("remainingReps", set.remainingReps());
        values.put("completionStatus", set.completionStatus().name());
        values.put("completedAt", set.completedAt().orElse(null));
        values.put("serverRevision", set.serverRevision());
        values.put("safetyFlag", set.safetyFlag().map(Enum::name).orElse(null));
        values.put("authoritativeSessionVersion", sessionVersion);
        return Collections.unmodifiableMap(values);
    }

    private static BigDecimal canonicalWeight(BigDecimal weight) {
        return new BigDecimal(weight.stripTrailingZeros().toPlainString());
    }

    private static Map<String, Object> authoritativePayload(
            WorkoutSession session, Map<String, String> serverEvidence) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", "WORKOUT_SESSION");
        values.put("sessionId", session.id());
        values.put("status", session.status().name());
        values.put("authoritativeSessionVersion", session.version());
        values.put("reasonCode", serverEvidence.getOrDefault("reasonCode", "SERVER_AUTHORITY"));
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, Object> authoritativePayload(
            String clientKey, Map<String, String> evidence) {
        String kind = TrustedLocalEvidence.required(evidence, "kind");
        long sessionVersion = Long.parseLong(
                TrustedLocalEvidence.required(evidence, "authoritativeSessionVersion"));
        if ("WORKOUT_SESSION".equals(kind)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("kind", kind);
            values.put("sessionId", UUID.fromString(TrustedLocalEvidence.required(evidence, "sessionId")));
            values.put("status", TrustedLocalEvidence.required(evidence, "status"));
            values.put("authoritativeSessionVersion", sessionVersion);
            values.put("reasonCode", evidence.getOrDefault("reasonCode", "SERVER_AUTHORITY"));
            return Collections.unmodifiableMap(values);
        }
        if (!"WORKOUT_SET".equals(kind)) {
            throw new IllegalStateException("stored sync resolution kind is invalid");
        }
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("targetWeightKg", canonicalWeight(
                new BigDecimal(TrustedLocalEvidence.required(evidence, "targetWeightKg"))));
        target.put("unit", TrustedLocalEvidence.required(evidence, "targetUnit"));
        target.put("targetReps", Integer.valueOf(TrustedLocalEvidence.required(evidence, "targetReps")));
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("actualWeightKg", canonicalWeight(
                new BigDecimal(TrustedLocalEvidence.required(evidence, "actualWeightKg"))));
        actual.put("unit", TrustedLocalEvidence.required(evidence, "actualUnit"));
        actual.put("actualReps", Integer.valueOf(TrustedLocalEvidence.required(evidence, "actualReps")));
        String remaining = TrustedLocalEvidence.required(evidence, "remainingReps");
        String completedAt = TrustedLocalEvidence.required(evidence, "completedAt");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", kind);
        values.put("sessionId", UUID.fromString(TrustedLocalEvidence.required(evidence, "sessionId")));
        values.put("sessionExerciseId",
                UUID.fromString(TrustedLocalEvidence.required(evidence, "sessionExerciseId")));
        values.put("clientSetKey", clientKey);
        values.put("setType", TrustedLocalEvidence.required(evidence, "setType"));
        values.put("setOrder", Integer.valueOf(TrustedLocalEvidence.required(evidence, "setOrder")));
        values.put("target", Collections.unmodifiableMap(target));
        values.put("actual", Collections.unmodifiableMap(actual));
        values.put("remainingReps", "NONE".equals(remaining) ? null : Integer.valueOf(remaining));
        values.put("completionStatus", TrustedLocalEvidence.required(evidence, "completionStatus"));
        values.put("completedAt", "NONE".equals(completedAt) ? null : Instant.parse(completedAt));
        values.put("serverRevision", Long.valueOf(TrustedLocalEvidence.required(evidence, "serverRevision")));
        String safetyFlag = TrustedLocalEvidence.required(evidence, "safetyFlag");
        values.put("safetyFlag", "NONE".equals(safetyFlag) ? null : safetyFlag);
        values.put("authoritativeSessionVersion", sessionVersion);
        return Collections.unmodifiableMap(values);
    }

    private static String reason(RuntimeException exception) {
        if (exception instanceof WorkoutSessionService.VersionConflictException) return "VERSION_CONFLICT";
        if (exception instanceof WorkoutSessionService.SessionNotFoundException) return "RESOURCE_NOT_FOUND";
        if (exception instanceof WorkoutSetService.AnomalyConfirmationRequiredException) {
            return "ANOMALY_CONFIRMATION_REQUIRED";
        }
        return "VALIDATION_FAILED";
    }

    public record Operation(
            long clientOperationSeq,
            UUID sessionId,
            String clientKey,
            long expectedSessionVersion,
            Optional<WorkoutSetService.Command> command,
            Optional<String> rejectionReason) {
        public Operation {
            if (clientOperationSeq < 1) throw new IllegalArgumentException("operation sequence must be positive");
            command = Objects.requireNonNull(command);
            rejectionReason = Objects.requireNonNull(rejectionReason);
        }

        public static Operation upsert(
                long sequence, UUID sessionId, String clientKey, long expectedVersion,
                WorkoutSetService.Command command) {
            return new Operation(sequence, Objects.requireNonNull(sessionId), clientKey, expectedVersion,
                    Optional.of(command), Optional.empty());
        }

        public static Operation rejected(long sequence, String reason) {
            return new Operation(sequence, null, "rejected-operation", 0, Optional.empty(), Optional.of(reason));
        }
    }

    public record ItemResult(
            long clientOperationSeq, ItemStatus status, Optional<UUID> conflictId, Optional<String> reasonCode) {}

    public record ConflictResolutionResult(
            UUID conflictId,
            long clientOperationSeq,
            String clientKey,
            SyncConflict.Resolution resolution,
            ConflictResolutionOutcome outcome,
            long authoritativeSessionVersion,
            Map<String, Object> authoritativePayload,
            Optional<Map<String, Object>> rebuiltPayload) {
        public ConflictResolutionResult {
            authoritativePayload = Collections.unmodifiableMap(new LinkedHashMap<>(authoritativePayload));
            rebuiltPayload = Objects.requireNonNull(rebuiltPayload, "rebuilt payload must not be null");
        }
    }

    public enum ConflictResolutionOutcome { ACKNOWLEDGED, REBUILT, ABANDONED }
    public enum ItemStatus { APPLIED, DUPLICATE, CONFLICT, REJECTED }

    private record TrustedLocalEvidence(
            UUID sessionId,
            long clientOperationSeq,
            WorkoutSetService.Command command,
            boolean completedAtWasProvided) {
        private static TrustedLocalEvidence parse(Map<String, String> values) {
            try {
                long sequence = Long.parseLong(required(values, "clientOperationSeq"));
                if (sequence < 1) throw new IllegalArgumentException("invalid client operation sequence");
                String remaining = required(values, "remainingReps");
                String completedAt = required(values, "completedAt");
                WorkoutSetService.Command command = new WorkoutSetService.Command(
                        UUID.fromString(required(values, "sessionExerciseId")), sequence,
                        WorkoutSet.SetType.valueOf(required(values, "setType")),
                        Integer.parseInt(required(values, "setOrder")),
                        new WorkoutSet.Performance(
                                new BigDecimal(required(values, "targetWeightKg")),
                                required(values, "targetUnit"), Integer.valueOf(required(values, "targetReps"))),
                        new WorkoutSet.Performance(
                                new BigDecimal(required(values, "actualWeightKg")),
                                required(values, "actualUnit"), Integer.valueOf(required(values, "actualReps"))),
                        "NONE".equals(remaining) ? null : Integer.valueOf(remaining),
                        WorkoutSet.CompletionStatus.valueOf(required(values, "completionStatus")),
                        "NONE".equals(completedAt)
                                ? Optional.empty()
                                : Optional.of(Instant.parse(completedAt)),
                        "NONE".equals(required(values, "safetyFlag"))
                                ? Optional.empty()
                                : Optional.of(WorkoutSet.SafetyFlag.valueOf(required(values, "safetyFlag"))),
                        Boolean.parseBoolean(required(values, "confirmAnomaly")));
                return new TrustedLocalEvidence(
                        UUID.fromString(required(values, "sessionId")), sequence, command,
                        Boolean.parseBoolean(required(values, "completedAtWasProvided")));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("stored sync conflict evidence is invalid", exception);
            }
        }

        private static String required(Map<String, String> values, String field) {
            String value = values.get(field);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing conflict evidence field " + field);
            }
            return value;
        }
    }
}
