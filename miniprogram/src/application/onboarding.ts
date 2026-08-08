import type {
  AiExplanationStatus,
  EquipmentItemRequest,
  ExercisePreference,
  ExperienceLevel,
  FitnessGoal,
  PlanCandidateGenerationData,
  SessionMinutes,
  TrainingLocation,
  UpdateEquipmentRequest,
  UpdatePreferencesRequest,
  UpdateProfileRequest,
  ValidationIssue,
  WeightStatus,
  AiPlanProposal,
  PlanGenerationContextData,
  PlanGenerationSource,
} from './models'
import type { AiPlanGenerator } from './cloudbaseAi'
import {
  AiPlanGenerationError,
  AiPlanUnavailableError,
} from './cloudbaseAi'
import { normalizeSafeTrainingPreference } from './trainingPreferenceSafety'

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

export interface OnboardingDraft {
  adultConfirmed: boolean
  safetyAccepted: boolean
  goal?: FitnessGoal
  experience?: ExperienceLevel
  weeklyFrequency?: number
  sessionMinutes?: SessionMinutes
  location?: TrainingLocation
  equipment: EquipmentItemRequest[]
  preferences: ExercisePreference[]
  preferencesTouched?: boolean
  additionalRequirements?: string
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
  generateCandidate(
    request: {
      profileVersion: number
      lockedFields?: Record<string, number>
      additionalRequirements?: string
      aiProposal?: AiPlanProposal
      fallbackAllowed?: boolean
    },
  ): Promise<PlanCandidateGenerationData>
}

export interface Session {
  accessToken: string
  refreshToken: string
  expiresAt: string
}

export type AppDestination = 'LOGIN' | 'ONBOARDING' | 'PLAN' | 'HOME' | 'WORKOUT_SESSION'

export interface StartupPorts {
  sessionStore: {
    load(): Promise<Session | null>
    save(session: Session): Promise<void>
    clear(): Promise<void>
  }
  wechatLogin: { getCode(): Promise<string> }
  auth: { login(code: string): Promise<Session> }
  workout: { hasActive(): Promise<boolean> }
  profile: { exists(): Promise<boolean> }
  plan: { hasActivePlan(): Promise<boolean> }
  navigation: { replace(destination: AppDestination): Promise<void> | void }
}

export interface CandidateExerciseViewModel {
  exerciseCode: string
  workSets: number
  repRange: string
  restLabel: string
  weightLabel: string
}

export interface CandidateViewModel {
  candidateId?: string
  status: 'READY' | 'NO_CANDIDATE'
  canContinue: boolean
  generationSource?: PlanGenerationSource
  generationLabel?: string
  explanationMessage: string
  notices: string[]
  days: Array<{
    code: string
    name: string
    exercises: CandidateExerciseViewModel[]
  }>
  reason?: string
  action?: {
    label: string
    route: 'ONBOARDING_EQUIPMENT'
  }
}

const allowedDurations: readonly number[] = [30, 45, 60, 75, 90]

