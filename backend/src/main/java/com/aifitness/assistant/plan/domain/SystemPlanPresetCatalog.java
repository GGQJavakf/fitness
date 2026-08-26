package com.aifitness.assistant.plan.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Versioned, selectable plans whose prescriptions are owned by reviewed system content. */
public record SystemPlanPresetCatalog(List<Preset> presets) {

    public SystemPlanPresetCatalog {
        presets = List.copyOf(Objects.requireNonNull(presets, "presets must not be null"));
        long distinctCodes = presets.stream().map(Preset::code).distinct().count();
        if (distinctCodes != presets.size()) {
            throw new IllegalArgumentException("preset codes must be unique");
        }
    }

    public static SystemPlanPresetCatalog empty() {
        return new SystemPlanPresetCatalog(List.of());
    }

    public Optional<Preset> find(String code) {
        return presets.stream().filter(preset -> preset.code().equals(code)).findFirst();
    }

    public record Preset(
            String code,
            String version,
            String name,
            String goal,
            int weeklyFrequency,
            int sessionMinutes,
            String location,
            PlanDraft plan) {
        public Preset {
            if (code == null || code.isBlank() || version == null || version.isBlank()
                    || name == null || name.isBlank() || goal == null || goal.isBlank()
                    || location == null || location.isBlank()) {
                throw new IllegalArgumentException("preset identity is required");
            }
            if (weeklyFrequency < 2 || weeklyFrequency > 6 || sessionMinutes < 1) {
                throw new IllegalArgumentException("preset schedule is invalid");
            }
            Objects.requireNonNull(plan, "preset plan must not be null");
            if (plan.days().size() != weeklyFrequency
                    || !code.equals(plan.presetCode())
                    || !version.equals(plan.presetVersion())) {
                throw new IllegalArgumentException("preset plan identity does not match catalog metadata");
            }
        }
    }
}
