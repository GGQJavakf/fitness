package com.aifitness.assistant.plan.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.FieldLock;
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
                generated.issues(), generated.lockedFieldOutcomes()));
    }

    @PostMapping("/validate")
    public ApiResponse<ValidationData> validate(
            AuthenticatedUserId user, @RequestBody ValidateRequest request) {
        if (request == null || request.plan() == null || request.ruleReference() == null) {
            throw new IllegalArgumentException("candidate plan and ruleReference are required");
        }
        List<PlanVersionService.ValidationIssue> issues = candidates.validate(
                user, request.plan().toDomain(), request.ruleReference().toDomain());
        boolean valid = issues.stream().noneMatch(
                issue -> issue.severity() == PlanVersionService.Severity.ERROR);
        return response(new ValidationData(valid, issues));
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

    public record ValidateRequest(PlanController.PlanData plan, RuleReferenceData ruleReference) {}

    public record GenerationData(
            PlanCandidateService.GenerationStatus status,
            Optional<CandidateData> candidate,
            List<PlanVersionService.ValidationIssue> validationIssues,
            Map<String, FieldLock.Status> lockedFieldOutcomes) {}

    public record CandidateData(
            String candidateId,
            PlanController.PlanData plan,
            List<PlanVersionService.ValidationIssue> validationIssues,
            RuleReferenceData ruleReference,
            Map<String, FieldLock.Status> lockedFieldOutcomes,
            PlanCandidateService.ExplanationStatus explanationStatus,
            String explanation,
            Instant expiresAt) {
        static CandidateData from(
                PlanCandidateService.CandidateEnvelope envelope,
                List<PlanVersionService.ValidationIssue> issues,
                Map<String, FieldLock.Status> lockedFieldOutcomes) {
            return new CandidateData(
                    envelope.candidateId(), PlanController.PlanData.from(envelope.plan()), issues,
                    RuleReferenceData.from(envelope.ruleReference()), lockedFieldOutcomes,
                    envelope.explanationStatus(), envelope.explanation(), envelope.expiresAt());
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

    public record ValidationData(
            boolean valid, List<PlanVersionService.ValidationIssue> validationIssues) {}
}
