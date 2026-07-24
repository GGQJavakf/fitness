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
  WeightStatus,
} from './models'

export const ONBOARDING_STEPS = [
  'SAFETY',
  'GOAL_AND_EXPERIENCE',
  'SCHEDULE',
  'EQUIPMENT',
  'PREFERENCES',
  'REVIEW',
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
  generateCandidate(
    request: { profileVersion: number; lockedFields?: Record<string, number> },
  ): Promise<PlanCandidateGenerationData>
}

export interface Session {
  accessToken: string
  refreshToken: string
  expiresAt: string
}

export type AppDestination = 'LOGIN' | 'ONBOARDING' | 'PLAN' | 'HOME'

export interface StartupPorts {
  sessionStore: {
    load(): Promise<Session | null>
    save(session: Session): Promise<void>
    clear(): Promise<void>
  }
  wechatLogin: { getCode(): Promise<string> }
  auth: { login(code: string): Promise<Session> }
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
  explanationMessage: string
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

export function validateOnboardingDraft(draft: OnboardingDraft): string[] {
  return ONBOARDING_STEPS.flatMap((step) => validateStep(step, draft))
}

export async function saveProfileAndGenerateCandidate(
  port: OnboardingPersistencePort,
  draft: OnboardingDraft,
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
  await port.savePreferences({
    items: draft.preferences,
    expectedVersion: preferencesVersion ?? 0,
  })

  return port.generateCandidate({ profileVersion: profile.version })
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
      days: [],
      reason: reasonCodes.length > 0
        ? `暂未生成候选：${reasonCodes.join('、')}`
        : '当前资料不足以生成安全候选，请调整器械或训练频率后重试。',
      action: {
        label: '返回调整器械与频率',
        route: 'ONBOARDING_EQUIPMENT',
      },
    }
  }

  const { candidate } = data
  return {
    candidateId: candidate.candidateId,
    status: 'READY',
    canContinue: true,
    explanationMessage: explanationMessage(candidate.explanationStatus, candidate.explanation),
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

export function createStartupUseCases(ports: StartupPorts) {
  async function resolveDestination(): Promise<AppDestination> {
    if (!await ports.profile.exists()) {
      return 'ONBOARDING'
    }
    return await ports.plan.hasActivePlan() ? 'PLAN' : 'HOME'
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
        ...(!draft.location ? ['请选择训练场地'] : []),
      ]
    case 'EQUIPMENT':
      return draft.equipment.some((item) => (
        item.minIncrement.unit !== 'KG'
        || item.availableLevels.some((weight) => weight.unit !== 'KG')
      ))
        ? ['P0 仅支持 KG，不支持 LB 或隐式换算']
        : []
    case 'PREFERENCES':
      return []
    case 'REVIEW':
      return []
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
      return '计划已可用，AI 解释仍在生成中。'
    case 'DEGRADED':
      return '计划已由规则引擎生成；AI 解释暂不可用，不影响继续编辑和确认。'
  }
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
