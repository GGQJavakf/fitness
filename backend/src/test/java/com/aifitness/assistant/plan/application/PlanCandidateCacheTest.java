package com.aifitness.assistant.plan.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.domain.PlanDraft;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlanCandidateCacheTest {

    private static final AuthenticatedUserId USER = new AuthenticatedUserId(
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000101"));

    @Test
    void removesExpiredEntriesDuringReadMaintenance() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T08:00:00Z"));
        PlanCandidateCache cache = new PlanCandidateCache(clock, 3);
        cache.save(USER, candidate("00000000-0000-0000-0000-000000000201",
                clock.instant().plusSeconds(60)));

        clock.advanceSeconds(61);

        assertThat(cache.find(USER, "00000000-0000-0000-0000-000000000201")).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void evictsTheEarliestExpiryAndNeverExceedsCapacity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T08:00:00Z"));
        PlanCandidateCache cache = new PlanCandidateCache(clock, 2);
        cache.save(USER, candidate("00000000-0000-0000-0000-000000000201",
                clock.instant().plusSeconds(60)));
        cache.save(USER, candidate("00000000-0000-0000-0000-000000000202",
                clock.instant().plusSeconds(180)));

        cache.save(USER, candidate("00000000-0000-0000-0000-000000000203",
                clock.instant().plusSeconds(120)));

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.find(USER, "00000000-0000-0000-0000-000000000201")).isEmpty();
        assertThat(cache.find(USER, "00000000-0000-0000-0000-000000000203")).isPresent();
        assertThat(cache.find(USER, "00000000-0000-0000-0000-000000000202")).isPresent();
    }

    @Test
    void remainsWithinTheHardLimitDuringConcurrentWrites() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T08:00:00Z"));
        PlanCandidateCache cache = new PlanCandidateCache(clock, 16);

        IntStream.range(0, 128).parallel().forEach(index -> {
            String id = new java.util.UUID(0L, index + 1L).toString();
            cache.save(USER, candidate(id, clock.instant().plusSeconds(60 + index)));
        });

        assertThat(cache.size()).isEqualTo(16);
    }

    @Test
    void isolatesTheSameCandidateIdByAuthenticatedUser() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T08:00:00Z"));
        PlanCandidateCache cache = new PlanCandidateCache(clock, 3);
        String candidateId = "00000000-0000-0000-0000-000000000201";
        AuthenticatedUserId otherUser = new AuthenticatedUserId(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000102"));

        cache.save(USER, candidate(candidateId, clock.instant().plusSeconds(60)));

        assertThat(cache.find(USER, candidateId)).isPresent();
        assertThat(cache.find(otherUser, candidateId)).isEmpty();
    }

    private static PlanCandidateService.CandidateEnvelope candidate(String id, Instant expiresAt) {
        return new PlanCandidateService.CandidateEnvelope(
                id,
                PlanCandidateService.GenerationSource.FALLBACK_RULE_PLAN,
                new PlanDraft(
                        "TEST",
                        "Test",
                        List.of(new PlanDraft.Day(
                                "DAY_1",
                                "Day 1",
                                List.of(new PlanDraft.Exercise(
                                        "SQUAT",
                                        3,
                                        8,
                                        12,
                                        90,
                                        PlanDraft.WeightStatus.NEEDS_CALIBRATION)))),
                        Map.of()),
                new RuleReference("rules", "templates", "exercises"),
                PlanCandidateService.ExplanationStatus.DEGRADED,
                "fallback",
                expiresAt);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
