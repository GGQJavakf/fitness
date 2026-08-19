package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutCompletionOutboxProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class WorkoutCompletionOutboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(WorkoutCompletionOutboxWorker.class);
    private static final int MAX_EVENTS_PER_TICK = 20;
    private final WorkoutCompletionOutboxProcessor processor;

    public WorkoutCompletionOutboxWorker(WorkoutCompletionOutboxProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${fitness.workout.outbox.poll-delay-ms:1000}")
    public void drain() {
        for (int processed = 0; processed < MAX_EVENTS_PER_TICK; processed++) {
            try {
                if (!processor.processNext()) return;
            } catch (RuntimeException exception) {
                LOG.warn("Workout completion outbox delivery failed: {}", exception.getClass().getSimpleName());
                return;
            }
        }
    }
}
