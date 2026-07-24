package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.PlanDraft;
import java.util.List;
import java.util.Objects;

public final class RulesPlanPolicy implements PlanVersionService.PlanPolicy {
    private final PlanCandidateService candidates;

    public RulesPlanPolicy(PlanCandidateService candidates) {
        this.candidates = Objects.requireNonNull(candidates, "candidates must not be null");
    }

    @Override
    public PlanVersionService.CandidatePlan candidate(AuthenticatedUserId user, String candidateId) {
        PlanCandidateService.CandidateEnvelope candidate = candidates.candidate(user, candidateId);
        return new PlanVersionService.CandidatePlan(
                candidateId, candidate.plan(), candidate.ruleReference());
    }

    @Override
    public List<PlanVersionService.ValidationIssue> validate(
            AuthenticatedUserId user, PlanDraft plan, RuleReference reference) {
        return candidates.validate(user, plan, reference);
    }
}
