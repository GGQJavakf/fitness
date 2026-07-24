export type ExperienceLevel = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
export type FitnessGoal = 'STRENGTH' | 'HYPERTROPHY' | 'GENERAL_FITNESS'
export type TrainingLocation = 'HOME' | 'GYM' | 'OTHER'
export type SessionMinutes = 30 | 45 | 60 | 75 | 90
export type WeightUnit = 'KG'
export type WeightStatus = 'KNOWN' | 'NEEDS_CALIBRATION' | 'BODYWEIGHT'
export type LockStatus = 'USER_LOCKED' | 'RULE_LOCKED' | 'UNLOCKED'
export type LockCommandStatus = 'USER_LOCKED' | 'UNLOCKED'
export type AiExplanationStatus = 'READY' | 'PENDING' | 'DEGRADED'
export type ValidationSeverity = 'INFO' | 'WARNING' | 'ERROR'

export interface EquipmentWeightInput {
  value: number
  unit: WeightUnit
}

export interface EquipmentItemRequest {
  clientEquipmentKey: string
  equipmentType: string
  minIncrement: EquipmentWeightInput
  availableLevels: EquipmentWeightInput[]
}

export interface ExercisePreference {
  exerciseId: string
  preferenceType: 'PREFERRED' | 'EXCLUDED'
}

export interface UpdateProfileRequest {
  experience: ExperienceLevel
  goal: FitnessGoal
  weeklyFrequency: number
  sessionMinutes: SessionMinutes
  location: TrainingLocation
  expectedVersion: number
}

export interface UpdateEquipmentRequest {
  items: EquipmentItemRequest[]
  expectedVersion: number
}

export interface UpdatePreferencesRequest {
  items: ExercisePreference[]
  expectedVersion: number
}

export interface PlanExercise {
  exerciseCode: string
  workSets: number
  repMin: number
  repMax: number
  restSeconds: number
  weightStatus: WeightStatus
  targetWeightKg?: number
}

export interface PlanExerciseOption extends PlanExercise {
  name: string
}

export interface PlanDay {
  code: string
  name: string
  exercises: PlanExercise[]
}

export interface PlanDraft {
  templateCode: string
  name: string
  days: PlanDay[]
  locks: Record<string, LockStatus>
}

export interface PlanValidationDraft {
  templateCode: string
  name: string
  days: PlanDay[]
}

export interface ValidationIssue {
  severity: ValidationSeverity
  reasonCode: string
  fieldPath: string
  parameters?: Record<string, unknown>
}

export interface RuleReference {
  ruleVersion: string
  templateVersion: string
  contentVersion: string
}

export interface PlanCandidate {
  candidateId: string
  plan: PlanDraft
  validationIssues: ValidationIssue[]
  ruleReference: RuleReference
  lockedFieldOutcomes: Record<string, LockStatus>
  explanationStatus: AiExplanationStatus
  explanation: string
  expiresAt: string
}

export interface PlanCandidateGenerationData {
  status: 'CANDIDATE_READY' | 'NO_CANDIDATE'
  candidate?: PlanCandidate
  validationIssues: ValidationIssue[]
  lockedFieldOutcomes: Record<string, LockStatus>
}

export interface PlanValidationData {
  valid: boolean
  validationIssues: ValidationIssue[]
}

export interface PlanVersionData {
  id: string
  planId: string
  versionNumber: number
  sourceType: 'INITIAL' | 'USER_EDIT' | 'REBALANCE' | 'PROGRESSION'
  plan: PlanDraft
  ruleReference: RuleReference
  confirmedWarningCodes: string[]
  createdAt: string
}

export interface ActivePlanData {
  planId: string
  activeVersion: PlanVersionData
}

export interface CreatePlanVersionRequest {
  plan: PlanValidationDraft
  baseVersionNumber: number
  locks: Record<string, LockCommandStatus>
  warningConfirmationToken?: string
}

export interface PlanVersionResultData {
  status: 'PREVIEW' | 'WARNING_CONFIRMATION_REQUIRED' | 'VALIDATION_ERROR' | 'CREATED'
  plan: PlanDraft
  validationIssues: ValidationIssue[]
  warningConfirmationToken?: string
  version?: PlanVersionData
}
