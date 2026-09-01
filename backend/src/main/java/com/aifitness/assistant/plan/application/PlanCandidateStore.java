package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.util.Optional;

/** User-scoped storage boundary for short-lived generated plan candidates. */
public interface PlanCandidateStore {

    void save(AuthenticatedUserId user, PlanCandidateService.CandidateEnvelope candidate);

    Optional<PlanCandidateService.CandidateEnvelope> find(
            AuthenticatedUserId user, String candidateId);
}
