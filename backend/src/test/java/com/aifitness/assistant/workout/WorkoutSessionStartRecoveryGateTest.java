package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.workout.application.WorkoutRecoveryAssessmentQuery;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSessionStartService;
import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutRecoveryConfirmationStore;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionStartTransaction;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSetRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkoutSessionStartRecoveryGateTest {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000903");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000904");
    private static final UUID DAY_ID = UUID.fromString("00000000-0000-0000-0000-000000000905");
    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");

    @Test
    void warningIssuesSingleUseChallengeAndDoesNotCreateUntilExplicitConfirmation() {
        Harness harness = new Harness(required(NOW.minus(Duration.ofHours(18))));
        WorkoutSessionService.StartCommand command = command("recovery-client-001");

        WorkoutSessionStartService.StartResult first = harness.start(USER_ID, command, Optional.empty());

        assertThat(first).isInstanceOf(WorkoutSessionStartService.ConfirmationRequired.class);
        WorkoutSessionStartService.ConfirmationRequired warning =
                (WorkoutSessionStartService.ConfirmationRequired) first;
        assertThat(warning.assessment().affectedMuscles())
                .extracting(WorkoutRecoveryAssessment.AffectedMuscle::muscleGroup)
                .containsExactly("CHEST");
        assertThat(harness.sessions.findByUserAndClientKey(USER_ID, command.clientSessionKey())).isEmpty();

        WorkoutSessionStartService.StartResult confirmed = harness.start(
                USER_ID, command, Optional.of(warning.confirmationToken()));
        assertThat(confirmed).isInstanceOf(WorkoutSessionStartService.Started.class);
        assertThat(harness.sessions.findByUserAndClientKey(USER_ID, command.clientSessionKey())).isPresent();

        harness.recovery.set(required(NOW.minus(Duration.ofHours(1))));
        WorkoutSessionStartService.StartResult replay = harness.start(
                USER_ID, command, Optional.of(warning.confirmationToken()));
        assertThat(replay).isInstanceOf(WorkoutSessionStartService.Started.class);
        assertThat(((WorkoutSessionStartService.Started) replay).session().id())
                .isEqualTo(((WorkoutSessionStartService.Started) confirmed).session().id());
        assertThat(harness.planLoads).hasValue(1);
    }

    @Test
    void tokenRejectsCrossUserTamperExpiryAndChangedRecoveryFactsWithoutCreatingSessions() {
        Harness harness = new Harness(required(NOW.minus(Duration.ofHours(18))));
        WorkoutSessionService.StartCommand original = command("recovery-client-002");
        WorkoutSessionStartService.ConfirmationRequired warning = (WorkoutSessionStartService.ConfirmationRequired)
                harness.start(USER_ID, original, Optional.empty());

        WorkoutSessionStartService.StartResult crossUser = harness.start(
                OTHER_USER_ID, original, Optional.of(warning.confirmationToken()));
        assertThat(crossUser).isInstanceOf(WorkoutSessionStartService.ConfirmationRequired.class);
        assertThat(harness.sessions.findByUserAndClientKey(OTHER_USER_ID, original.clientSessionKey())).isEmpty();

        WorkoutSessionService.StartCommand changedKey = command("recovery-client-003");
        WorkoutSessionStartService.StartResult changedBinding = harness.start(
                USER_ID, changedKey, Optional.of(warning.confirmationToken()));
        assertThat(changedBinding).isInstanceOf(WorkoutSessionStartService.ConfirmationRequired.class);
        assertThat(harness.sessions.findByUserAndClientKey(USER_ID, changedKey.clientSessionKey())).isEmpty();

        WorkoutSessionStartService.StartResult tampered = harness.start(
                USER_ID, original, Optional.of(warning.confirmationToken() + "x"));
        assertThat(tampered).isInstanceOf(WorkoutSessionStartService.ConfirmationRequired.class);
        assertThat(harness.sessions.findByUserAndClientKey(USER_ID, original.clientSessionKey())).isEmpty();

        harness.recovery.set(required(NOW.minus(Duration.ofHours(17))));
        WorkoutSessionStartService.StartResult factsChanged = harness.start(
                USER_ID, original, Optional.of(warning.confirmationToken()));
        assertThat(factsChanged).isInstanceOf(WorkoutSessionStartService.ConfirmationRequired.class);
        assertThat(harness.sessions.findByUserAndClientKey(USER_ID, original.clientSessionKey())).isEmpty();

        WorkoutSessionStartService.ConfirmationRequired fresh = (WorkoutSessionStartService.ConfirmationRequired)
                harness.start(USER_ID, original, Optional.empty());
        harness.clock.advance(Duration.ofMinutes(6));
        WorkoutSessionStartService.StartResult expired = harness.start(
                USER_ID, original, Optional.of(fresh.confirmationToken()));
        assertThat(expired).isInstanceOf(WorkoutSessionStartService.ConfirmationRequired.class);
        assertThat(harness.sessions.findByUserAndClientKey(USER_ID, original.clientSessionKey())).isEmpty();
    }

    @Test
    void concurrentConfirmationCreatesAtMostOneSession() throws Exception {
        Harness harness = new Harness(required(NOW.minus(Duration.ofHours(18))));
        WorkoutSessionService.StartCommand command = command("recovery-client-004");
        WorkoutSessionStartService.ConfirmationRequired warning = (WorkoutSessionStartService.ConfirmationRequired)
                harness.start(USER_ID, command, Optional.empty());
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> executor.submit(() -> harness.start(
                            USER_ID, command, Optional.of(warning.confirmationToken()))))
                    .toList();
            Set<UUID> createdIds = new java.util.HashSet<>();
            for (var future : futures) {
                WorkoutSessionStartService.StartResult result = future.get(5, TimeUnit.SECONDS);
                assertThat(result).isInstanceOf(WorkoutSessionStartService.Started.class);
                createdIds.add(((WorkoutSessionStartService.Started) result).session().id());
            }
            assertThat(createdIds).hasSize(1);
            assertThat(harness.planLoads).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void confirmationBindsTheExactClientSessionKeyWithoutWhitespaceCanonicalization() {
        Harness harness = new Harness(required(NOW.minus(Duration.ofHours(18))));
        WorkoutSessionService.StartCommand issuedFor = command("recovery-client-005 ");
        WorkoutSessionStartService.ConfirmationRequired warning = (WorkoutSessionStartService.ConfirmationRequired)
                harness.start(USER_ID, issuedFor, Optional.empty());
        WorkoutSessionService.StartCommand whitespaceVariant = command(" recovery-client-005");

        WorkoutSessionStartService.StartResult result = harness.start(
                USER_ID, whitespaceVariant, Optional.of(warning.confirmationToken()));

        assertThat(result).isInstanceOf(WorkoutSessionStartService.ConfirmationRequired.class);
        assertThat(harness.sessions.findByUserAndClientKey(USER_ID, whitespaceVariant.clientSessionKey())).isEmpty();
    }

    @Test
    void aDifferentClientKeyRecoversTheExistingNonTerminalSessionInsteadOfCreatingAnotherOne() {
        Harness harness = new Harness(ready());
        WorkoutSessionStartService.Started first = (WorkoutSessionStartService.Started)
                harness.start(USER_ID, command("single-active-client-001"), Optional.empty());

        WorkoutSessionStartService.StartResult repeated = harness.start(
                USER_ID, command("single-active-client-002"), Optional.empty());

        assertThat(repeated).isInstanceOf(WorkoutSessionStartService.ActiveWorkoutExists.class);
        assertThat(((WorkoutSessionStartService.ActiveWorkoutExists) repeated).session().id())
                .isEqualTo(first.session().id());
        assertThat(harness.sessions.findByUserAndClientKey(USER_ID, "single-active-client-002")).isEmpty();
        assertThat(harness.planLoads).hasValue(1);
    }

    @Test
    void concurrentDifferentClientKeysStillProduceOnlyOneNonTerminalSession() throws Exception {
        Harness harness = new Harness(ready());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(
                    executor.submit(() -> harness.start(
                            USER_ID, command("single-active-race-client-001"), Optional.empty())),
                    executor.submit(() -> harness.start(
                            USER_ID, command("single-active-race-client-002"), Optional.empty())));
            List<WorkoutSessionStartService.StartResult> results = futures.stream().map(future -> {
                try {
                    return future.get(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(results).filteredOn(WorkoutSessionStartService.Started.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(WorkoutSessionStartService.ActiveWorkoutExists.class::isInstance)
                    .hasSize(1);
            assertThat(results.stream().map(result -> result instanceof WorkoutSessionStartService.Started started
                    ? started.session().id()
                    : ((WorkoutSessionStartService.ActiveWorkoutExists) result).session().id()).toList())
                    .containsOnly(results.stream()
                            .filter(WorkoutSessionStartService.Started.class::isInstance)
                            .map(WorkoutSessionStartService.Started.class::cast)
                            .findFirst().orElseThrow().session().id());
            assertThat(harness.planLoads).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exactReplayOfAnInProgressSessionReturnsOnlyEffectiveRecordedFacts() {
        Harness harness = new Harness(ready());
        WorkoutSessionStartService.Started created = (WorkoutSessionStartService.Started)
                harness.start(USER_ID, command("exact-active-client-001"), Optional.empty());
        var active = harness.sessionsService.transition(
                new AuthenticatedUserId(USER_ID), created.session().id(), WorkoutStatus.IN_PROGRESS, 0);
        WorkoutSet kept = completedSet(active, 1, "exact-active-set-0001");
        long version = harness.sets.save(USER_ID, kept, active.version()).sessionVersion();
        WorkoutSet voided = completedSet(
                harness.sessions.findByIdAndUser(active.id(), USER_ID).orElseThrow(),
                2,
                "exact-active-set-0002");
        version = harness.sets.save(USER_ID, voided, version).sessionVersion();
        harness.sets.appendVoid(
                USER_ID, active.id(), voided.id(), "exact-active-void-0002", "b".repeat(64),
                version, UUID.fromString("00000000-0000-0000-0000-000000000999"), NOW);

        WorkoutSessionStartService.StartResult replay = harness.start(
                USER_ID, command("exact-active-client-001"), Optional.empty());

        assertThat(replay).isInstanceOf(WorkoutSessionStartService.ActiveWorkoutExists.class);
        assertThat(((WorkoutSessionStartService.ActiveWorkoutExists) replay).sets())
                .extracting(WorkoutSet::clientSetKey)
                .containsExactly("exact-active-set-0001");
    }

    @Test
    void exactReplayOfAPausedSessionUsesTheRecoverableActiveResult() {
        Harness harness = new Harness(ready());
        WorkoutSessionStartService.Started created = (WorkoutSessionStartService.Started)
                harness.start(USER_ID, command("exact-paused-client-001"), Optional.empty());
        var active = harness.sessionsService.transition(
                new AuthenticatedUserId(USER_ID), created.session().id(), WorkoutStatus.IN_PROGRESS, 0);
        harness.sessionsService.transition(
                new AuthenticatedUserId(USER_ID), active.id(), WorkoutStatus.PAUSED, active.version());

        WorkoutSessionStartService.StartResult replay = harness.start(
                USER_ID, command("exact-paused-client-001"), Optional.empty());

        assertThat(replay).isInstanceOf(WorkoutSessionStartService.ActiveWorkoutExists.class);
        assertThat(((WorkoutSessionStartService.ActiveWorkoutExists) replay).session().status())
                .isEqualTo(WorkoutStatus.PAUSED);
    }

    @Test
    void terminalExactReplayRevealsAnotherActiveSessionOrReturnsAnExplicitTerminalResult() {
        Harness harness = new Harness(ready());
        WorkoutSessionStartService.Started old = (WorkoutSessionStartService.Started)
                harness.start(USER_ID, command("terminal-replay-client-001"), Optional.empty());
        harness.sessionsService.transition(
                new AuthenticatedUserId(USER_ID), old.session().id(), WorkoutStatus.ABORTED, 0);

        WorkoutSessionStartService.StartResult terminalOnly = harness.start(
                USER_ID, command("terminal-replay-client-001"), Optional.empty());
        assertThat(terminalOnly).isInstanceOf(WorkoutSessionStartService.TerminalReplay.class);

        WorkoutSessionStartService.Started current = (WorkoutSessionStartService.Started)
                harness.start(USER_ID, command("terminal-replay-client-002"), Optional.empty());
        WorkoutSessionStartService.StartResult replay = harness.start(
                USER_ID, command("terminal-replay-client-001"), Optional.empty());

        assertThat(replay).isInstanceOf(WorkoutSessionStartService.ActiveWorkoutExists.class);
        assertThat(((WorkoutSessionStartService.ActiveWorkoutExists) replay).session().id())
                .isEqualTo(current.session().id());
    }

    @Test
    void legacyDuplicateActiveSessionTakesPriorityOverAPristineExactReplay() {
        Harness harness = new Harness(ready());
        var older = harness.sessionsService.start(
                new AuthenticatedUserId(USER_ID), command("legacy-active-client-001"));
        older = harness.sessionsService.transition(
                new AuthenticatedUserId(USER_ID), older.id(), WorkoutStatus.IN_PROGRESS, older.version());
        var pristine = harness.sessionsService.start(
                new AuthenticatedUserId(USER_ID), command("legacy-pristine-client-002"));

        WorkoutSessionStartService.StartResult replay = harness.start(
                USER_ID, command("legacy-pristine-client-002"), Optional.empty());

        assertThat(pristine.status()).isEqualTo(WorkoutStatus.CREATED);
        assertThat(replay).isInstanceOf(WorkoutSessionStartService.ActiveWorkoutExists.class);
        assertThat(((WorkoutSessionStartService.ActiveWorkoutExists) replay).session().id())
                .isEqualTo(older.id());
    }

    private static WorkoutSet completedSet(
            com.aifitness.assistant.workout.domain.WorkoutSession session,
            int sequence,
            String clientSetKey) {
        return new WorkoutSet(
                new UUID(1, sequence), session.id(), session.exercises().get(0).id(), clientSetKey,
                sequence, WorkoutSet.SetType.WORK, sequence,
                new WorkoutSet.Performance(BigDecimal.valueOf(25), "KG", 10),
                new WorkoutSet.Performance(BigDecimal.valueOf(25), "KG", 8),
                2, WorkoutSet.CompletionStatus.COMPLETED, Optional.of(NOW), 0,
                Optional.empty(), Optional.empty(), "a".repeat(64));
    }

    private static WorkoutSessionService.StartCommand command(String key) {
        return new WorkoutSessionService.StartCommand(key, PLAN_ID, 1, "DAY_1");
    }

    private static WorkoutRecoveryAssessment required(Instant completedAt) {
        return WorkoutRecoveryAssessment.evaluate(
                "1.3.0", 48, NOW, Set.of("CHEST"),
                List.of(new WorkoutRecoveryAssessment.CompletedMuscleFact(completedAt, Set.of("CHEST"))));
    }

    private static WorkoutRecoveryAssessment ready() {
        return WorkoutRecoveryAssessment.evaluate("1.3.0", 48, NOW, Set.of("CHEST"), List.of());
    }

    private static final class Harness {
        private final MutableClock clock = new MutableClock(NOW);
        private final InMemoryWorkoutSessionRepository sessions = new InMemoryWorkoutSessionRepository();
        private final InMemoryWorkoutSetRepository sets = new InMemoryWorkoutSetRepository(sessions);
        private final AtomicReference<WorkoutRecoveryAssessment> recovery;
        private final AtomicInteger planLoads = new AtomicInteger();
        private final WorkoutSessionStartService starts;
        private final WorkoutSessionService sessionsService;

        private Harness(WorkoutRecoveryAssessment initial) {
            recovery = new AtomicReference<>(initial);
            PlanWorkoutSnapshotQuery plans = (userId, planId, versionNo, dayCode) -> {
                planLoads.incrementAndGet();
                return new PlanWorkoutSnapshotQuery.PlanDaySource(
                        PLAN_ID, VERSION_ID, 1, DAY_ID, "DAY_1",
                        List.of(new PlanWorkoutSnapshotQuery.ExerciseSource(
                                UUID.fromString("00000000-0000-0000-0000-000000000906"),
                                1, "BENCH_PRESS", "杠铃卧推", "content-v1", Set.of("BARBELL"),
                                3, 8, 12, 90, "NEEDS_CALIBRATION", "KG")));
            };
            AtomicInteger ids = new AtomicInteger(1000);
            sessionsService = new WorkoutSessionService(
                    sessions, plans, clock, () -> new UUID(0, ids.getAndIncrement()));
            WorkoutRecoveryAssessmentQuery recoveryQuery = (user, planId, versionNo, dayCode) -> recovery.get();
            starts = new WorkoutSessionStartService(
                    sessionsService,
                    sessions,
                    sets,
                    recoveryQuery,
                    new InMemoryWorkoutRecoveryConfirmationStore(),
                    new InMemoryWorkoutSessionStartTransaction(),
                    clock,
                    Duration.ofMinutes(5));
        }

        private WorkoutSessionStartService.StartResult start(
                UUID userId, WorkoutSessionService.StartCommand command, Optional<String> token) {
            return starts.start(new AuthenticatedUserId(userId), command, token);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return instant.get();
        }
    }
}
