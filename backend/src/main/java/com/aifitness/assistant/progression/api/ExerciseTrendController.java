package com.aifitness.assistant.progression.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.progression.application.ExerciseTrendQuery;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/progress/exercises")
@Profile({"local", "test", "staging-experience"})
public final class ExerciseTrendController {
    private final ExerciseTrendQuery trends;
    private final Clock clock;

    public ExerciseTrendController(ExerciseTrendQuery trends, Clock clock) {
        this.trends = trends;
        this.clock = clock;
    }

    @GetMapping("/{exerciseId}")
    public ApiResponse<ExerciseTrendQuery.Trend> get(
            AuthenticatedUserId user, @PathVariable("exerciseId") String exerciseCode) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return new ApiResponse<>(trends.load(user, exerciseCode), new ResponseMeta(requestId, clock.instant()));
    }
}
