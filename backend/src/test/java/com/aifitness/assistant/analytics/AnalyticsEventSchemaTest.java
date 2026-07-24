package com.aifitness.assistant.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.analytics.application.AnalyticsEventService;
import com.aifitness.assistant.analytics.application.AnalyticsEventService.Event;
import com.aifitness.assistant.analytics.application.AnalyticsEventService.EventName;
import com.aifitness.assistant.analytics.application.AnalyticsEventService.Facts;
import com.aifitness.assistant.analytics.application.AnalyticsEventService.Outcome;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyticsEventSchemaTest {

    @Test
    void coversEveryPrdEventWithoutFreeTextFields() {
        assertThat(Arrays.stream(EventName.values()).map(EventName::wireName)).containsExactly(
                "onboarding_started", "onboarding_completed",
                "plan_generated", "plan_generation_failed", "plan_edited", "plan_confirmed",
                "workout_started", "workout_set_completed", "workout_paused", "workout_resumed",
                "workout_completed", "workout_aborted", "exercise_replaced", "exercise_skipped",
                "progression_recommended", "progression_applied", "progression_dismissed",
                "ai_summary_requested", "ai_summary_viewed", "ai_summary_failed",
                "sync_failed", "sync_conflict_resolved");
        assertThat(Event.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("name", "schemaVersion", "facts");
        assertThat(Facts.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("outcome", "count", "durationMs");
    }

    @Test
    void validatesBoundedFactsAndEmitsDetachedEvents() {
        List<Event> emitted = new ArrayList<>();
        AnalyticsEventService service = new AnalyticsEventService(emitted::add);

        service.emit(EventName.WORKOUT_SET_COMPLETED, new Facts(Outcome.SUCCESS, 1, 12L));

        assertThat(emitted).containsExactly(new Event(
                EventName.WORKOUT_SET_COMPLETED, 1, new Facts(Outcome.SUCCESS, 1, 12L)));
        assertThatThrownBy(() -> new Facts(Outcome.SUCCESS, -1, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Facts(Outcome.SUCCESS, 1, 600_001L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void telemetryFailureNeverBlocksTheBusinessBoundary() {
        AnalyticsEventService service = new AnalyticsEventService(event -> {
            throw new IllegalStateException("collector unavailable");
        });

        assertThatCode(() -> service.emit(
                EventName.SYNC_FAILED, new Facts(Outcome.FAILURE, 1, 20L)))
                .doesNotThrowAnyException();
        assertThat(service.droppedEventCount()).isEqualTo(1);
    }
}
