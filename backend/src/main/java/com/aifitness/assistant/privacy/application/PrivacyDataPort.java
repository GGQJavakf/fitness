package com.aifitness.assistant.privacy.application;

import java.util.List;
import java.util.UUID;

public interface PrivacyDataPort {

    List<ResourceExport> export(UUID userId);

    record ResourceExport(Category category, List<ExportRecord> records) {
        public ResourceExport {
            records = List.copyOf(records);
        }

        public int recordCount() { return records.size(); }
    }

    record ExportRecord(String id, String summary) {}

    enum Category {
        PROFILE, EQUIPMENT, PREFERENCES, PLANS, WORKOUTS
    }
}
