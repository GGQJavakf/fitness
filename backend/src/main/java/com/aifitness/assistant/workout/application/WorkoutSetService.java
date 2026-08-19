package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class WorkoutSetService {
    private final WorkoutSetRepository sets;
    private final InputPolicy policy;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public WorkoutSetService(
            WorkoutSetRepository sets, InputPolicy policy, Clock clock, Supplier<UUID> ids) {
        this.sets = Objects.requireNonNull(sets, "sets must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    public WorkoutSetRepository.SaveResult upsert(
            AuthenticatedUserId user,
            UUID sessionId,
            String clientSetKey,
            long expectedSessionVersion,
            Command command) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Command normalized = normalize(command);
        WorkoutSet set = candidate(ids.get(), sessionId, clientSetKey, normalized, command, 0);
        return sets.save(user.value(), set, expectedSessionVersion);
    }

    public WorkoutSetRepository.SaveResult correct(
            AuthenticatedUserId user,
            WorkoutSet existing,
            long expectedSessionVersion,
            UUID conflictId,
            Command command,
            boolean completedAtWasProvided) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(existing, "existing set must not be null");
        Objects.requireNonNull(conflictId, "conflict id must not be null");
        Command local = normalize(command);
        Command normalized = new Command(
                existing.sessionExerciseId(), existing.clientOperationSeq(), existing.setType(),
                existing.setOrder(), existing.target(), local.actual(), local.remainingReps(),
                local.completionStatus(), local.completedAt(), local.safetyFlag(), local.confirmAnomaly());
        Command digestSource = completedAtWasProvided
                ? normalized
                : normalized.withCompletedAt(Optional.empty());
        WorkoutSet corrected = candidate(
                existing.id(), existing.sessionId(), existing.clientSetKey(), normalized, digestSource,
                existing.serverRevision() + 1);
        return sets.correct(user.value(), corrected, expectedSessionVersion, conflictId, clock.instant());
    }

    public Command normalize(Command command) {
        Objects.requireNonNull(command, "command must not be null");
        validateRanges(command);
        List<String> anomalies = anomalies(command);
        if (!anomalies.isEmpty() && !command.confirmAnomaly()) {
            throw new AnomalyConfirmationRequiredException(anomalies);
        }
        Optional<Instant> completedAt = command.completionStatus() == WorkoutSet.CompletionStatus.COMPLETED
                ? Optional.of(command.completedAt().orElseGet(clock::instant))
                : Optional.empty();
        return new Command(
                command.sessionExerciseId(), command.clientOperationSeq(), command.setType(), command.setOrder(),
                command.target(), command.actual(), command.remainingReps(), command.completionStatus(),
                completedAt, command.safetyFlag(), command.confirmAnomaly());
    }

    private WorkoutSet candidate(
            UUID id,
            UUID sessionId,
            String clientSetKey,
            Command command,
            Command digestSource,
            long serverRevision) {
        List<String> anomalies = anomalies(command);
        return new WorkoutSet(
                id, sessionId, command.sessionExerciseId(), clientSetKey,
                command.clientOperationSeq(), command.setType(), command.setOrder(),
                command.target(), command.actual(), command.remainingReps(), command.completionStatus(),
                command.completedAt(), serverRevision, command.safetyFlag(),
                anomalies.isEmpty() ? Optional.empty()
                        : Optional.of(WorkoutSet.AnomalyStatus.CONFIRMED_EXCLUDED),
                digest(digestSource));
    }

    public WorkoutSetRepository.VoidResult voidSet(
            AuthenticatedUserId user,
            UUID sessionId,
            UUID setId,
            String idempotencyKey,
            long expectedSessionVersion) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(setId, "setId must not be null");
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("void idempotency key is invalid");
        }
        if (expectedSessionVersion < 0) {
            throw new IllegalArgumentException("expected session version must not be negative");
        }
        return sets.appendVoid(
                user.value(), sessionId, setId, idempotencyKey, digestVoid(sessionId, setId),
                expectedSessionVersion, ids.get(), clock.instant());
    }

    private void validateRanges(Command command) {
        if (command.target().weight().compareTo(policy.maxWeightKg()) > 0
                || command.actual().weight().compareTo(policy.maxWeightKg()) > 0
                || command.target().reps() > policy.maxReps()
                || command.actual().reps() > policy.maxReps()) {
            throw new IllegalArgumentException("workout set exceeds the configured safe input range");
        }
    }

    private List<String> anomalies(Command command) {
        BigDecimal target = command.target().weight();
        BigDecimal actual = command.actual().weight();
        BigDecimal difference = actual.subtract(target).abs();
        boolean hasZeroBoundary = target.signum() == 0 || actual.signum() == 0;
        boolean ratioChanged = hasZeroBoundary
                ? target.signum() != actual.signum()
                : target.max(actual).divide(target.min(actual), 4, java.math.RoundingMode.HALF_UP)
                        .compareTo(policy.largeChangeRatio()) >= 0;
        return difference.compareTo(policy.largeChangeKg()) >= 0
                        || ratioChanged
                ? List.of("LARGE_WEIGHT_CHANGE") : List.of();
    }

    private static String digest(Command command) {
        String canonical = String.join("|",
                command.sessionExerciseId().toString(), Long.toString(command.clientOperationSeq()),
                command.setType().name(), Integer.toString(command.setOrder()),
                decimal(command.target().weight()), command.target().unit(), command.target().reps().toString(),
                decimal(command.actual().weight()), command.actual().unit(), command.actual().reps().toString(),
                String.valueOf(command.remainingReps()), command.completionStatus().name(),
                command.completedAt().map(Instant::toString).orElse(""),
                command.safetyFlag().map(Enum::name).orElse(""));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String digestVoid(UUID sessionId, UUID setId) {
        return sha256("VOID_WORKOUT_SET|" + sessionId + "|" + setId + "|USER_REQUESTED");
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public record Command(
            UUID sessionExerciseId,
            long clientOperationSeq,
            WorkoutSet.SetType setType,
            int setOrder,
            WorkoutSet.Performance target,
            WorkoutSet.Performance actual,
            Integer remainingReps,
            WorkoutSet.CompletionStatus completionStatus,
            Optional<Instant> completedAt,
            Optional<WorkoutSet.SafetyFlag> safetyFlag,
            boolean confirmAnomaly) {

        public Command(
                UUID sessionExerciseId,
                long clientOperationSeq,
                WorkoutSet.SetType setType,
                int setOrder,
                WorkoutSet.Performance target,
                WorkoutSet.Performance actual,
                Integer remainingReps,
                WorkoutSet.CompletionStatus completionStatus,
                Optional<Instant> completedAt,
                boolean confirmAnomaly) {
            this(sessionExerciseId, clientOperationSeq, setType, setOrder, target, actual, remainingReps,
                    completionStatus, completedAt, Optional.empty(), confirmAnomaly);
        }

        public Command {
            Objects.requireNonNull(sessionExerciseId, "session exercise id must not be null");
            if (clientOperationSeq < 1 || setOrder < 1) {
                throw new IllegalArgumentException("set sequence and order must be positive");
            }
            Objects.requireNonNull(setType, "set type must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(actual, "actual must not be null");
            Objects.requireNonNull(completionStatus, "completion status must not be null");
            completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
            safetyFlag = Objects.requireNonNull(safetyFlag, "safetyFlag must not be null");
        }

        public Command withAnomalyConfirmation() {
            return new Command(
                    sessionExerciseId, clientOperationSeq, setType, setOrder, target, actual,
                    remainingReps, completionStatus, completedAt, safetyFlag, true);
        }

        public Command withClientOperationSeq(long sequence) {
            return new Command(
                    sessionExerciseId, sequence, setType, setOrder, target, actual,
                    remainingReps, completionStatus, completedAt, safetyFlag, confirmAnomaly);
        }

        public Command withCompletedAt(Optional<Instant> value) {
            return new Command(
                    sessionExerciseId, clientOperationSeq, setType, setOrder, target, actual,
                    remainingReps, completionStatus, value, safetyFlag, confirmAnomaly);
        }
    }

    public record InputPolicy(
            BigDecimal maxWeightKg, int maxReps, BigDecimal largeChangeRatio, BigDecimal largeChangeKg) {
        public InputPolicy {
            if (maxWeightKg.signum() <= 0 || maxReps < 1
                    || largeChangeRatio.compareTo(BigDecimal.ONE) <= 0 || largeChangeKg.signum() <= 0) {
                throw new IllegalArgumentException("workout input policy is invalid");
            }
        }

        public static InputPolicy conservativeDefaults() {
            return new InputPolicy(new BigDecimal("1000"), 500, new BigDecimal("2.0"), new BigDecimal("50"));
        }
    }

    public static final class AnomalyConfirmationRequiredException extends RuntimeException {
        private final List<String> reasons;

        public AnomalyConfirmationRequiredException(List<String> reasons) {
            this.reasons = List.copyOf(reasons);
        }

        public List<String> reasons() {
            return reasons;
        }
    }

    public static final class SessionNotAcceptingSetsException extends RuntimeException {}
}
