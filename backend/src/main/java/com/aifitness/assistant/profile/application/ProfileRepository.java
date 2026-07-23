package com.aifitness.assistant.profile.application;

import com.aifitness.assistant.profile.domain.EquipmentProfile;
import com.aifitness.assistant.profile.domain.PreferenceProfile;
import com.aifitness.assistant.profile.domain.UserProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository {

    Optional<UserProfile> findProfile(UUID userId);

    UserProfile replaceProfile(UUID userId, long expectedVersion, UserProfile.Details details);

    Optional<EquipmentProfile> findEquipment(UUID userId);

    EquipmentProfile replaceEquipment(UUID userId, long expectedVersion, List<EquipmentProfile.Item> items);

    Optional<PreferenceProfile> findPreferences(UUID userId);

    PreferenceProfile replacePreferences(
            UUID userId, long expectedVersion, List<PreferenceProfile.Preference> preferences);
}
