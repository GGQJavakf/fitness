import type {
  AiExplanationStatus,
  EquipmentItemRequest,
  ExercisePreference,
  ExperienceLevel,
  FitnessGoal,
  PlanCandidateGenerationData,
  SessionMinutes,
  TrainingLocation,
  TrainingSplit,
  UpdateEquipmentRequest,
  UpdatePreferencesRequest,
  UpdateProfileRequest,
  ValidationIssue,
  WeightStatus,
  AiPlanProposal,
  PlanGenerationContextData,
  PlanGenerationSource,
  PlanPresetSummary,
} from './models'
import {
  AiDiagnosticError,
  isOrdinaryAiFallbackError,
  type AiPlanGenerator,
} from './cloudbaseAi'
import { normalizeSafeTrainingPreference } from './trainingPreferenceSafety'

export { createStartupUseCases } from './startup'
export type { AppDestination, Session, StartupPorts } from './startup'

export const ONBOARDING_STEPS = [
  'SAFETY',
  'GOAL_AND_EXPERIENCE',
  'SCHEDULE',
  'LOCATION_AND_EQUIPMENT',
] as const

export const DEFAULT_GYM_EQUIPMENT: EquipmentItemRequest[] = [
  {
    clientEquipmentKey: '00000000-0000-4000-8000-000000000001',
    equipmentType: 'DUMBBELL',
    minIncrement: { value: 2.5, unit: 'KG' },
    availableLevels: kgLevels(2.5, 5, 7.5, 10),
  },
  {
    clientEquipmentKey: '00000000-0000-4000-8000-000000000002',
    equipmentType: 'BENCH',
    minIncrement: { value: 1, unit: 'KG' },
    availableLevels: kgLevels(1),
  },
  {
    clientEquipmentKey: '00000000-0000-4000-8000-000000000003',
    equipmentType: 'CABLE',
    minIncrement: { value: 2.5, unit: 'KG' },
    availableLevels: kgLevels(2.5, 5, 7.5, 10),
  },
  {
    clientEquipmentKey: '00000000-0000-4000-8000-000000000004',
    equipmentType: 'MACHINE',
    minIncrement: { value: 5, unit: 'KG' },
    availableLevels: kgLevels(5, 10, 15, 20),
  },
]

export type OnboardingStep = (typeof ONBOARDING_STEPS)[number]
export type OnboardingAdjustmentRoute = 'ONBOARDING_EQUIPMENT' | 'ONBOARDING_SCHEDULE'

export interface OnboardingDraft {
  adultConfirmed: boolean
  safetyAccepted: boolean
  goal?: FitnessGoal
  experience?: ExperienceLevel
  trainingSplit?: TrainingSplit
  weeklyFrequency?: number
  sessionMinutes?: SessionMinutes
  location?: TrainingLocation
  equipment: EquipmentItemRequest[]
  preferences: ExercisePreference[]
  preferencesTouched?: boolean
  additionalRequirements?: string
  aiConsentGranted?: boolean
}

export interface OnboardingState {
  stepIndex: number
  step: OnboardingStep
  draft: OnboardingDraft
  errors: string[]
}

export interface VersionedResource {
  version: number
}

export interface OnboardingPersistencePort {
  getProfileVersion(): Promise<number | null>
  getEquipmentVersion(): Promise<number | null>
  getPreferencesVersion(): Promise<number | null>
  saveProfile(request: UpdateProfileRequest): Promise<VersionedResource>
  saveEquipment(request: UpdateEquipmentRequest): Promise<VersionedResource>
  savePreferences(request: UpdatePreferencesRequest): Promise<VersionedResource>
  getPlanGenerationContext?(profileVersion: number): Promise<PlanGenerationContextData>
  listPlanPresets(): Promise<readonly PlanPresetSummary[]>
  generateCandidate(
    request: {
      profileVersion: number
      trainingSplit?: TrainingSplit
      lockedFields?: Record<string, number>
      additionalRequirements?: string
      aiProposal?: AiPlanProposal
      fallbackAllowed?: boolean
      presetCode?: string
    },
  ): Promise<PlanCandidateGenerationData>
}

export interface CandidateExerciseViewModel {
  exerciseCode: string
  workSets: number
  repRange: string
  restLabel: string
  weightLabel: string
  targetRirLabel?: string
  eccentricLabel?: string
  perSide?: boolean
  executionGroup?: string
  executionOrder?: number
  optionalSetDescription?: string
  notes: string[]
}

