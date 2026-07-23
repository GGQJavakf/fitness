package com.aifitness.assistant.content.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Clock;
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
@RequestMapping("/api/v1/plan-templates")
@Profile({"local", "test"})
public final class TemplateController {

    private final TemplateQueryService templates;
    private final ExerciseQueryService exercises;
    private final Clock clock;

    public TemplateController(TemplateQueryService templates, ExerciseQueryService exercises, Clock clock) {
        this.templates = templates;
        this.exercises = exercises;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<TemplateListData> list(
            AuthenticatedUserId user, @RequestParam Optional<Integer> weeklyFrequency) {
        List<TemplateData> items = templates.list(user, weeklyFrequency).stream().map(this::toData).toList();
        return response(new TemplateListData(items, templates.version(), exercises.version()));
    }

    private TemplateData toData(PlanTemplateCatalog.Template template) {
        return new TemplateData(
                template.code(), template.name(), template.sessionsPerWeek(),
                template.exerciseCodes().stream().sorted().toList(), templates.version(), exercises.version());
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record TemplateListData(
            List<TemplateData> items, String templateVersion, String contentVersion) {}

    public record TemplateData(
            String code, String name, int weeklyFrequency, List<String> exerciseCodes,
            String templateVersion, String contentVersion) {}
}
