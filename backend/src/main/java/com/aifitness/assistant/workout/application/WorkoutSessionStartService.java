package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-authoritative start boundary: recovery confirmation is consumed in the session transaction. */
public final class WorkoutSessionStartService {
    private final WorkoutSessionService sessions;
    private final WorkoutSessionRepository repository;
    private final WorkoutSetRepository sets;
    private final WorkoutRecoveryAssessmentQuery recovery;
    private final WorkoutRecoveryConfirmationStore confirmations;
    private final WorkoutSessionStartTransaction transactions;
    private final Optional<WorkoutCompletionService> completion;
    private final Clock clock;
    private final Duration confirmationTtl;

    public WorkoutSessionStartService(
            WorkoutSessionService sessions,
            WorkoutSessionRepository repository,
            WorkoutSetRepository sets,
            WorkoutRecoveryAssessmentQuery recovery,
            WorkoutRecoveryConfirmationStore confirmations,
            WorkoutSessionStartTransaction transactions,
            Clock clock,
            Duration confirmationTtl) {
        this(sessions, repository, sets, recovery, confirmations, transactions, clock,
                confirmationTtl, Optional.empty());
    }

    public WorkoutSessionStartService(
            WorkoutSessionService sessions,
            WorkoutSessionRepository repository,
            WorkoutSetRepository sets,
            WorkoutRecoveryAssessmentQuery recovery,
            WorkoutRecoveryConfirmationStore confirmations,
            WorkoutSessionStartTransaction transactions,
            Clock clock,
            Duration confirmationTtl,
            WorkoutCompletionService completion) {
        this(sessions, repository, sets, recovery, confirmations, transactions, clock,
                confirmationTtl, Optional.of(Objects.requireNonNull(completion, "completion must not be null")));
    }

    private WorkoutSessionStartService(
            WorkoutSessionService sessions,
            WorkoutSessionRepository repository,
            WorkoutSetRepository sets,
            WorkoutRecoveryAssessmentQuery recovery,
            WorkoutRecoveryConfirmationStore confirmations,
            WorkoutSessionStartTransaction transactions,
            Clock clock,
            Duration confirmationTtl,
            Optional<WorkoutCompletionService> completion) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.sets = Objects.requireNonNull(sets, "sets must not be null");
        this.recovery = Objects.requireNonNull(recovery, "recovery must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.completion = Objects.requireNonNull(completion, "completion must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.confirmationTtl = Objects.requireNonNull(confirmationTtl, "confirmation TTL must not be null");
        if (confirmationTtl.isZero() || confirmationTtl.isNegative()
                || confirmationTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("confirmation TTL must be between 1ns and 15 minutes");
        }
    }

    public StartResult start(
            AuthenticatedUserId user,
            WorkoutSessionService.StartCommand command,
            Optional<String> confirmationToken) {
        return start(user, command, confirmationToken, Optional.empty());
    }

