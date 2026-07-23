package com.aifitness.assistant.common.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Weight(BigDecimal value, WeightUnit unit, String equipmentProfileId) {

    private static final int MAX_SCALE = 2;

    public Weight {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
        if (value.scale() > MAX_SCALE) {
            throw new IllegalArgumentException("value supports at most two decimal places");
        }
        if (equipmentProfileId != null && equipmentProfileId.isBlank()) {
            throw new IllegalArgumentException("equipmentProfileId must not be blank");
        }
    }

    public static Weight p0Kilograms(BigDecimal value, String equipmentProfileId) {
        return new Weight(value, WeightUnit.KG, equipmentProfileId);
    }
}
