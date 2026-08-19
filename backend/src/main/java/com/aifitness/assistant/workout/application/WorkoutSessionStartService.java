package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import com.aifitness.assistant.workout.domain.WorkoutSet;

/** Server-authoritative start boundary: recovery confirmation is consumed in the session transaction. */
public final class WorkoutSessionStartService {
    private final WorkoutSessionService sessions;
    private final WorkoutSessionRepository repository;
    private final WorkoutSetRepository sets;
    private final WorkoutRecoveryAssessmentQuery recovery;
    private final WorkoutRecoveryConfirmationStore confirmations;
    private final WorkoutSessionStartTransaction transactions;
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
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.sets = Objects.requireNonNull(sets, "sets must not be null");
        this.recovery = Objects.requireNonNull(recovery, "recovery must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
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
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Optional<String> token = Objects.requireNonNull(
                        confirmationToken, "confirmation token must not be null")
                .filter(value -> !value.isBlank() && value.length() <= 256);
        return transactions.execute(() -> startInTransaction(user, command, token));
    }

    private StartResult startInTransaction(
            AuthenticatedUserId user,
            WorkoutSessionService.StartCommand command,
            Optional<String> confirmationToken) {
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
            return activeWorkout(user, active.get());
        }

        WorkoutRecoveryAssessment assessment = recovery.check(
                user, command.planId(), command.planVersionNumber(), command.trainingDayCode());
        if (assessment.decision() == WorkoutRecoveryAssessment.Decision.READY) {
            return new Started(sessions.start(user, command));
        }

        String fingerprint = WorkoutRecoveryAssessmentFingerprint.create(assessment);
        WorkoutRecoveryConfirmationStore.Binding binding = new WorkoutRecoveryConfirmationStore.Binding(
                user.value(), command.planId(), command.planVersionNumber(), command.trainingDayCode(),
                command.clientSessionKey(), fingerprint);
        Instant now = clock.instant();
        if (confirmationToken.filter(token -> confirmations.consume(binding, token, now)).isPresent()) {
            return new Started(sessions.start(user, command));
        }

        Instant expiresAt = now.plus(confirmationTtl);
        String issued = confirmations.issue(binding, now, expiresAt);
        return new ConfirmationRequired(assessment, issued, expiresAt);
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
