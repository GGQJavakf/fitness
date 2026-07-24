package com.aifitness.assistant.ai.api;

import com.aifitness.assistant.ai.application.AiContentService;
import com.aifitness.assistant.ai.application.AiOutputValidator;
import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
@Profile({"local", "test", "staging-experience"})
public final class AiController {
    private final AiContentService content;
    private final Clock clock;

    public AiController(AiContentService content, Clock clock) {
        this.content = content;
        this.clock = clock;
    }

    @PostMapping("/plan-explanations")
    public ApiResponse<GeneratedContentData> explainPlan(
            AuthenticatedUserId user, @RequestBody PlanExplanationRequest request) {
        if (request == null || request.candidateId() == null || request.candidateId().isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        return response(GeneratedContentData.from(content.explainPlan(user, request.candidateId())));
    }

    @PostMapping("/workout-summaries")
    public ApiResponse<GeneratedContentData> summarizeWorkout(
            AuthenticatedUserId user, @RequestBody WorkoutSummaryRequest request) {
        if (request == null || request.workoutSessionId() == null) {
            throw new IllegalArgumentException("workoutSessionId must not be null");
        }
        return response(GeneratedContentData.from(content.summarizeWorkout(user, request.workoutSessionId())));
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record PlanExplanationRequest(String candidateId) {}
    public record WorkoutSummaryRequest(UUID workoutSessionId) {}

    public record GeneratedContentData(
            AiContentService.Status status,
            String content,
            String validationStatus,
            Optional<StructuredSummaryData> structured) {
        static GeneratedContentData from(AiContentService.GeneratedContent content) {
            return new GeneratedContentData(
                    content.status(), content.content(), content.validationStatus(),
                    content.structured().map(StructuredSummaryData::from));
        }
    }

    public record StructuredSummaryData(
            String summary,
            List<String> highlights,
            List<String> issues,
            List<String> nextActions,
            String explanation,
            Optional<String> safetyNotice) {
        static StructuredSummaryData from(AiOutputValidator.AiSummary summary) {
            return new StructuredSummaryData(
                    summary.summary(), summary.highlights(), summary.issues(), summary.nextActions(),
                    summary.explanation(), summary.safetyNotice());
        }
    }
}
