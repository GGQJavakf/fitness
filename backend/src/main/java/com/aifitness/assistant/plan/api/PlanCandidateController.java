package com.aifitness.assistant.plan.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.rules.domain.PlanGenerationEngine;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Profile({"local", "test"})
public final class PlanCandidateController {

    private final PlanCandidateService candidates;
    private final Clock clock;

    public PlanCandidateController(PlanCandidateService candidates, Clock clock) {
        this.candidates = candidates;
        this.clock = clock;
    }

    @PostMapping("/candidates")
    public ApiResponse<GenerationData> generate(
            AuthenticatedUserId user, @RequestBody CandidateRequest request) {
        if (request == null || request.profileVersion() == null || request.profileVersion() < 0) {
            throw new IllegalArgumentException("profileVersion must be non-negative");
        }
        Map<String, Integer> lockedFields = validLockedFields(request.lockedFields());
        PlanCandidateService.GeneratedCandidates generated = candidates.generate(
                user, request.profileVersion(), lockedFields);
        return response(new GenerationData(
                generated.status(),
                generated.candidate().map(candidate -> CandidateData.from(
                        candidate, generated.issues(), generated.lockedFieldOutcomes())),
                generated.issues(),
                generated.lockedFieldOutcomes()));
    }

    @PostMapping("/validate")
    public ApiResponse<ValidationData> validate(
            AuthenticatedUserId user, @RequestBody ValidateRequest request) {
        PlanGenerationEngine.Candidate candidate = requiredCandidate(request);
        List<PlanGenerationEngine.ValidationIssue> issues = candidates.validate(user, candidate);
        boolean valid = issues.stream().noneMatch(
                issue -> issue.severity() == PlanGenerationEngine.ValidationSeverity.ERROR);
        return response(new ValidationData(valid, issues));
    }

    private static PlanGenerationEngine.Candidate requiredCandidate(ValidateRequest request) {
        if (request == null || request.plan() == null || request.ruleReference() == null) {
            throw new IllegalArgumentException("candidate plan and ruleReference are required");
        }
        return request.plan().toDomain(request.ruleReference().toDomain());
    }

    private static Map<String, Integer> validLockedFields(Map<String, Integer> lockedFields) {
        if (lockedFields == null) {
            return Map.of();
        }
        if (lockedFields.size() > 100) {
            throw new IllegalArgumentException("lockedFields must contain at most 100 entries");
        }
        lockedFields.forEach((path, value) -> {
            if (path == null || path.isBlank() || path.length() > 256 || !path.startsWith("/days/")) {
                throw new IllegalArgumentException("locked field path is invalid");
            }
            if (value == null || value < 0 || value > 3600) {
                throw new IllegalArgumentException("locked numeric value is invalid");
            }
        });
        return Map.copyOf(lockedFields);
    }

    private <T> ApiResponse<T> response(T data) {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new ApiResponse<>(data, new ResponseMeta(requestId, clock.instant()));
    }

    public record CandidateRequest(Long profileVersion, Map<String, Integer> lockedFields) {}

    public record ValidateRequest(ValidationDraftData plan, RuleReferenceData ruleReference) {}

    public record GenerationData(
            PlanGenerationEngine.GenerationStatus status,
            Optional<CandidateData> candidate,
            List<PlanGenerationEngine.ValidationIssue> validationIssues,
            Map<String, PlanGenerationEngine.LockStatus> lockedFieldOutcomes) {}

    public record CandidateData(
            String candidateId,
            PlanDraftData plan,
            List<PlanGenerationEngine.ValidationIssue> validationIssues,
            RuleReferenceData ruleReference,
            Map<String, PlanGenerationEngine.LockStatus> lockedFieldOutcomes,
            PlanCandidateService.ExplanationStatus explanationStatus,
            String explanation,
            Instant expiresAt) {

        static CandidateData from(
                PlanCandidateService.CandidateEnvelope envelope,
                List<PlanGenerationEngine.ValidationIssue> issues,
                Map<String, PlanGenerationEngine.LockStatus> lockedFieldOutcomes) {
            PlanGenerationEngine.Candidate candidate = envelope.plan();
            return new CandidateData(
                    envelope.candidateId(),
                    PlanDraftData.from(candidate, lockedFieldOutcomes),
                    issues,
                    RuleReferenceData.from(candidate.ruleReference()),
                    lockedFieldOutcomes,
                    envelope.explanationStatus(),
                    envelope.explanation(),
                    envelope.expiresAt());
        }
    }

    public record PlanDraftData(
            String templateCode,
            String name,
            List<DayData> days,
            Map<String, PlanGenerationEngine.LockStatus> locks) {

        static PlanDraftData from(
                PlanGenerationEngine.Candidate candidate,
                Map<String, PlanGenerationEngine.LockStatus> locks) {
            return new PlanDraftData(
                    candidate.templateCode(), candidate.name(),
                    candidate.days().stream().map(DayData::from).toList(), locks);
        }

        PlanGenerationEngine.Candidate toDomain(RuleReference reference) {
            if (days == null || days.isEmpty() || days.size() > 6 || days.stream().anyMatch(day -> day == null)) {
                throw new IllegalArgumentException("plan must contain between 1 and 6 days");
            }
            return new PlanGenerationEngine.Candidate(
                    templateCode, name, days.stream().map(DayData::toDomain).toList(), reference);
        }
    }

    public record ValidationDraftData(String templateCode, String name, List<DayData> days) {
        PlanGenerationEngine.Candidate toDomain(RuleReference reference) {
            if (days == null || days.isEmpty() || days.size() > 6 || days.stream().anyMatch(day -> day == null)) {
                throw new IllegalArgumentException("plan must contain between 1 and 6 days");
            }
            return new PlanGenerationEngine.Candidate(
                    templateCode, name, days.stream().map(DayData::toDomain).toList(), reference);
        }
    }

    public record DayData(String code, String name, List<ExerciseData> exercises) {
        static DayData from(PlanGenerationEngine.Day day) {
            return new DayData(day.code(), day.name(), day.exercises().stream().map(ExerciseData::from).toList());
        }

        PlanGenerationEngine.Day toDomain() {
            if (exercises == null || exercises.isEmpty() || exercises.size() > 8
                    || exercises.stream().anyMatch(exercise -> exercise == null)) {
                throw new IllegalArgumentException("day must contain between 1 and 8 exercises");
            }
            return new PlanGenerationEngine.Day(
                    code, name, exercises.stream().map(ExerciseData::toDomain).toList());
        }
    }

    public record ExerciseData(
            String exerciseCode,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            PlanGenerationEngine.WeightStatus weightStatus) {
        static ExerciseData from(PlanGenerationEngine.Exercise exercise) {
            return new ExerciseData(
                    exercise.exerciseCode(), exercise.workSets(), exercise.repMin(), exercise.repMax(),
                    exercise.restSeconds(), exercise.weightStatus());
        }

        PlanGenerationEngine.Exercise toDomain() {
            return new PlanGenerationEngine.Exercise(
                    exerciseCode, workSets, repMin, repMax, restSeconds, weightStatus);
        }
    }

    public record RuleReferenceData(String ruleVersion, String templateVersion, String contentVersion) {
        static RuleReferenceData from(RuleReference reference) {
            return new RuleReferenceData(
                    reference.ruleVersion(), reference.templateVersion(), reference.contentVersion());
        }

        RuleReference toDomain() {
            return new RuleReference(ruleVersion, templateVersion, contentVersion);
        }
    }

    public record ValidationData(boolean valid, List<PlanGenerationEngine.ValidationIssue> validationIssues) {}
}
