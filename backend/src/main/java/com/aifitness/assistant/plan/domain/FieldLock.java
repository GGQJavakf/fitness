package com.aifitness.assistant.plan.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record FieldLock(String fieldPath, Status status, Instant lockedAt) {

    private static final Pattern LOCKABLE_PATH = Pattern.compile(
            "^/days/[^/]+/exercises/[^/]+/(workSets|repMin|repMax|restSeconds|targetWeightKg)$");

    public FieldLock {
        if (fieldPath == null || fieldPath.length() > 256 || !LOCKABLE_PATH.matcher(fieldPath).matches()) {
            throw new IllegalArgumentException("fieldPath is not a stable lockable plan field");
        }
        Objects.requireNonNull(status, "lock status must not be null");
        Objects.requireNonNull(lockedAt, "lockedAt must not be null");
    }

    public enum Status {
        UNLOCKED,
        USER_LOCKED,
        RULE_LOCKED
    }
}