export interface CandidateViewModel {
  candidateId?: string
  status: 'READY' | 'NO_CANDIDATE'
  canContinue: boolean
  generationSource?: PlanGenerationSource
  generationLabel?: string
  name?: string
  trainingSplit?: TrainingSplit
  executionRules: string[]
  progressionRules: string[]
  explanationMessage: string
  notices: string[]
  days: Array<{
    code: string
    name: string
    weekday?: string
    focus?: string
    estimatedMinutesLabel?: string
    warmup: Array<{ instruction: string; prescription?: string; optional: boolean }>
    notes: string[]
    exercises: CandidateExerciseViewModel[]
  }>
  reason?: string
  action?: {
    label: string
    route: OnboardingAdjustmentRoute
  }
}

const allowedDurations: readonly number[] = [30, 45, 60, 75, 90]

const splitFrequencies: Readonly<Record<TrainingSplit, readonly number[]>> = {
  FULL_BODY: [2, 3],
  UPPER_LOWER: [2, 4],
  PUSH_PULL_LEGS: [3, 6],
  BODY_PART_FIVE_DAY: [5],
}

export function recommendedTrainingSplit(experience: ExperienceLevel): TrainingSplit {
  return ({
    BEGINNER: 'FULL_BODY',
    INTERMEDIATE: 'PUSH_PULL_LEGS',
    ADVANCED: 'BODY_PART_FIVE_DAY',
  } as const)[experience]
}

export function allowedFrequenciesForSplit(split: TrainingSplit): readonly number[] {
  return splitFrequencies[split]
}

export function defaultFrequencyForSplit(split: TrainingSplit): number {
  return splitFrequencies[split][0]
}

export function resolveTrainingSplit(draft: Pick<OnboardingDraft, 'trainingSplit' | 'weeklyFrequency' | 'experience'>): TrainingSplit {
  if (draft.trainingSplit) return draft.trainingSplit
  if (draft.experience === 'BEGINNER'
    && (draft.weeklyFrequency === 2 || draft.weeklyFrequency === 3)) return 'FULL_BODY'
  if (draft.weeklyFrequency === 2 || draft.weeklyFrequency === 4) return 'UPPER_LOWER'
  if (draft.weeklyFrequency === 3 || draft.weeklyFrequency === 6) return 'PUSH_PULL_LEGS'
  if (draft.weeklyFrequency === 5) return 'BODY_PART_FIVE_DAY'
  return recommendedTrainingSplit(draft.experience ?? 'BEGINNER')
}

export function planGenerationTrainingSplit(
  draft: Pick<OnboardingDraft, 'equipment' | 'location' | 'trainingSplit' | 'weeklyFrequency' | 'experience'>,
): TrainingSplit | undefined {
  if (draft.location === 'HOME' || draft.equipment.length === 0) return undefined
  return resolveTrainingSplit(draft)
}

export function createOnboardingState(): OnboardingState {
  return {
    stepIndex: 0,
    step: ONBOARDING_STEPS[0],
    draft: {
      adultConfirmed: false,
      safetyAccepted: false,
      equipment: [],
      preferences: [],
      aiConsentGranted: false,
    },
    errors: [],
  }
}

export function updateOnboardingDraft(
  state: OnboardingState,
  patch: Partial<OnboardingDraft>,
): OnboardingState {
  return {
    ...state,
    draft: { ...state.draft, ...patch },
    errors: [],
  }
}

export function advanceOnboarding(state: OnboardingState): OnboardingState {
  const errors = validateStep(state.step, state.draft)
  if (errors.length > 0 || state.stepIndex === ONBOARDING_STEPS.length - 1) {
    return { ...state, errors }
  }

  const stepIndex = state.stepIndex + 1
  return {
    ...state,
    stepIndex,
    step: ONBOARDING_STEPS[stepIndex],
    errors: [],
  }
}

export function previousOnboardingStep(state: OnboardingState): OnboardingState {
  const stepIndex = Math.max(0, state.stepIndex - 1)
  return {
    ...state,
    stepIndex,
    step: ONBOARDING_STEPS[stepIndex],
    errors: [],
  }
}

export function goToOnboardingStep(
  state: OnboardingState,
  step: OnboardingStep,
): OnboardingState {
  return {
    ...state,
    stepIndex: ONBOARDING_STEPS.indexOf(step),
    step,
    errors: [],
  }
}

export function validateOnboardingDraft(draft: OnboardingDraft): string[] {
  return ONBOARDING_STEPS.flatMap((step) => validateStep(step, draft))
}

export interface UserStateOperationGuard {
  assertCurrent(): void
}

