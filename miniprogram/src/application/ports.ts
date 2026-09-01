import type {
  ActivePlanData,
  CandidateCommitRequest,
  CreatePlanVersionRequest,
  PlanValidationData,
  PlanValidationDraft,
  PlanExerciseOption,
  PlanExerciseReplacementOption,
  PlanDayOption,
  PlanVersionResultData,
  RuleReference,
} from './models'

export interface PlanPersistencePort {
  validatePlan(plan: PlanValidationDraft, ruleReference: RuleReference): Promise<PlanValidationData>
  createInitialPlan(candidateId: string): Promise<ActivePlanData>
  getActivePlan(): Promise<ActivePlanData | null>
  commitCandidate(
    request: CandidateCommitRequest,
    idempotencyKey: string,
  ): Promise<PlanVersionResultData>
  createPlanVersion(planId: string, request: CreatePlanVersionRequest): Promise<PlanVersionResultData>
  previewRebalance(
    planId: string,
    request: Omit<CreatePlanVersionRequest, 'warningConfirmationToken'>,
  ): Promise<PlanVersionResultData>
  listExerciseOptions?(planId: string, dayCode: string): Promise<readonly PlanExerciseOption[]>
  listPlanExerciseReplacements?(
    planId: string,
    dayCode: string,
    sourceExerciseCode: string,
  ): Promise<readonly PlanExerciseReplacementOption[]>
  listDayOptions?(planId: string): Promise<readonly PlanDayOption[]>
}
