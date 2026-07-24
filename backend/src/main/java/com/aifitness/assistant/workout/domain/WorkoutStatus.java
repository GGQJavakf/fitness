package com.aifitness.assistant.workout.domain;

import java.util.EnumSet;
import java.util.Set;

public enum WorkoutStatus {
    CREATED,
    IN_PROGRESS,
    PAUSED,
    COMPLETING,
    COMPLETED,
    ABORTED;

    public boolean canTransitionTo(WorkoutStatus target) {
        return switch (this) {
            case CREATED -> target == IN_PROGRESS || target == ABORTED;
            case IN_PROGRESS -> EnumSet.of(PAUSED, COMPLETING).contains(target);
            case PAUSED -> EnumSet.of(IN_PROGRESS, COMPLETING).contains(target);
            case COMPLETING -> Set.of(COMPLETED, ABORTED).contains(target);
            case COMPLETED, ABORTED -> false;
        };
    }

    public boolean terminal() {
        return this == COMPLETED || this == ABORTED;
    }
}
