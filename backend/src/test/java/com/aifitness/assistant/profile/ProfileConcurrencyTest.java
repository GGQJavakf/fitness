package com.aifitness.assistant.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.UserProfile;
import com.aifitness.assistant.profile.infrastructure.InMemoryProfileRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProfileConcurrencyTest {

    @Test
    void onlyOneConcurrentUpdateCanConsumeAnExpectedVersion() throws Exception {
        ProfileService service = new ProfileService(new InMemoryProfileRepository());
        AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());
        long expectedVersion = service.updateProfile(user, 0, details(3)).version();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int frequency : List.of(4, 5)) {
                executor.submit(() -> {
                    try {
                        start.await();
                        service.updateProfile(user, expectedVersion, details(frequency));
                        success.incrementAndGet();
                    } catch (ProfileService.VersionConflictException exception) {
                        conflicts.incrementAndGet();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success).hasValue(1);
        assertThat(conflicts).hasValue(1);
        assertThat(service.getProfile(user).version()).isEqualTo(2);
    }

    @Test
    void staleVersionReportsTheCurrentVersion() {
        ProfileService service = new ProfileService(new InMemoryProfileRepository());
        AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());
        service.updateProfile(user, 0, details(3));

        assertThatThrownBy(() -> service.updateProfile(user, 0, details(4)))
                .isInstanceOfSatisfying(
                        ProfileService.VersionConflictException.class,
                        exception -> assertThat(exception.currentVersion()).isEqualTo(1));
    }

    private static UserProfile.Details details(int weeklyFrequency) {
        return new UserProfile.Details(
                UserProfile.ExperienceLevel.INTERMEDIATE,
                UserProfile.FitnessGoal.STRENGTH,
                weeklyFrequency,
                60,
                UserProfile.TrainingLocation.GYM);
    }
}
