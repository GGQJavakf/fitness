package com.aifitness.assistant.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.EquipmentProfile;
import com.aifitness.assistant.profile.domain.PreferenceProfile;
import com.aifitness.assistant.profile.domain.UserProfile;
import com.aifitness.assistant.profile.infrastructure.InMemoryProfileRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileServiceTest {

    private ProfileService service;
    private AuthenticatedUserId user;

    @BeforeEach
    void setUp() {
        service = new ProfileService(new InMemoryProfileRepository());
        user = new AuthenticatedUserId(UUID.randomUUID());
    }

    @Test
    void createsAndUpdatesAValidKgOnlyProfile() {
        UserProfile created = service.updateProfile(user, 0, details(3, 60));
        UserProfile updated = service.updateProfile(user, created.version(), details(4, 75));

        assertThat(created.version()).isEqualTo(1);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(service.getProfile(user)).isEqualTo(updated);
    }

    @Test
    void rejectsFrequencyAndSessionDurationOutsideP0Rules() {
        assertThatThrownBy(() -> details(1, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> details(7, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> details(3, 50)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesEquipmentLevelsAndRejectsNonKgUnits() {
        UUID clientEquipmentKey = UUID.randomUUID();
        EquipmentProfile created = service.updateEquipment(
                user,
                0,
                List.of(new EquipmentProfile.Item(
                        clientEquipmentKey, "BARBELL", new BigDecimal("2.50"), "KG", List.of(new BigDecimal("20.00"), new BigDecimal("22.50")))));

        assertThat(created.version()).isEqualTo(1);
        assertThat(created.items()).hasSize(1);
        assertThat(created.items().getFirst().clientEquipmentKey()).isEqualTo(clientEquipmentKey);
        assertThatThrownBy(() -> new EquipmentProfile.Item(
                        UUID.randomUUID(), "BARBELL", new BigDecimal("2.50"), "LB", List.of(new BigDecimal("20.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EquipmentProfile.Item(
                        UUID.randomUUID(), "BARBELL", new BigDecimal("2.50"), "KG", List.of(new BigDecimal("21.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsMultipleConfigurationsForTheSameEquipmentType() {
        EquipmentProfile profile = service.updateEquipment(
                user,
                0,
                List.of(
                        new EquipmentProfile.Item(
                                UUID.randomUUID(), "DUMBBELL", new BigDecimal("1.00"), "KG", List.of(new BigDecimal("5.00"))),
                        new EquipmentProfile.Item(
                                UUID.randomUUID(), "DUMBBELL", new BigDecimal("2.50"), "KG", List.of(new BigDecimal("10.00")))));

        assertThat(profile.items()).hasSize(2).extracting(EquipmentProfile.Item::equipmentType)
                .containsOnly("DUMBBELL");
    }

    @Test
    void storesPreferredAndExcludedExercisesWithoutContradictoryDuplicates() {
        UUID squat = UUID.randomUUID();
        PreferenceProfile profile = service.updatePreferences(
                user,
                0,
                List.of(new PreferenceProfile.Preference(squat, PreferenceProfile.PreferenceType.EXCLUDED)));

        assertThat(profile.preferences()).singleElement().extracting(PreferenceProfile.Preference::exerciseId)
                .isEqualTo(squat);
        assertThatThrownBy(() -> service.updatePreferences(
                        user,
                        profile.version(),
                        List.of(
                                new PreferenceProfile.Preference(squat, PreferenceProfile.PreferenceType.PREFERRED),
                                new PreferenceProfile.Preference(squat, PreferenceProfile.PreferenceType.EXCLUDED))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverReturnsAnotherUsersProfile() {
        service.updateProfile(user, 0, details(3, 60));

        assertThatThrownBy(() -> service.getProfile(new AuthenticatedUserId(UUID.randomUUID())))
                .isInstanceOf(ProfileService.ProfileNotFoundException.class);
    }

    private static UserProfile.Details details(int weeklyFrequency, int sessionMinutes) {
        return new UserProfile.Details(
                UserProfile.ExperienceLevel.BEGINNER,
                UserProfile.FitnessGoal.GENERAL_FITNESS,
                weeklyFrequency,
                sessionMinutes,
                UserProfile.TrainingLocation.GYM);
    }
}