export function createOnboardingState(): OnboardingState {
  return {
    stepIndex: 0,
    step: ONBOARDING_STEPS[0],
    draft: {
      adultConfirmed: false,
      safetyAccepted: false,
      equipment: [],
      preferences: [],
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

export async function saveProfileAndGenerateCandidate(
  port: OnboardingPersistencePort,
  draft: OnboardingDraft,
  aiGenerator?: AiPlanGenerator,
): Promise<PlanCandidateGenerationData> {
  const errors = validateOnboardingDraft(draft)
  if (errors.length > 0) {
    throw new Error(errors[0])
  }

  const [profileVersion, equipmentVersion, preferencesVersion] = await Promise.all([
    port.getProfileVersion(),
    port.getEquipmentVersion(),
    port.getPreferencesVersion(),
  ])

  const profile = await port.saveProfile({
    experience: draft.experience!,
    goal: draft.goal!,
    weeklyFrequency: draft.weeklyFrequency!,
    sessionMinutes: draft.sessionMinutes!,
    location: draft.location!,
    expectedVersion: profileVersion ?? 0,
  })
  await port.saveEquipment({
    items: draft.equipment,
    expectedVersion: equipmentVersion ?? 0,
  })
  if (preferencesVersion === null || draft.preferencesTouched) {
    await port.savePreferences({
      items: draft.preferences,
      expectedVersion: preferencesVersion ?? 0,
    })
  }

  const requirements = draft.additionalRequirements?.trim() ?? ''
  if (!aiGenerator || !port.getPlanGenerationContext) {
    return port.generateCandidate({ profileVersion: profile.version })
  }

  const context = await port.getPlanGenerationContext(profile.version)
  let firstProposal: AiPlanProposal
  try {
    firstProposal = await aiGenerator.generate(context, requirements)
  } catch (error) {
    if (!isAiFallbackError(error)) throw error
    return requestFallback(port, profile.version, requirements)
  }
  const firstResult = await port.generateCandidate({
    profileVersion: profile.version,
    additionalRequirements: requirements,
    aiProposal: firstProposal,
    fallbackAllowed: false,
  })
  if (firstResult.status === 'CANDIDATE_READY' && firstResult.candidate) {
    return firstResult
  }

  let repairedProposal: AiPlanProposal
  try {
    repairedProposal = await aiGenerator.generate(
      context,
      requirements,
      firstResult.validationIssues,
    )
  } catch (error) {
    if (!isAiFallbackError(error)) throw error
    return requestFallback(port, profile.version, requirements)
  }
  const repairedResult = await port.generateCandidate({
    profileVersion: profile.version,
    additionalRequirements: requirements,
    aiProposal: repairedProposal,
    fallbackAllowed: false,
  })
  if (repairedResult.status === 'CANDIDATE_READY' && repairedResult.candidate) {
    return repairedResult
  }

  return requestFallback(port, profile.version, requirements)
}

function requestFallback(
  port: OnboardingPersistencePort,
  profileVersion: number,
  additionalRequirements: string,
): Promise<PlanCandidateGenerationData> {
  return port.generateCandidate({
    profileVersion,
    additionalRequirements,
    fallbackAllowed: true,
  })
}

function isAiFallbackError(error: unknown): boolean {
  return error instanceof AiPlanGenerationError || error instanceof AiPlanUnavailableError
}

export function buildCandidateViewModel(
  data: PlanCandidateGenerationData,
): CandidateViewModel {
  if (data.status === 'NO_CANDIDATE' || !data.candidate) {
    const reasonCodes = data.validationIssues.map((issue) => issue.reasonCode)
    return {
      status: 'NO_CANDIDATE',
      canContinue: false,
      explanationMessage: '',
      notices: [],
      days: [],
      reason: candidateUnavailableReason(reasonCodes),
      action: {
        label: '返回调整器械与频率',
        route: 'ONBOARDING_EQUIPMENT',
      },
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
      : '基础保底计划 · AI 本次不可用',
    explanationMessage: explanationMessage(candidate.explanationStatus, candidate.explanation),
    notices: candidateNotices(candidate.validationIssues),
    days: candidate.plan.days.map((day) => ({
      code: day.code,
      name: day.name,
      exercises: day.exercises.map((exercise) => ({
        exerciseCode: exercise.exerciseCode,
        workSets: exercise.workSets,
        repRange: `${exercise.repMin}～${exercise.repMax} 次`,
        restLabel: `休息 ${exercise.restSeconds} 秒`,
        weightLabel: weightLabel(exercise.weightStatus),
      })),
    })),
  }
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
  if (reasonCodes.includes('NO_TEMPLATE_FOR_FREQUENCY')) {
    return '当前训练频率暂无可用模板，请调整每周训练天数后重试。'
  }
  if (reasonCodes.some((code) => (
    code === 'NO_ELIGIBLE_TEMPLATE'
    || code === 'EQUIPMENT_UNAVAILABLE'
    || code === 'EXERCISE_NOT_ELIGIBLE'
  ))) {
    return '当前器械或动作排除设置无法组成安全计划，请调整后重试。'
  }
  return '当前资料不足以生成安全候选，请调整器械或训练频率后重试。'
}

export function createStartupUseCases(ports: StartupPorts) {
  async function resolveDestination(): Promise<AppDestination> {
    if (await ports.workout.hasActive()) {
      return 'WORKOUT_SESSION'
    }
    if (!await ports.profile.exists()) {
      return 'ONBOARDING'
    }
    return await ports.plan.hasActivePlan() ? 'PLAN' : 'ONBOARDING'
  }

  async function navigate(destination: AppDestination): Promise<AppDestination> {
    await ports.navigation.replace(destination)
    return destination
  }

  return {
    async start(): Promise<AppDestination> {
      const session = await ports.sessionStore.load()
      if (!session) {
        return navigate('LOGIN')
      }
      return navigate(await resolveDestination())
    },

    async login(): Promise<AppDestination> {
      const code = await ports.wechatLogin.getCode()
      const session = await ports.auth.login(code)
      await ports.sessionStore.save(session)
      return navigate(await resolveDestination())
    },

    async authenticationExpired(): Promise<AppDestination> {
      await ports.sessionStore.clear()
      return navigate('LOGIN')
    },
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
      return [
        ...(draft.weeklyFrequency === undefined
          || !Number.isInteger(draft.weeklyFrequency)
          || draft.weeklyFrequency < 2
          || draft.weeklyFrequency > 6
          ? ['每周训练频率必须为 2～6 天']
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
      return 'AI 已结合你的资料与偏好生成计划，服务端规则校验已通过；详细解释仍在生成中。'
    case 'DEGRADED':
      return 'AI 本次不可用，已切换为规则保底计划；仍可继续编辑和确认。'
  }
}

function invalidAdditionalRequirements(value?: string): boolean {
  return normalizeSafeTrainingPreference(value) === null
}

function weightLabel(status: WeightStatus): string {
  switch (status) {
    case 'KNOWN':
      return '已采用现有校准重量'
    case 'NEEDS_CALIBRATION':
      return '需要在首次训练中校准重量'
    case 'BODYWEIGHT':
      return '自重动作'
  }
}

function kgLevels(...values: number[]): EquipmentItemRequest['availableLevels'] {
  return values.map((value) => ({ value, unit: 'KG' }))
}
