package com.aifitness.assistant.content.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exercises")
@Profile({"local", "test", "staging-experience"})
public final class ExerciseController {

    private final ExerciseQueryService exercises;
    private final Clock clock;

    public ExerciseController(ExerciseQueryService exercises, Clock clock) {
        this.exercises = exercises;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<ExerciseListData> list(
            AuthenticatedUserId user,
            @RequestParam Optional<String> equipmentType,
            @RequestParam Optional<String> movementPattern,
            @RequestParam Optional<String> muscleGroup) {
        ExerciseQueryService.Filter filter = new ExerciseQueryService.Filter(
                equipmentType, movementPattern, muscleGroup);
        List<ExerciseData> items = exercises.list(user, filter).stream().map(this::toData).toList();
        return response(new ExerciseListData(items, exercises.version()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ExerciseData> get(AuthenticatedUserId user, @PathVariable String id) {
        ExerciseCatalog.Exercise exercise = exercises.get(user, id)
                .orElseThrow(ContentExceptionHandler.ContentNotFoundException::new);
        return response(toData(exercise));
    }

    private ExerciseData toData(ExerciseCatalog.Exercise exercise) {
        return new ExerciseData(
                exercise.stableId().toString(), exercise.code(), exercise.name(),
                exercise.plainLanguage(), exercise.movementPattern(), exercise.difficulty(),
                exercise.equipment().stream().sorted().toList(),
                exercise.primaryMuscles().stream().sorted().toList(), exercise.instructions(),
                exercise.safetyCues(), new ImageData(exercise.image().primaryRef(), exercise.image().fallbackRef()),
                exercise.alternatives().stream()
                        .sorted(Comparator.comparingInt(ExerciseCatalog.Alternative::rank))
                        .map(alternative -> new AlternativeData(alternative.exerciseCode(), alternative.rank()))
                        .toList(),
                exercises.version());
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record ExerciseListData(List<ExerciseData> items, String contentVersion) {}

    public record ExerciseData(
            String id, String code, String name, String plainLanguage, String movementPattern, String difficulty,
            List<String> equipment,
            List<String> primaryMuscles, List<String> instructions, List<String> safetyCues, ImageData image,
            List<AlternativeData> alternatives, String contentVersion) {}

    public record ImageData(String primaryRef, String fallbackRef) {}

    public record AlternativeData(String exerciseCode, int rank) {}
}
