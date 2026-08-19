package com.aifitness.assistant.workout.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.application.ExerciseReplacementService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Profile({"local", "test", "staging-experience"})
public final class ExerciseReplacementController {
    private final ExerciseReplacementService replacements;
    private final Clock clock;

    public ExerciseReplacementController(ExerciseReplacementService replacements, Clock clock) {
        this.replacements = replacements;
        this.clock = clock;
    }

    @GetMapping("/exercises/{sourceCode}/replacements")
    public ApiResponse<ReplacementData> list(
            AuthenticatedUserId user, @PathVariable String sourceCode) {
        return response(sourceCode, replacements.candidates(user, sourceCode));
    }

    @GetMapping("/workout-sessions/{sessionId}/exercises/{snapshotId}/replacements")
    public ApiResponse<ReplacementData> listForWorkout(
            AuthenticatedUserId user,
            @PathVariable UUID sessionId,
            @PathVariable UUID snapshotId) {
        List<ExerciseCatalog.Exercise> items = replacements
                .candidates(user, sessionId, snapshotId, null);
        String sourceCode = items.isEmpty() ? "" : replacements.sourceCode(user, sessionId, snapshotId);
        return response(sourceCode, items);
    }

    private ApiResponse<ReplacementData> response(
            String sourceCode, List<ExerciseCatalog.Exercise> candidates) {
        List<ReplacementItem> items = candidates.stream()
                .map(ReplacementItem::from).toList();
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return new ApiResponse<>(new ReplacementData(sourceCode, items),
                new ResponseMeta(requestId, clock.instant()));
    }

    public record ReplacementData(String sourceCode, List<ReplacementItem> items) {}
    public record ReplacementItem(
            String id, String code, String name, String movementPattern, String difficulty,
            List<String> equipment, List<String> primaryMuscles) {
        static ReplacementItem from(ExerciseCatalog.Exercise exercise) {
            return new ReplacementItem(exercise.stableId().toString(), exercise.code(), exercise.name(),
                    exercise.movementPattern(), exercise.difficulty(), exercise.equipment().stream().sorted().toList(),
                    exercise.primaryMuscles().stream().sorted().toList());
        }
    }
}
