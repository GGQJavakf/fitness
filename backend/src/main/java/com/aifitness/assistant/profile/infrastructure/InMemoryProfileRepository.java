package com.aifitness.assistant.profile.infrastructure;

import com.aifitness.assistant.profile.application.ProfileRepository;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.EquipmentProfile;
import com.aifitness.assistant.profile.domain.PreferenceProfile;
import com.aifitness.assistant.profile.domain.UserProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryProfileRepository implements ProfileRepository {

    private final Map<UUID, UserProfile> profiles = new HashMap<>();
    private final Map<UUID, EquipmentProfile> equipment = new HashMap<>();
    private final Map<UUID, PreferenceProfile> preferences = new HashMap<>();

    @Override
    public synchronized Optional<UserProfile> findProfile(UUID userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    @Override
    public synchronized UserProfile replaceProfile(
            UUID userId, long expectedVersion, UserProfile.Details details) {
        long currentVersion = currentVersion(profiles.get(userId));
        requireVersion(expectedVersion, currentVersion);
        UserProfile updated = new UserProfile(userId, details, currentVersion + 1);
        profiles.put(userId, updated);
        return updated;
    }

    @Override
    public synchronized Optional<EquipmentProfile> findEquipment(UUID userId) {
        return Optional.ofNullable(equipment.get(userId));
    }

    @Override
    public synchronized EquipmentProfile replaceEquipment(
            UUID userId, long expectedVersion, List<EquipmentProfile.Item> items) {
        long currentVersion = currentVersion(equipment.get(userId));
        requireVersion(expectedVersion, currentVersion);
        EquipmentProfile updated = new EquipmentProfile(userId, items, currentVersion + 1);
        equipment.put(userId, updated);
        return updated;
    }

    @Override
    public synchronized Optional<PreferenceProfile> findPreferences(UUID userId) {
        return Optional.ofNullable(preferences.get(userId));
    }

    @Override
    public synchronized PreferenceProfile replacePreferences(
            UUID userId, long expectedVersion, List<PreferenceProfile.Preference> preferenceItems) {
        long currentVersion = currentVersion(preferences.get(userId));
        requireVersion(expectedVersion, currentVersion);
        PreferenceProfile updated = new PreferenceProfile(userId, preferenceItems, currentVersion + 1);
        preferences.put(userId, updated);
        return updated;
    }

    private static long currentVersion(Object resource) {
        if (resource instanceof UserProfile profile) {
            return profile.version();
        }
        if (resource instanceof EquipmentProfile profile) {
            return profile.version();
        }
        if (resource instanceof PreferenceProfile profile) {
            return profile.version();
        }
        return 0;
    }

    private static void requireVersion(long expectedVersion, long currentVersion) {
        if (expectedVersion != currentVersion) {
            throw new ProfileService.VersionConflictException(currentVersion);
        }
    }
}