export async function saveProfileAndGenerateCandidate(
  port: OnboardingPersistencePort,
  draft: OnboardingDraft,
  aiGenerator?: AiPlanGenerator,
  userState?: UserStateOperationGuard,
): Promise<PlanCandidateGenerationData> {
  userState?.assertCurrent()
  const errors = validateOnboardingDraft(draft)
  if (errors.length > 0) {
    throw new Error(errors[0])
  }

  const [profileVersion, equipmentVersion, preferencesVersion] = await runUserStateOperation(
    userState,
    () => Promise.all([
      port.getProfileVersion(),
      port.getEquipmentVersion(),
      port.getPreferencesVersion(),
    ]),
  )

  const profile = await runUserStateOperation(userState, () => port.saveProfile({
      experience: draft.experience!,
      goal: draft.goal!,
      weeklyFrequency: draft.weeklyFrequency!,
      sessionMinutes: draft.sessionMinutes!,
      location: draft.location!,
      expectedVersion: profileVersion ?? 0,
    }))
  await runUserStateOperation(userState, () => port.saveEquipment({
      items: draft.equipment,
      expectedVersion: equipmentVersion ?? 0,
    }))
  if (preferencesVersion === null || draft.preferencesTouched) {
    await runUserStateOperation(userState, () => port.savePreferences({
        items: draft.preferences,
        expectedVersion: preferencesVersion ?? 0,
      }))
  }

  const additionalRequirements = draft.additionalRequirements?.trim() ?? ''
  const trainingSplit = planGenerationTrainingSplit(draft)
  if (draft.aiConsentGranted !== true) {
    return runUserStateOperation(
      userState,
      () => requestFallback(port, profile.version, trainingSplit, additionalRequirements),
    )
  }
  if (!aiGenerator || !port.getPlanGenerationContext) {
    throw new AiDiagnosticError(
      'CONFIGURATION',
      'AI_PLAN_GENERATOR_UNAVAILABLE',
      'AI plan generation is not configured',
    )
  }

  try {
    const loadedContext = await runUserStateOperation(
      userState,
      () => port.getPlanGenerationContext!(profile.version),
    )
    const context: PlanGenerationContextData = {
      ...loadedContext,
      profile: { ...loadedContext.profile, trainingSplit },
    }
    let repairIssues: ValidationIssue[] | undefined
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const aiProposal = await runUserStateOperation(
        userState,
        () => aiGenerator.generate(context, {
          consentGranted: true,
          repairIssues,
        }),
      )
      const generated = await runUserStateOperation(
        userState,
        () => port.generateCandidate({
          profileVersion: profile.version,
          ...(trainingSplit ? { trainingSplit } : {}),
          additionalRequirements,
          aiProposal,
          fallbackAllowed: false,
        }),
      )
      if (generated.status === 'CANDIDATE_READY' && generated.candidate) return generated
      if (attempt === 1) return generated
      repairIssues = generated.validationIssues
    }
    throw new AiDiagnosticError(
      'CONTRACT',
      'AI_PLAN_REPAIR_EXHAUSTED',
      'AI plan repair attempts were exhausted without a validation result',
    )
  } catch (error) {
    userState?.assertCurrent()
    if (isOrdinaryAiFallbackError(error)) {
      return runUserStateOperation(
        userState,
        () => requestFallback(port, profile.version, trainingSplit, additionalRequirements),
      )
    }
    throw error
  }
}

async function runUserStateOperation<T>(
  userState: UserStateOperationGuard | undefined,
  operation: () => Promise<T>,
): Promise<T> {
  userState?.assertCurrent()
  try {
    const result = await operation()
    userState?.assertCurrent()
    return result
  } catch (error) {
    userState?.assertCurrent()
    throw error
  }
}

function requestFallback(
  port: OnboardingPersistencePort,
  profileVersion: number,
  trainingSplit: TrainingSplit | undefined,
  additionalRequirements: string,
): Promise<PlanCandidateGenerationData> {
  return port.generateCandidate({
    profileVersion,
    ...(trainingSplit ? { trainingSplit } : {}),
    additionalRequirements,
    fallbackAllowed: true,
  })
}

