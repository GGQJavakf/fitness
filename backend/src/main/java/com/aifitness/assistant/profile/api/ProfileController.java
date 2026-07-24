package com.aifitness.assistant.profile.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.EquipmentProfile;
import com.aifitness.assistant.profile.domain.PreferenceProfile;
import com.aifitness.assistant.profile.domain.UserProfile;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
@Profile({"local", "test", "staging-experience"})
public final class ProfileController {

    private final ProfileService profiles;
    private final Clock clock;

    public ProfileController(ProfileService profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<ProfileData> getProfile(AuthenticatedUserId user) {
        return response(ProfileData.from(profiles.getProfile(user)));
    }

    @PutMapping
    public ApiResponse<ProfileData> updateProfile(
            AuthenticatedUserId user, @RequestBody UpdateProfileRequest request) {
        UserProfile.Details details = new UserProfile.Details(
                request.experience(),
                request.goal(),
                request.weeklyFrequency(),
                request.sessionMinutes(),
                request.location());
        return response(ProfileData.from(
                profiles.updateProfile(user, requiredExpectedVersion(request.expectedVersion()), details)));
    }

    @GetMapping("/equipment")
    public ApiResponse<EquipmentData> getEquipment(AuthenticatedUserId user) {
        return response(EquipmentData.from(profiles.getEquipment(user)));
    }

    @PutMapping("/equipment")
    public ApiResponse<EquipmentData> updateEquipment(
            AuthenticatedUserId user, @RequestBody UpdateEquipmentRequest request) {
        if (request.items() == null) {
            throw new IllegalArgumentException("items must not be null");
        }
        List<EquipmentProfile.Item> items = request.items().stream()
                .map(ProfileController::requiredEquipmentItem)
                .map(EquipmentItemData::toDomain)
                .toList();
        return response(EquipmentData.from(profiles.updateEquipment(
                user, requiredExpectedVersion(request.expectedVersion()), items)));
    }

    @GetMapping("/preferences")
    public ApiResponse<PreferencesData> getPreferences(AuthenticatedUserId user) {
        return response(PreferencesData.from(profiles.getPreferences(user)));
    }

    @PutMapping("/preferences")
    public ApiResponse<PreferencesData> updatePreferences(
            AuthenticatedUserId user, @RequestBody UpdatePreferencesRequest request) {
        if (request.items() == null) {
            throw new IllegalArgumentException("items must not be null");
        }
        List<PreferenceProfile.Preference> preferences = request.items().stream()
                .map(ProfileController::requiredPreference)
                .map(PreferenceData::toDomain)
                .toList();
        return response(PreferencesData.from(
                profiles.updatePreferences(
                        user, requiredExpectedVersion(request.expectedVersion()), preferences)));
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    private static long requiredExpectedVersion(Long expectedVersion) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("expectedVersion must not be null");
        }
        return expectedVersion;
    }

    private static EquipmentItemData requiredEquipmentItem(EquipmentItemData item) {
        if (item == null) {
            throw new IllegalArgumentException("equipment item must not be null");
        }
        return item;
    }

    private static PreferenceData requiredPreference(PreferenceData preference) {
        if (preference == null) {
            throw new IllegalArgumentException("preference must not be null");
        }
        return preference;
    }

    public record UpdateProfileRequest(
            UserProfile.ExperienceLevel experience,
            UserProfile.FitnessGoal goal,
            int weeklyFrequency,
            int sessionMinutes,
            UserProfile.TrainingLocation location,
            Long expectedVersion) {}

    public record ProfileData(
            UserProfile.ExperienceLevel experience,
            UserProfile.FitnessGoal goal,
            int weeklyFrequency,
            int sessionMinutes,
            UserProfile.TrainingLocation location,
            long version) {
        static ProfileData from(UserProfile profile) {
            UserProfile.Details details = profile.details();
            return new ProfileData(
                    details.experience(),
                    details.goal(),
                    details.weeklyFrequency(),
                    details.sessionMinutes(),
                    details.location(),
                    profile.version());
        }
    }

    public record WeightData(BigDecimal value, String unit, String equipmentProfileId) {
        BigDecimal requireP0Kilograms() {
            if (value == null || !"KG".equals(unit) || value.signum() < 0 || value.scale() > 2) {
                throw new IllegalArgumentException("P0 weight must be a valid KG value");
            }
            if (equipmentProfileId != null) {
                throw new IllegalArgumentException("equipmentProfileId is server managed");
            }
            return value;
        }
    }

    public record EquipmentItemData(
            UUID clientEquipmentKey,
            String equipmentType,
            WeightData minIncrement,
            List<WeightData> availableLevels) {
        EquipmentProfile.Item toDomain() {
            if (clientEquipmentKey == null || minIncrement == null || availableLevels == null) {
                throw new IllegalArgumentException("equipment values must not be null");
            }
            return new EquipmentProfile.Item(
                    clientEquipmentKey,
                    equipmentType,
                    minIncrement.requireP0Kilograms(),
                    "KG",
                    availableLevels.stream()
                            .map(ProfileController::requiredWeight)
                            .map(WeightData::requireP0Kilograms)
                            .toList());
        }

        static EquipmentItemData from(EquipmentProfile.Item item) {
            return new EquipmentItemData(
                    item.clientEquipmentKey(),
                    item.equipmentType(),
                    new WeightData(item.minIncrement(), item.unit(), null),
                    item.availableLevels().stream()
                            .map(level -> new WeightData(level, item.unit(), null))
                            .toList());
        }
    }

    private static WeightData requiredWeight(WeightData weight) {
        if (weight == null) {
            throw new IllegalArgumentException("weight must not be null");
        }
        return weight;
    }

    public record UpdateEquipmentRequest(List<EquipmentItemData> items, Long expectedVersion) {}

    public record EquipmentData(List<EquipmentItemData> items, long version) {
        static EquipmentData from(EquipmentProfile profile) {
            return new EquipmentData(
                    profile.items().stream().map(EquipmentItemData::from).toList(), profile.version());
        }
    }

    public record PreferenceData(UUID exerciseId, PreferenceProfile.PreferenceType preferenceType) {
        PreferenceProfile.Preference toDomain() {
            return new PreferenceProfile.Preference(exerciseId, preferenceType);
        }

        static PreferenceData from(PreferenceProfile.Preference preference) {
            return new PreferenceData(preference.exerciseId(), preference.preferenceType());
        }
    }

    public record UpdatePreferencesRequest(List<PreferenceData> items, Long expectedVersion) {}

    public record PreferencesData(List<PreferenceData> items, long version) {
        static PreferencesData from(PreferenceProfile profile) {
            return new PreferencesData(
                    profile.preferences().stream().map(PreferenceData::from).toList(), profile.version());
        }
    }
}
