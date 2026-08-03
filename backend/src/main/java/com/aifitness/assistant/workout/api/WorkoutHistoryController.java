package com.aifitness.assistant.workout.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.WorkoutHistoryQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workout-sessions")
@Profile({"local", "test", "staging-experience"})
public final class WorkoutHistoryController {
    private final WorkoutHistoryQueryService history;
    private final Clock clock;

    public WorkoutHistoryController(WorkoutHistoryQueryService history, Clock clock) {
        this.history = history;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<HistoryData> list(
            AuthenticatedUserId user,
            @RequestParam Optional<String> cursor,
            @RequestParam(defaultValue = "20") int limit) {
        WorkoutHistoryQueryService.Page page = history.list(user, cursor, limit);
        return response(new HistoryData(
                page.items().stream().map(HistoryItem::from).toList(), page.nextCursor(), page.hasMore()));
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record HistoryData(List<HistoryItem> items, Optional<String> nextCursor, boolean hasMore) {}
    public record HistoryItem(
            UUID sessionId, String trainingDayCode, String status, Instant startedAt, Instant completedAt,
            int completedWorkSets, BigDecimal completedVolumeKg, int completedReps, boolean usesExternalLoad) {
        static HistoryItem from(WorkoutHistoryQueryService.Item item) {
            return new HistoryItem(item.sessionId(), item.trainingDayCode(), item.status().name(), item.startedAt(),
                    item.completedAt(), item.completedWorkSets(), item.completedVolumeKg(),
                    item.completedReps(), item.usesExternalLoad());
        }
    }
}