export function buildCandidateViewModel(
  data: PlanCandidateGenerationData,
): CandidateViewModel {
  if (data.status === 'NO_CANDIDATE' || !data.candidate) {
    const reasonCodes = data.validationIssues.map((issue) => issue.reasonCode)
    return {
      status: 'NO_CANDIDATE',
      canContinue: false,
      executionRules: [],
      progressionRules: [],
      explanationMessage: '',
      notices: [],
      days: [],
      reason: candidateUnavailableReason(reasonCodes),
      action: candidateUnavailableAction(reasonCodes),
    }
  }

  const { candidate } = data
  const generationSource = candidate.generationSource
    ?? (candidate.plan.templateCode === 'AI_PERSONALIZED'
      ? 'AI_PERSONALIZED'
      : 'FALLBACK_RULE_PLAN')
  return {
    candidateId: candidate.candidateId,
    status: 'READY',
    canContinue: true,
    generationSource,
    generationLabel: generationSource === 'AI_PERSONALIZED'
      ? 'AI 个性化计划 · 规则已校验'
      : generationSource === 'SYSTEM_PRESET'
        ? '系统个人预设 · 完整处方已载入'
        : '规则生成计划 · 已通过安全校验',
    name: candidate.plan.name,
    trainingSplit: candidate.plan.trainingSplit,
    executionRules: [...(candidate.plan.executionRules ?? [])],
    progressionRules: [...(candidate.plan.progressionRules ?? [])],
    explanationMessage: explanationMessage(candidate.explanationStatus, candidate.explanation),
    notices: candidateNotices(candidate.validationIssues),
    days: candidate.plan.days.map((day) => ({
      code: day.code,
      name: day.name,
      weekday: day.weekday,
      focus: day.focus,
      estimatedMinutesLabel: day.estimatedMinutesMin && day.estimatedMinutesMax
        ? `${day.estimatedMinutesMin}～${day.estimatedMinutesMax} 分钟`
        : undefined,
      warmup: day.warmup ?? [],
      notes: day.notes ?? [],
      exercises: day.exercises.map((exercise) => ({
        exerciseCode: exercise.exerciseCode,
        workSets: exercise.workSets,
        repRange: `${exercise.repMin}～${exercise.repMax} 次`,
        restLabel: `休息 ${exercise.restSeconds} 秒`,
        weightLabel: weightLabel(exercise.weightStatus),
        targetRirLabel: exercise.targetRirMin !== undefined && exercise.targetRirMax !== undefined
          ? `RIR ${exercise.targetRirMin}～${exercise.targetRirMax}`
          : undefined,
        eccentricLabel: exercise.eccentricSeconds
          ? `下放约 ${exercise.eccentricSeconds} 秒`
          : undefined,
        perSide: exercise.perSide,
        executionGroup: exercise.executionGroup,
        executionOrder: exercise.executionOrder,
        optionalSetDescription: optionalSetDescription(exercise.optionalSetRule),
        notes: exercise.notes ?? [],
      })),
    })),
  }
}

function optionalSetDescription(rule: { conditionCode: string; description?: string } | undefined): string | undefined {
  if (!rule) return undefined
  if (rule.description) return rule.description
  if (rule.conditionCode === 'TUESDAY_UNDER_42_GOOD_STATE') {
    return '当天用时在 42 分钟以内且状态良好，可在坐姿划船或哑铃弯举中任选一项增加 1 组；不要两项都加。'
  }
  return '满足当天条件时可增加 1 个补充组。'
}

function candidateNotices(issues: ValidationIssue[]): string[] {
  return [...new Set(issues
    .filter((issue) => issue.severity === 'WARNING')
    .map((issue) => {
      if (issue.reasonCode === 'INITIAL_WEIGHT_NEEDS_CALIBRATION') return ''
      if (issue.reasonCode === 'RECOVERY_WINDOW_TOO_SHORT') {
        return '部分相邻训练日涉及相同主肌群，请在计划编辑中调整安排或确保充分恢复。'
      }
      return '计划包含需要留意的规则提示，进入编辑器可查看具体位置。'
    })
    .filter(Boolean))]
}

function candidateUnavailableReason(reasonCodes: string[]): string {
  if (reasonCodes.includes('PRESET_PROFILE_MISMATCH')) {
    return '该系统预设与当前档案不匹配，请按预设标注调整训练经验、目标、每周训练天数、场地和单次时长后重试。'
  }
  if (reasonCodes.includes('RECOVERY_WINDOW_TOO_SHORT')) {
    return '当前每周训练频率会让相同主肌群恢复不足，请降低训练频率后重试。'
  }
  if (reasonCodes.includes('NO_TEMPLATE_FOR_FREQUENCY')) {
    return '当前训练频率暂无可用模板，请调整每周训练天数后重试。'
  }
  if (reasonCodes.includes('SPLIT_FREQUENCY_MISMATCH')) {
    return '当前训练分化与每周训练天数不匹配，请重新选择分化或训练频率。'
  }
  if (reasonCodes.includes('INSUFFICIENT_ELIGIBLE_EXERCISES')) {
    return '当前可用器械对应的安全动作不足以组成完整训练，请补充器械或调整训练条件后重试。'
  }
  if (reasonCodes.some((code) => (
    code === 'NO_ELIGIBLE_TEMPLATE'
    || code === 'EQUIPMENT_UNAVAILABLE'
    || code === 'EXERCISE_NOT_ELIGIBLE'
  ))) {
    return '当前场地、器械和训练频率暂无可执行的安全计划，请调整训练条件后重试。'
  }
  return '当前资料不足以生成安全候选，请调整器械或训练频率后重试。'
}

