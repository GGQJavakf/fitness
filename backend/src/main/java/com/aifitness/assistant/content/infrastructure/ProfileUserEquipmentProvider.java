package com.aifitness.assistant.content.infrastructure;

import com.aifitness.assistant.content.application.UserEquipmentProvider;
import com.aifitness.assistant.profile.application.ProfileRepository;
import com.aifitness.assistant.profile.domain.EquipmentProfile;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ProfileUserEquipmentProvider implements UserEquipmentProvider {

    private final ProfileRepository profiles;

    public ProfileUserEquipmentProvider(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Override
    public Set<String> availableEquipment(UUID userId) {
        return profiles.findEquipment(userId).map(EquipmentProfile::items).orElseGet(java.util.List::of).stream()
                .map(EquipmentProfile.Item::equipmentType)
                .collect(Collectors.toUnmodifiableSet());
    }
}
