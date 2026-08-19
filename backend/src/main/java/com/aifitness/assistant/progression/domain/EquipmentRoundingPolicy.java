package com.aifitness.assistant.progression.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Deterministic KG rounding to concrete levels on one exact equipment item. */
public final class EquipmentRoundingPolicy {
    public static final String INCREASE_RULE = "NEXT_AVAILABLE_LEVEL";
    public static final String REDUCTION_RULE = "FLOOR_TO_AVAILABLE_LEVEL";

    private final String unit;
    private final List<BigDecimal> availableLevels;

    public EquipmentRoundingPolicy(String unit, List<BigDecimal> allowedSteps) {
        if (!"KG".equals(unit)) throw new IllegalArgumentException("P0 equipment rounding only supports KG");
        this.unit = unit;
        if (allowedSteps == null || allowedSteps.stream()
                .anyMatch(step -> step == null || step.signum() <= 0)) {
            throw new IllegalArgumentException("available equipment levels must contain positive values");
        }
        this.availableLevels = allowedSteps.stream().map(BigDecimal::stripTrailingZeros)
                .distinct().sorted(Comparator.naturalOrder()).toList();
    }

    public Optional<BigDecimal> increaseOneStep(BigDecimal currentWeight) {
        BigDecimal current = requireWeight(currentWeight);
        return availableLevels.stream().filter(level -> level.compareTo(current) > 0).findFirst();
    }

    public Optional<BigDecimal> roundReduction(BigDecimal currentWeight, BigDecimal rawWeight) {
        BigDecimal current = requireWeight(currentWeight);
        BigDecimal raw = requireWeight(rawWeight);
        return availableLevels.stream()
                .filter(level -> level.compareTo(raw) <= 0 && level.compareTo(current) < 0)
                .max(Comparator.naturalOrder());
    }

    public String unit() { return unit; }
    public List<BigDecimal> availableLevels() { return availableLevels; }

    /** Kept as an accessor compatibility bridge; values are concrete levels, never increments. */
    public List<BigDecimal> allowedSteps() { return availableLevels; }

    private static BigDecimal requireWeight(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("weight must not be negative");
        return value;
    }
}
