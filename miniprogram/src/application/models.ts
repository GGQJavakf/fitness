export type ExperienceLevel = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
export type TrainingSplit = 'UPPER_LOWER' | 'PUSH_PULL_LEGS' | 'BODY_PART_FIVE_DAY'
export type FitnessGoal = 'STRENGTH' | 'HYPERTROPHY' | 'FAT_LOSS' | 'GENERAL_FITNESS'
export type TrainingLocation = 'HOME' | 'GYM' | 'OTHER'
export type SessionMinutes = 30 | 45 | 60 | 75 | 90
export type WeightUnit = 'KG'
export type WeightStatus = 'KNOWN' | 'NEEDS_CALIBRATION' | 'BODYWEIGHT'
export type LockStatus = 'USER_LOCKED' | 'RULE_LOCKED' | 'UNLOCKED'
export type LockCommandStatus = 'USER_LOCKED' | 'UNLOCKED'
export type AiExplanationStatus = 'READY' | 'PENDING' | 'DEGRADED'
export type PlanGenerationSource = 'AI_PERSONALIZED' | 'FALLBACK_RULE_PLAN'
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

export interface PlanExerciseReplacementOption extends PlanExerciseOption {
  movementPattern: string
  primaryMuscles: string[]
  equipment: string[]
  matchReason: 'SAME_PATTERN_MUSCLES_DIFFICULTY'
}

export interface PlanDayOption {
  code: string
  name: string
  exercises: PlanExerciseOption[]
}

export interface PlanDay {
  code: string
  name: string
  exercises: PlanExercise[]
}

export interface PlanDraft {
  templateCode: string
  trainingSplit?: TrainingSplit
  name: string
  days: PlanDay[]
  locks: Record<string, LockStatus>
}

export interface PlanValidationDraft {
  templateCode: string
  trainingSplit?: TrainingSplit
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
  generationSource?: PlanGenerationSource
  plan: PlanDraft
  validationIssues: ValidationIssue[]
  ruleReference: RuleReference
  lockedFieldOutcomes: Record<string, LockStatus>
  explanationStatus: AiExplanationStatus
  explanation: string
  expiresAt: string
}

export interface AiPlanProposalExercise {
  exerciseCode: string
  workSets: number
  repMin: number
  repMax: number
  restSeconds: number
}

export interface AiPlanProposalDay {
  code: string
  name: string
  exercises: AiPlanProposalExercise[]
}

export interface AiPlanProposal {
  name: string
  days: AiPlanProposalDay[]
}

export interface PlanGenerationContextData {
  profile: {
    experience: ExperienceLevel
    trainingSplit?: TrainingSplit
    goal: FitnessGoal
    weeklyFrequency: number
    sessionMinutes: SessionMinutes
    location: TrainingLocation
    profileVersion: number
  }
  exercises: Array<{
    code: string
    name: string
    movementPattern: string
    difficulty: string
    equipment: string[]
    primaryMuscles: string[]
    preferred: boolean
    bodyweight: boolean
  }>
  constraints: {
    minimumSessionsPerWeek: number
    maximumSessionsPerWeek: number
    maximumExercisesPerSession: number
    minimumWorkSets: number
    maximumWorkSets: number
    minimumReps: number
    maximumReps: number
    minimumRestSeconds: number
    maximumRestSeconds: number
    secondsPerWorkSet: number
    secondsPerExerciseTransition: number
    maximumMovementPatternOccurrencesPerSession: number
    maximumWorkSetsPerPrimaryMusclePerSession: number
    minimumRecoveryHoursBetweenPrimaryMuscleSessions: number
  }
  ruleReference: RuleReference
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
