package com.aifitness.assistant.privacy.application;

import java.util.List;
import java.util.UUID;

public interface PrivacyDataPort {

    List<ResourceSummary> summarize(UUID userId);

    record ResourceSummary(Category category, int recordCount) {
        public ResourceSummary {
            if (recordCount < 0) {
                throw new IllegalArgumentException("recordCount must not be negative");
            }
        }
    }

    enum Category {
        PROFILE, EQUIPMENT, PREFERENCES, PLANS, WORKOUTS
    }
}
