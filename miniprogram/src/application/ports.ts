import type {
  ActivePlanData,
  CreatePlanVersionRequest,
  PlanValidationData,
  PlanValidationDraft,
  PlanVersionResultData,
  RuleReference,
} from './models'

export interface PlanPersistencePort {
  validatePlan(plan: PlanValidationDraft, ruleReference: RuleReference): Promise<PlanValidationData>
  createInitialPlan(candidateId: string): Promise<ActivePlanData>
  getActivePlan(): Promise<ActivePlanData | null>
  createPlanVersion(planId: string, request: CreatePlanVersionRequest): Promise<PlanVersionResultData>
  previewRebalance(
    planId: string,
    request: Omit<CreatePlanVersionRequest, 'warningConfirmationToken'>,
  ): Promise<PlanVersionResultData>
}
