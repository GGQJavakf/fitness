package com.aifitness.assistant.privacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.privacy.application.PrivacyExportRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryPrivacyExportRepositoryTest {

    @Test
    void readRemovesAnArtifactAtItsExactExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T08:00:00Z"));
        var repository = new InMemoryPrivacyExportRepository(clock);
        var artifact = artifact(clock.instant(), clock.instant().plus(Duration.ofMinutes(5)));
        repository.save(artifact);

        clock.advance(Duration.ofMinutes(5));

        assertThat(repository.findById(artifact.id())).isEmpty();
        assertThat(retainedArtifactCount(repository)).isZero();
    }

    @Test
    void writeRemovesExpiredArtifactsBeforeKeepingTheNewArtifact() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T08:00:00Z"));
        var repository = new InMemoryPrivacyExportRepository(clock);
        repository.save(artifact(clock.instant(), clock.instant().plus(Duration.ofMinutes(1))));
        clock.advance(Duration.ofMinutes(2));

        var current = artifact(clock.instant(), clock.instant().plus(Duration.ofMinutes(10)));
        repository.save(current);

        assertThat(retainedArtifactCount(repository)).isOne();
        assertThat(repository.findById(current.id())).contains(current);
    }

    private static PrivacyExportRepository.ExportArtifact artifact(
            Instant generatedAt, Instant expiresAt) {
        return new PrivacyExportRepository.ExportArtifact(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "READY",
                generatedAt,
                expiresAt,
                List.of(),
                List.of(),
                List.of());
    }

    @SuppressWarnings("unchecked")
    private static int retainedArtifactCount(InMemoryPrivacyExportRepository repository) {
        try {
            var artifacts = InMemoryPrivacyExportRepository.class.getDeclaredField("artifacts");
            artifacts.setAccessible(true);
            return ((java.util.Map<UUID, ?>) artifacts.get(repository)).size();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
