package com.aifitness.assistant.progression.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/** Deterministic KG rounding based only on the user's configured equipment increments. */
public final class EquipmentRoundingPolicy {
    public static final String INCREASE_RULE = "ADD_ONE_MIN_INCREMENT";
    public static final String REDUCTION_RULE = "FLOOR_TO_MIN_INCREMENT";

    private final String unit;
    private final List<BigDecimal> allowedSteps;
    private final BigDecimal minimumIncrement;

    public EquipmentRoundingPolicy(String unit, List<BigDecimal> allowedSteps) {
        if (!"KG".equals(unit)) throw new IllegalArgumentException("P0 equipment rounding only supports KG");
        this.unit = unit;
        if (allowedSteps == null || allowedSteps.isEmpty() || allowedSteps.stream()
                .anyMatch(step -> step == null || step.signum() <= 0)) {
            throw new IllegalArgumentException("equipment steps must contain positive values");
        }
        this.allowedSteps = allowedSteps.stream().map(BigDecimal::stripTrailingZeros)
                .sorted(Comparator.naturalOrder()).toList();
        this.minimumIncrement = this.allowedSteps.getFirst();
    }

    public BigDecimal increaseOneStep(BigDecimal currentWeight) {
        return requireWeight(currentWeight).add(minimumIncrement).stripTrailingZeros();
    }

    public BigDecimal roundReduction(BigDecimal currentWeight, BigDecimal rawWeight) {
        BigDecimal current = requireWeight(currentWeight);
        BigDecimal raw = requireWeight(rawWeight);
        BigDecimal rounded = raw.divide(minimumIncrement, 0, RoundingMode.FLOOR).multiply(minimumIncrement);
        if (current.signum() > 0 && rounded.compareTo(current) >= 0) rounded = current.subtract(minimumIncrement);
        if (rounded.signum() < 0) rounded = BigDecimal.ZERO;
        return rounded.stripTrailingZeros();
    }

    public String unit() { return unit; }
    public List<BigDecimal> allowedSteps() { return allowedSteps; }
    public BigDecimal minimumIncrement() { return minimumIncrement; }

    private static BigDecimal requireWeight(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("weight must not be negative");
        return value;
    }
}
