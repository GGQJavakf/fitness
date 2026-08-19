package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.AuthenticationAttemptLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

final class InMemoryAuthenticationAttemptLimiter implements AuthenticationAttemptLimiter {
    private final Map<Key, Integer> attempts = new HashMap<>();
    private final int maximumPerCredential;
    private final int maximumGlobal;
    private final long windowMillis;

    InMemoryAuthenticationAttemptLimiter(
            int maximumPerCredential, int maximumGlobal, Duration window) {
        Objects.requireNonNull(window, "window must not be null");
        if (maximumPerCredential < 1 || maximumGlobal < maximumPerCredential
                || window.isNegative() || window.isZero() || window.toMillis() < 1) {
            throw new IllegalArgumentException("invalid authentication rate limit");
        }
        this.maximumPerCredential = maximumPerCredential;
        this.maximumGlobal = maximumGlobal;
        this.windowMillis = window.toMillis();
    }

    @Override
    public synchronized boolean allow(Action action, String credential, Instant now) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(credential, "credential must not be null");
        Objects.requireNonNull(now, "now must not be null");
        long bucket = Math.floorDiv(now.toEpochMilli(), windowMillis);
        attempts.keySet().removeIf(key -> key.bucket() < bucket);
        if (!claim(new Key(action, "GLOBAL", bucket), maximumGlobal)) return false;
        return claim(new Key(action, digest(credential), bucket), maximumPerCredential);
    }

    private boolean claim(Key key, int maximum) {
        int current = attempts.getOrDefault(key, 0);
        if (current >= maximum) return false;
        attempts.put(key, current + 1);
        return true;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Key(Action action, String digest, long bucket) {}
}
