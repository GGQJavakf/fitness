package com.aifitness.assistant.profile.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record EquipmentProfile(UUID userId, List<Item> items, long version) {

    public EquipmentProfile {
        Objects.requireNonNull(userId, "userId must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (items.size() > 100) {
            throw new IllegalArgumentException("too many equipment items");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        Set<UUID> clientEquipmentKeys = new HashSet<>();
        if (items.stream().anyMatch(item -> !clientEquipmentKeys.add(item.clientEquipmentKey()))) {
            throw new IllegalArgumentException("clientEquipmentKey must be unique");
        }
    }

    public record Item(
            UUID clientEquipmentKey,
            String equipmentType,
            BigDecimal minIncrement,
            String unit,
            List<BigDecimal> availableLevels) {

        private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

        public Item {
            Objects.requireNonNull(clientEquipmentKey, "clientEquipmentKey must not be null");
            if (equipmentType == null || !TYPE.matcher(equipmentType).matches()) {
                throw new IllegalArgumentException("equipmentType is invalid");
            }
            Objects.requireNonNull(minIncrement, "minIncrement must not be null");
            if (minIncrement.signum() <= 0 || minIncrement.scale() > 2) {
                throw new IllegalArgumentException("minIncrement must be a positive KG value");
            }
            if (!"KG".equals(unit)) {
                throw new IllegalArgumentException("P0 supports KG only");
            }
            availableLevels = List.copyOf(
                    Objects.requireNonNull(availableLevels, "availableLevels must not be null"));
            if (availableLevels.isEmpty()) {
                throw new IllegalArgumentException("availableLevels must not be empty");
            }
            if (availableLevels.size() > 500) {
                throw new IllegalArgumentException("too many available levels");
            }
            BigDecimal previous = null;
            for (BigDecimal level : availableLevels) {
                Objects.requireNonNull(level, "available level must not be null");
                if (level.signum() <= 0 || level.scale() > 2 || level.remainder(minIncrement).signum() != 0) {
                    throw new IllegalArgumentException("available level must align with minIncrement");
                }
                if (previous != null && level.compareTo(previous) <= 0) {
                    throw new IllegalArgumentException("availableLevels must be strictly increasing");
                }
                previous = level;
            }
        }
    }
}