function candidateUnavailableAction(reasonCodes: string[]): CandidateViewModel['action'] {
  if (reasonCodes.some((code) => (
    code === 'RECOVERY_WINDOW_TOO_SHORT'
    || code === 'NO_TEMPLATE_FOR_FREQUENCY'
    || code === 'SPLIT_FREQUENCY_MISMATCH'
  ))) {
    return {
      label: '返回调整训练频率',
      route: 'ONBOARDING_SCHEDULE',
    }
  }
  return {
    label: '返回调整训练条件',
    route: 'ONBOARDING_EQUIPMENT',
  }
}

function validateStep(step: OnboardingStep, draft: OnboardingDraft): string[] {
  switch (step) {
    case 'SAFETY':
      return [
        ...(!draft.adultConfirmed ? ['仅支持已满 18 周岁的成年用户'] : []),
        ...(!draft.safetyAccepted ? ['请确认本产品不提供医疗诊断或康复处方'] : []),
      ]
    case 'GOAL_AND_EXPERIENCE':
      return [
        ...(!draft.goal ? ['请选择训练目标'] : []),
        ...(!draft.experience ? ['请选择训练经验'] : []),
      ]
    case 'SCHEDULE':
      const split = resolveTrainingSplit(draft)
      return [
        ...(draft.weeklyFrequency === undefined
          || !Number.isInteger(draft.weeklyFrequency)
          || draft.weeklyFrequency < 2
          || draft.weeklyFrequency > 6
          ? ['每周训练频率必须为 2～6 天']
          : []),
        ...(draft.weeklyFrequency !== undefined
          && Number.isInteger(draft.weeklyFrequency)
          && draft.weeklyFrequency >= 2
          && draft.weeklyFrequency <= 6
          && !allowedFrequenciesForSplit(split).includes(draft.weeklyFrequency)
          ? ['当前分化与每周训练天数不匹配，请重新选择']
          : []),
        ...(draft.sessionMinutes === undefined || !allowedDurations.includes(draft.sessionMinutes)
          ? ['单次训练时长只能选择 30/45/60/75/90 分钟']
          : []),
      ]
    case 'LOCATION_AND_EQUIPMENT':
      return [
        ...(!draft.location ? ['请选择训练场地'] : []),
        ...(draft.equipment.some((item) => (
          item.minIncrement.unit !== 'KG'
          || item.availableLevels.some((weight) => weight.unit !== 'KG')
        ))
          ? ['P0 仅支持 KG，不支持 LB 或隐式换算']
          : []),
        ...(invalidAdditionalRequirements(draft.additionalRequirements)
          ? ['额外需求仅支持 300 字以内的非医疗训练偏好，请删除提示词控制、疼痛、诊断或康复内容']
          : []),
      ]
  }
}

function explanationMessage(
  status: AiExplanationStatus,
  explanation: string,
): string {
  switch (status) {
    case 'READY':
      return explanation || '候选计划已由规则引擎生成。'
    case 'PENDING':
      return explanation || '候选计划已通过服务端规则校验；详细说明仍在生成中。'
    case 'DEGRADED':
      return explanation || '候选计划已通过规则校验；详细说明暂不可用，不影响继续编辑和确认。'
  }
}

function invalidAdditionalRequirements(value?: string): boolean {
  return normalizeSafeTrainingPreference(value) === null
}

function weightLabel(status: WeightStatus): string {
  switch (status) {
    case 'KNOWN':
      return '训练时自动使用最近重量'
    case 'NEEDS_CALIBRATION':
      return '训练时自动设置起始重量'
    case 'BODYWEIGHT':
      return '自重动作'
  }
}

function kgLevels(...values: number[]): EquipmentItemRequest['availableLevels'] {
  return values.map((value) => ({ value, unit: 'KG' }))
}
