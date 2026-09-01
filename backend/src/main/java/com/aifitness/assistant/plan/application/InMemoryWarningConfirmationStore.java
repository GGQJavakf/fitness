package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded local/test adapter; shared deployments use the JDBC implementation. */
public final class InMemoryWarningConfirmationStore implements WarningConfirmationStore {
    private static final int MAXIMUM_ENTRIES = 1024;

    private final Map<String, PendingWarning> entries = new HashMap<>();
    private final Clock clock;
    private final SecureRandom random;

    public InMemoryWarningConfirmationStore(Clock clock) {
        this(clock, new SecureRandom());
    }

    InMemoryWarningConfirmationStore(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public synchronized String issue(AuthenticatedUserId user, String fingerprint, Instant expiresAt) {
        Objects.requireNonNull(user, "user must not be null");
        requireFingerprint(fingerprint);
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
        removeExpired(now);
        String token = randomToken();
        entries.put(token, new PendingWarning(user, fingerprint, expiresAt));
        while (entries.size() > MAXIMUM_ENTRIES) {
            String oldest = entries.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                    .orElseThrow().getKey();
            entries.remove(oldest);
        }
        return token;
    }

    @Override
    public synchronized boolean consume(
            AuthenticatedUserId user, String token, String fingerprint, Instant now) {
        Objects.requireNonNull(user, "user must not be null");
        requireFingerprint(fingerprint);
        Objects.requireNonNull(now, "now must not be null");
        removeExpired(now);
        PendingWarning pending = token == null ? null : entries.get(token);
        if (pending == null || !pending.user().equals(user) || !pending.fingerprint().equals(fingerprint)) {
            return false;
        }
        entries.remove(token);
        return true;
    }

    /** Opaque copy used by the local atomic candidate-commit transaction adapter. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(entries);
    }

    /** Restores warning consumption when another part of the local transaction fails. */
    public synchronized void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        entries.clear();
        entries.putAll(snapshot.entries);
    }

    private void removeExpired(Instant now) {
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String randomToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static void requireFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank() || fingerprint.length() > 128) {
            throw new IllegalArgumentException("fingerprint is invalid");
        }
    }

    private record PendingWarning(
            AuthenticatedUserId user,
            String fingerprint,
            Instant expiresAt) {}

    public static final class Snapshot {
        private final Map<String, PendingWarning> entries;

        private Snapshot(Map<String, PendingWarning> entries) {
            this.entries = new HashMap<>(entries);
        }
    }
}
