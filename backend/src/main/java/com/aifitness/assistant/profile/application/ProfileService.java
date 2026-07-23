package com.aifitness.assistant.profile.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.profile.domain.EquipmentProfile;
import com.aifitness.assistant.profile.domain.PreferenceProfile;
import com.aifitness.assistant.profile.domain.UserProfile;
import java.util.List;
import java.util.Objects;

public final class ProfileService {

    private final ProfileRepository profiles;

    public ProfileService(ProfileRepository profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
    }

    public UserProfile getProfile(AuthenticatedUserId user) {
        return profiles.findProfile(user.value()).orElseThrow(ProfileNotFoundException::new);
    }

    public UserProfile updateProfile(
            AuthenticatedUserId user, long expectedVersion, UserProfile.Details details) {
        return profiles.replaceProfile(user.value(), validExpectedVersion(expectedVersion), details);
    }

    public EquipmentProfile getEquipment(AuthenticatedUserId user) {
        return profiles.findEquipment(user.value()).orElseThrow(ProfileNotFoundException::new);
    }

    public EquipmentProfile updateEquipment(
            AuthenticatedUserId user, long expectedVersion, List<EquipmentProfile.Item> items) {
        return profiles.replaceEquipment(user.value(), validExpectedVersion(expectedVersion), List.copyOf(items));
    }

    public PreferenceProfile getPreferences(AuthenticatedUserId user) {
        return profiles.findPreferences(user.value()).orElseThrow(ProfileNotFoundException::new);
    }

    public PreferenceProfile updatePreferences(
            AuthenticatedUserId user,
            long expectedVersion,
            List<PreferenceProfile.Preference> preferences) {
        return profiles.replacePreferences(
                user.value(), validExpectedVersion(expectedVersion), List.copyOf(preferences));
    }

    private static long validExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        return expectedVersion;
    }

    public static final class VersionConflictException extends RuntimeException {
        private final long currentVersion;

        public VersionConflictException(long currentVersion) {
            super("resource version conflict");
            this.currentVersion = currentVersion;
        }

        public long currentVersion() {
            return currentVersion;
        }
    }

    public static final class ProfileNotFoundException extends RuntimeException {
        public ProfileNotFoundException() {
            super("profile not found");
        }
    }
}
