package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class WorkoutHistoryQueryService {
    private static final int MAX_LIMIT = 50;
    private final WorkoutSessionRepository sessions;
    private final WorkoutSetRepository sets;

    public WorkoutHistoryQueryService(WorkoutSessionRepository sessions, WorkoutSetRepository sets) {
        this.sessions = Objects.requireNonNull(sessions);
        this.sets = Objects.requireNonNull(sets);
    }

    public Page list(AuthenticatedUserId user, Optional<String> encodedCursor, int limit) {
        Objects.requireNonNull(user);
        Objects.requireNonNull(encodedCursor);
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("history limit must be between 1 and 50");
        Optional<Cursor> cursor = encodedCursor.map(WorkoutHistoryQueryService::decode);
        List<WorkoutSession> found = sessions.findHistory(
                user.value(), cursor.map(Cursor::startedAt), cursor.map(Cursor::id), limit + 1);
        boolean hasMore = found.size() > limit;
        List<WorkoutSession> page = found.stream().limit(limit).toList();
        List<Item> items = page.stream().map(session -> item(user.value(), session)).toList();
        Optional<String> next = hasMore && !page.isEmpty()
                ? Optional.of(encode(new Cursor(page.getLast().startedAt(), page.getLast().id())))
                : Optional.empty();
        return new Page(items, next, hasMore);
    }

    public Summary summary(AuthenticatedUserId user, UUID sessionId) {
        Objects.requireNonNull(user);
        Objects.requireNonNull(sessionId);
        WorkoutSession session = sessions.findByIdAndUser(sessionId, user.value())
                .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
        List<WorkoutSet> facts = sets.findBySession(user.value(), sessionId);
        List<WorkoutSet> completed = WorkoutFactSummary.completedPrescribedWorkSets(session, facts);
        Metrics metrics = metrics(completed);
        return new Summary(session.id(), session.status(), completed.size(), metrics.volumeKg(),
                metrics.completedReps(), metrics.usesExternalLoad());
    }

    private Item item(UUID userId, WorkoutSession session) {
        List<WorkoutSet> facts = sets.findBySession(userId, session.id());
        List<WorkoutSet> completed = WorkoutFactSummary.completedPrescribedWorkSets(session, facts);
        Metrics metrics = metrics(completed);
        return new Item(session.id(), session.trainingDayCode(), session.status(), session.startedAt(),
                session.completedAt().orElseThrow(), completed.size(), metrics.volumeKg(),
                metrics.completedReps(), metrics.usesExternalLoad());
    }

    private static Metrics metrics(List<WorkoutSet> completed) {
        BigDecimal volume = completed.stream().map(set -> set.actual().weight()
                .multiply(BigDecimal.valueOf(set.actual().reps()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        int completedReps = completed.stream().mapToInt(set -> set.actual().reps()).sum();
        boolean usesExternalLoad = completed.stream()
                .anyMatch(set -> set.actual().weight().compareTo(BigDecimal.ZERO) > 0);
        return new Metrics(volume.stripTrailingZeros(), completedReps, usesExternalLoad);
    }

    private static String encode(Cursor cursor) {
        String raw = cursor.startedAt() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("history cursor is invalid");
        }
    }

    private record Cursor(Instant startedAt, UUID id) {}
    private record Metrics(BigDecimal volumeKg, int completedReps, boolean usesExternalLoad) {}
    public record Item(UUID sessionId, String trainingDayCode, WorkoutStatus status, Instant startedAt,
                       Instant completedAt, int completedWorkSets, BigDecimal completedVolumeKg,
                       int completedReps, boolean usesExternalLoad) {}
    public record Summary(
            UUID sessionId, WorkoutStatus status, int completedWorkSets, BigDecimal completedVolumeKg,
            int completedReps, boolean usesExternalLoad) {}
    public record Page(List<Item> items, Optional<String> nextCursor, boolean hasMore) {
        public Page { items = List.copyOf(items); Objects.requireNonNull(nextCursor); }
    }
}
