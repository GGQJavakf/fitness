package com.aifitness.assistant.analytics.application;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Emits bounded, versioned product facts without allowing identifiers or free text. */
public final class AnalyticsEventService {
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_DURATION_MS = 600_000L;
    private static final int MAX_COUNT = 1_000_000;

    private final EventSink sink;
    private final AtomicLong droppedEvents = new AtomicLong();

    public AnalyticsEventService(EventSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public void emit(EventName name, Facts facts) {
        Event event = new Event(name, SCHEMA_VERSION, facts);
        try {
            sink.accept(event);
        } catch (RuntimeException unavailableCollector) {
            droppedEvents.incrementAndGet();
        }
    }

    public long droppedEventCount() {
        return droppedEvents.get();
    }

    @FunctionalInterface
    public interface EventSink {
        void accept(Event event);
    }

    public record Event(EventName name, int schemaVersion, Facts facts) {
        public Event {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(facts, "facts");
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported analytics schema version");
            }
        }
    }

    public record Facts(Outcome outcome, Integer count, Long durationMs) {
        public Facts {
            Objects.requireNonNull(outcome, "outcome");
            if (count != null && (count < 0 || count > MAX_COUNT)) {
                throw new IllegalArgumentException("analytics count is out of range");
            }
            if (durationMs != null && (durationMs < 0 || durationMs > MAX_DURATION_MS)) {
                throw new IllegalArgumentException("analytics duration is out of range");
            }
        }
    }

    public enum Outcome {
        SUCCESS, FAILURE, DEGRADED, RESUMED, ABORTED, APPLIED, DISMISSED, CONFLICT
    }

    public enum EventName {
        ONBOARDING_STARTED("onboarding_started"),
        ONBOARDING_COMPLETED("onboarding_completed"),
        PLAN_GENERATED("plan_generated"),
        PLAN_GENERATION_FAILED("plan_generation_failed"),
        PLAN_EDITED("plan_edited"),
        PLAN_CONFIRMED("plan_confirmed"),
        WORKOUT_STARTED("workout_started"),
        WORKOUT_SET_COMPLETED("workout_set_completed"),
        WORKOUT_PAUSED("workout_paused"),
        WORKOUT_RESUMED("workout_resumed"),
        WORKOUT_COMPLETED("workout_completed"),
        WORKOUT_ABORTED("workout_aborted"),
        EXERCISE_REPLACED("exercise_replaced"),
        EXERCISE_SKIPPED("exercise_skipped"),
        PROGRESSION_RECOMMENDED("progression_recommended"),
        PROGRESSION_APPLIED("progression_applied"),
        PROGRESSION_DISMISSED("progression_dismissed"),
        AI_SUMMARY_REQUESTED("ai_summary_requested"),
        AI_SUMMARY_VIEWED("ai_summary_viewed"),
        AI_SUMMARY_FAILED("ai_summary_failed"),
        SYNC_FAILED("sync_failed"),
        SYNC_CONFLICT_RESOLVED("sync_conflict_resolved");

        private final String wireName;

        EventName(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
