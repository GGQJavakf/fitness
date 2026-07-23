package com.aifitness.assistant.content.application;

import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface UserEquipmentProvider {

    Set<String> availableEquipment(UUID userId);
}