    public StartResult start(
            AuthenticatedUserId user,
            WorkoutSessionService.StartCommand command,
            Optional<String> confirmationToken,
            Optional<ActiveWorkoutReplacement> replacement) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Optional<String> token = Objects.requireNonNull(
                confirmationToken, "confirmation token must not be null")
                .filter(value -> !value.isBlank() && value.length() <= 256);
        Optional<ActiveWorkoutReplacement> replacementCommand = Objects.requireNonNull(
                replacement, "replacement must not be null");
        return transactions.execute(() -> startInTransaction(user, command, token, replacementCommand));
    }

    private StartResult startInTransaction(
            AuthenticatedUserId user,
            WorkoutSessionService.StartCommand command,
            Optional<String> confirmationToken,
            Optional<ActiveWorkoutReplacement> replacement) {
        WorkoutSessionRepository.StartState state = repository.findStartStateForUpdate(
                user.value(), command.clientSessionKey());
        Optional<WorkoutSession> existing = state.exactReplay();
        if (existing.isPresent()) {
            WorkoutSession session = existing.get();
            if (!session.hasSameSource(
                    command.planId(), command.planVersionNumber(), command.trainingDayCode())) {
                throw new WorkoutSessionService.IdempotencyConflictException();
            }
            if (state.active().filter(active -> !active.id().equals(session.id())).isPresent()) {
                return activeWorkout(user, state.active().orElseThrow());
            }
            if (!session.status().terminal()) {
                if (replacement.isPresent()) return new Started(session);
                return session.status() == com.aifitness.assistant.workout.domain.WorkoutStatus.CREATED
                                && session.version() == 0
                        ? new Started(session)
                        : activeWorkout(user, session);
            }
            if (state.active().isPresent()) {
                return activeWorkout(user, state.active().get());
            }
            return new TerminalReplay(session);
        }

        Optional<WorkoutSession> active = state.active();
        if (active.isPresent()) {
            if (replacement.isEmpty() || !active.get().id().equals(replacement.get().sessionId())) {
                return activeWorkout(user, active.get());
            }
            if (active.get().version() != replacement.get().expectedVersion()) {
                throw new WorkoutSessionService.VersionConflictException(active.get().version());
            }
        }

        WorkoutRecoveryAssessment assessment = recovery.check(
                user, command.planId(), command.planVersionNumber(), command.trainingDayCode());
        if (assessment.decision() == WorkoutRecoveryAssessment.Decision.READY) {
            return startApproved(user, command, active, replacement);
        }

        String fingerprint = WorkoutRecoveryAssessmentFingerprint.create(assessment);
        WorkoutRecoveryConfirmationStore.Binding binding = new WorkoutRecoveryConfirmationStore.Binding(
                user.value(), command.planId(), command.planVersionNumber(), command.trainingDayCode(),
                command.clientSessionKey(), fingerprint);
        Instant now = clock.instant();
        if (confirmationToken.filter(token -> confirmations.consume(binding, token, now)).isPresent()) {
            return startApproved(user, command, active, replacement);
        }

        Instant expiresAt = now.plus(confirmationTtl);
        String issued = confirmations.issue(binding, now, expiresAt);
        return new ConfirmationRequired(assessment, issued, expiresAt);
    }

    private Started startApproved(
            AuthenticatedUserId user,
            WorkoutSessionService.StartCommand command,
            Optional<WorkoutSession> active,
            Optional<ActiveWorkoutReplacement> replacement) {
        if (replacement.isPresent() && active.isPresent()) {
            ActiveWorkoutReplacement expected = replacement.get();
            completion.orElseThrow(() -> new IllegalStateException("active workout replacement is unavailable"))
                    .complete(user, expected.sessionId(), expected.expectedVersion(),
                            WorkoutCompletionService.CompletionType.EARLY_END);
        }
        WorkoutSession started = sessions.start(user, command);
        if (replacement.isEmpty()) return new Started(started);
        WorkoutSession inProgress = started.status() == WorkoutStatus.IN_PROGRESS
                ? started
                : sessions.transition(user, started.id(), WorkoutStatus.IN_PROGRESS, started.version());
        return new Started(inProgress);
    }

    private ActiveWorkoutExists activeWorkout(AuthenticatedUserId user, WorkoutSession session) {
        return new ActiveWorkoutExists(
                session,
                sets.findBySession(user.value(), session.id()).stream()
                        .filter(set -> set.completionStatus() != WorkoutSet.CompletionStatus.PLANNED)
                        .toList());
    }

    public sealed interface StartResult permits Started, ConfirmationRequired, ActiveWorkoutExists, TerminalReplay {}

    public record Started(WorkoutSession session) implements StartResult {
        public Started {
            Objects.requireNonNull(session, "session must not be null");
        }
    }

    public record ActiveWorkoutReplacement(UUID sessionId, long expectedVersion) {
        public ActiveWorkoutReplacement {
            Objects.requireNonNull(sessionId, "active workout session id must not be null");
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("active workout expected version must not be negative");
            }
        }
    }

    public record ActiveWorkoutExists(WorkoutSession session, List<WorkoutSet> sets) implements StartResult {
        public ActiveWorkoutExists {
            Objects.requireNonNull(session, "active session must not be null");
            sets = List.copyOf(Objects.requireNonNull(sets, "active workout sets must not be null"));
            if (session.status().terminal()) {
                throw new IllegalArgumentException("active workout must not be terminal");
            }
        }
    }

    public record TerminalReplay(WorkoutSession session) implements StartResult {
        public TerminalReplay {
            Objects.requireNonNull(session, "terminal session must not be null");
            if (!session.status().terminal()) {
                throw new IllegalArgumentException("terminal replay must reference a terminal workout");
            }
        }
    }

    public record ConfirmationRequired(
            WorkoutRecoveryAssessment assessment,
            String confirmationToken,
            Instant confirmationExpiresAt) implements StartResult {
        public ConfirmationRequired {
            Objects.requireNonNull(assessment, "assessment must not be null");
            if (assessment.decision() != WorkoutRecoveryAssessment.Decision.CONFIRMATION_REQUIRED) {
                throw new IllegalArgumentException("confirmation requires a recovery warning");
            }
            if (confirmationToken == null || confirmationToken.isBlank()) {
                throw new IllegalArgumentException("confirmation token must not be blank");
            }
            Objects.requireNonNull(confirmationExpiresAt, "confirmation expiry must not be null");
        }
    }
}
