package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutRecoveryConfirmationStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Bounded digest-only adapter for local/test profiles. */
public final class InMemoryWorkoutRecoveryConfirmationStore implements WorkoutRecoveryConfirmationStore {
    private static final int MAXIMUM_ENTRIES = 1024;
    private final Map<String, PendingConfirmation> entries = new HashMap<>();
    private final SecureRandom random;

    public InMemoryWorkoutRecoveryConfirmationStore() {
        this(new SecureRandom());
    }

    InMemoryWorkoutRecoveryConfirmationStore(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public synchronized String issue(Binding binding, Instant issuedAt, Instant expiresAt) {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt must be in the future");
        removeExpired(issuedAt);
        String token = randomToken();
        entries.put(digestHex(token), new PendingConfirmation(binding, issuedAt, expiresAt));
        while (entries.size() > MAXIMUM_ENTRIES) {
            String oldest = entries.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                    .orElseThrow().getKey();
            entries.remove(oldest);
        }
        return token;
    }

    @Override
    public synchronized boolean consume(Binding binding, String token, Instant now) {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (token == null || token.isBlank() || token.length() > 256) return false;
        String digest = digestHex(token);
        PendingConfirmation pending = entries.get(digest);
        if (pending == null || !pending.binding().equals(binding)
                || pending.issuedAt().isAfter(now) || !pending.expiresAt().isAfter(now)) {
            return false;
        }
        entries.remove(digest);
        return true;
    }

    private void removeExpired(Instant now) {
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String randomToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(new HashMap<>(entries));
    }

    synchronized void restore(Snapshot snapshot) {
        entries.clear();
        entries.putAll(snapshot.entries());
    }

    private static String digestHex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Snapshot(Map<String, PendingConfirmation> entries) {
        Snapshot {
            entries = Map.copyOf(entries);
        }
    }

    private record PendingConfirmation(Binding binding, Instant issuedAt, Instant expiresAt) {}
}
