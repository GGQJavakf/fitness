export type ExperienceLevel = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
export type TrainingSplit = 'FULL_BODY' | 'UPPER_LOWER' | 'PUSH_PULL_LEGS' | 'BODY_PART_FIVE_DAY'
export type FitnessGoal = 'STRENGTH' | 'HYPERTROPHY' | 'FAT_LOSS' | 'GENERAL_FITNESS'
export type TrainingLocation = 'HOME' | 'GYM' | 'OTHER'
export type SessionMinutes = 30 | 45 | 60 | 75 | 90
export type TrainingWeekday = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'
export type WeightUnit = 'KG'
export type WeightStatus = 'KNOWN' | 'NEEDS_CALIBRATION' | 'BODYWEIGHT'
export type LockStatus = 'USER_LOCKED' | 'RULE_LOCKED' | 'UNLOCKED'
export type LockCommandStatus = 'USER_LOCKED' | 'UNLOCKED'
export type AiExplanationStatus = 'READY' | 'PENDING' | 'DEGRADED'
export type PlanGenerationSource = 'AI_PERSONALIZED' | 'FALLBACK_RULE_PLAN' | 'SYSTEM_PRESET'
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
  targetRirMin?: number
  targetRirMax?: number
  eccentricSeconds?: number
  perSide?: boolean
  executionGroup?: string
  executionOrder?: number
  optionalSetRule?: PlanOptionalSetRule
  notes?: string[]
}

export interface PlanOptionalSetRule {
  conditionCode: string
  exclusiveChoiceGroup: string
  additionalSets: 1
  description?: string
}

export interface PlanWarmupStep {
  instruction: string
  prescription?: string
  optional: boolean
}

export interface PlanExerciseOption extends PlanExercise {
  name: string
  movementPattern?: string
  primaryMuscles?: string[]
  equipment?: string[]
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
  weekday?: TrainingWeekday
  focus?: string
  estimatedMinutesMin?: number
  estimatedMinutesMax?: number
  warmup?: PlanWarmupStep[]
  notes?: string[]
  exercises: PlanExercise[]
}

export interface PlanDraft {
  templateCode: string
  trainingSplit?: TrainingSplit
  name: string
  presetCode?: string
  presetVersion?: string
  executionRules?: string[]
  progressionRules?: string[]
  movementImpactConstraint?: 'NO_JUMP'
  days: PlanDay[]
  locks: Record<string, LockStatus>
}

export interface PlanValidationDraft {
  templateCode: string
  trainingSplit?: TrainingSplit
  name: string
  presetCode?: string
  presetVersion?: string
  executionRules?: string[]
  progressionRules?: string[]
  movementImpactConstraint?: 'NO_JUMP'
  days: PlanDay[]
}

export interface PlanPresetDaySummary {
  weekday: TrainingWeekday
  name: string
  focus: string
  estimatedMinutesMin: number
  estimatedMinutesMax: number
  exerciseCount: number
}

export type PlanPresetContentStatus =
  | 'AI_DRAFT'
  | 'AI_VALIDATED'
  | 'PUBLIC_RELEASE_APPROVED'
  | 'RETIRED'

export type PlanPresetProfessionalReviewStatus = 'PENDING' | 'APPROVED'

export type PlanPresetAvailabilityStatus = 'AVAILABLE' | 'BLOCKED_CAPABILITY'

export interface PlanPresetIntroductoryPhase {
  weeks: number
  workSets: number
  targetRirMin: number
  targetRirMax: number
  transitionCondition: string
}

export type PlanPresetSourceKind =
  | 'PEER_REVIEWED_POSITION_STAND'
  | 'PEER_REVIEWED_CONSENSUS_STATEMENT'
  | 'PROFESSIONAL_ORGANIZATION_SUMMARY'
  | 'GOVERNMENT_GUIDELINE'
  | 'GOVERNMENT_PUBLIC_HEALTH_GUIDANCE'
  | 'INTERNAL_USER_PLAN'

export interface PlanPresetSourceSummary {
  id: string
  title: string
  url?: string
  usageBoundary: string
  sourceKind: PlanPresetSourceKind
}

export interface PlanPresetSummary {
  code: string
  version: string
  name: string
  experience: ExperienceLevel
  goal: FitnessGoal
  weeklyFrequency: number
  sessionMinutes: number
  location: TrainingLocation
  contentStatus: PlanPresetContentStatus
  professionalReviewStatus: PlanPresetProfessionalReviewStatus
  availabilityStatus: PlanPresetAvailabilityStatus
  unavailableReason?: string
  introductoryPhase?: PlanPresetIntroductoryPhase
  sources: PlanPresetSourceSummary[]
  explanationSources: PlanPresetSourceSummary[]
  matchStatus: 'EXACT' | 'PARTIAL'
  recommended: boolean
  mismatchFields: Array<
    'EXPERIENCE' | 'GOAL' | 'WEEKLY_FREQUENCY' | 'SESSION_MINUTES' | 'LOCATION'
  >
  days: PlanPresetDaySummary[]
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

export interface CandidateCommitRequest {
  candidateId: string
  expectedActiveVersionNumber: number
  plan: PlanValidationDraft
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
