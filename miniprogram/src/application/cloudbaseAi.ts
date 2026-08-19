import type { AiGeneratedContent, AiStructuredSummary } from './ai'
import type {
  AiPlanProposal,
  PlanGenerationContextData,
  ValidationIssue,
} from './models'
import { normalizeSafeTrainingPreference } from './trainingPreferenceSafety'

export const AI_APPROVED_PURPOSES = [
  'PLAN_EXPLANATION',
  'WORKOUT_SUMMARY',
  'ALTERNATIVE_RANKING',
  'PLAN_GENERATION',
] as const

export type AiGenerationPurpose = (typeof AI_APPROVED_PURPOSES)[number]
export type AiContentGenerationPurpose = Exclude<AiGenerationPurpose, 'PLAN_GENERATION'>

export interface AiTextGenerationRequest {
  purpose: AiGenerationPurpose
  systemPrompt: string
  factsJson: string
  explicitUserConsent?: boolean
}

export interface AiTextGenerationPort {
  generate(request: AiTextGenerationRequest): Promise<string>
}

export interface AiConsentPort {
  hasConsent(purpose: AiGenerationPurpose): Promise<boolean>
}

export const DEFAULT_DENY_AI_CONSENT_PORT: AiConsentPort = {
  hasConsent: async () => false,
}

export type AiDiagnosticCategory =
  | 'CONFIGURATION'
  | 'ELIGIBILITY'
  | 'SDK'
  | 'CONTRACT'
  | 'UNSAFE_OUTPUT'
  | 'TRANSIENT'
  | 'TIMEOUT'
  | 'THROTTLING'

export class AiDiagnosticError extends Error {
  constructor(
    readonly category: AiDiagnosticCategory,
    readonly code: string,
    message = code,
  ) {
    super(message)
    this.name = 'AiDiagnosticError'
  }
}

export function isOrdinaryAiFallbackError(error: unknown): boolean {
  return error instanceof AiDiagnosticError
    && (error.category === 'TIMEOUT'
      || error.category === 'THROTTLING'
      || error.category === 'TRANSIENT')
}

export function isApprovedAiPurpose(value: unknown): value is AiGenerationPurpose {
  return typeof value === 'string'
    && (AI_APPROVED_PURPOSES as readonly string[]).includes(value)
}

export interface ValidatedAiContentGenerator {
  generate(
    purpose: AiContentGenerationPurpose,
    facts: Record<string, unknown>,
    fallback: () => Promise<AiGeneratedContent>,
  ): Promise<AiGeneratedContent>
}

export interface AiPlanGenerationOptions {
  consentGranted: boolean
  repairIssues?: ValidationIssue[]
}

export interface AiPlanGenerator {
  generate(
    context: PlanGenerationContextData,
    options: AiPlanGenerationOptions,
  ): Promise<AiPlanProposal>
}

export class AiPlanGenerationError extends AiDiagnosticError {
  constructor(message: string) {
    super('UNSAFE_OUTPUT', 'AI_PROPOSAL_INVALID', message)
    this.name = 'AiPlanGenerationError'
  }
}

export class AiPlanUnavailableError extends AiDiagnosticError {
  constructor(message = 'AI provider is temporarily unavailable') {
    super('TRANSIENT', 'AI_PROVIDER_UNAVAILABLE', message)
    this.name = 'AiPlanUnavailableError'
  }
}

const SUMMARY_FIELDS = [
  'explanation',
  'highlights',
  'issues',
  'nextActions',
  'safetyNotice',
  'summary',
] as const
const NUMBER_PATTERN = /[+-]?\d{1,6}(?:\.\d{1,3})?/g
const NON_ARABIC_NUMBER_PATTERN = /[０-９零〇一二两三四五六七八九十百千万亿]/
const UNSAFE_MARKERS = [
  '忽略之前',
  '忽略以上',
  '忽略系统',
  '系统提示词',
  '开发者消息',
  'ignore previous',
  'system prompt',
  'api key',
  'access token',
  '医疗诊断',
  '医疗建议',
  '康复',
  '康复处方',
  '处方',
  '诊断',
  '治疗',
  '疾病',
  '医生',
  '疼痛',
  '受伤',
  '损伤',
]

type ProfessionalSessionFocus = 'FULL_BODY' | 'UPPER' | 'LOWER' | 'PUSH' | 'PULL'
  | 'CHEST' | 'BACK' | 'ARMS' | 'SHOULDERS'

interface ProfessionalSessionBlueprint {
  allowedMovementPatterns: readonly string[]
  requiredMovementPatterns: readonly string[]
  recommendedMovementPatternGroups?: readonly (readonly string[])[]
}

interface WeeklyMovementPatternSessionTarget {
  movementPattern: string
  minimumSessions: number
  maximumSessions: number
}

const FULL_BODY_WEEKLY_MOVEMENT_TARGETS: Partial<
  Record<number, readonly WeeklyMovementPatternSessionTarget[]>
> = {
  2: [
    { movementPattern: 'SQUAT', minimumSessions: 1, maximumSessions: 2 },
    { movementPattern: 'HINGE', minimumSessions: 1, maximumSessions: 2 },
    { movementPattern: 'HORIZONTAL_PUSH', minimumSessions: 1, maximumSessions: 2 },
    { movementPattern: 'VERTICAL_PUSH', minimumSessions: 1, maximumSessions: 1 },
    { movementPattern: 'HORIZONTAL_PULL', minimumSessions: 1, maximumSessions: 2 },
    { movementPattern: 'VERTICAL_PULL', minimumSessions: 1, maximumSessions: 1 },
    { movementPattern: 'ELBOW_FLEXION', minimumSessions: 1, maximumSessions: 1 },
    { movementPattern: 'ELBOW_EXTENSION', minimumSessions: 1, maximumSessions: 1 },
  ],
  3: [
    { movementPattern: 'SQUAT', minimumSessions: 2, maximumSessions: 3 },
    { movementPattern: 'HINGE', minimumSessions: 1, maximumSessions: 2 },
    { movementPattern: 'HORIZONTAL_PUSH', minimumSessions: 2, maximumSessions: 2 },
    { movementPattern: 'VERTICAL_PUSH', minimumSessions: 1, maximumSessions: 1 },
    { movementPattern: 'HORIZONTAL_PULL', minimumSessions: 2, maximumSessions: 2 },
    { movementPattern: 'VERTICAL_PULL', minimumSessions: 1, maximumSessions: 1 },
    { movementPattern: 'ELBOW_FLEXION', minimumSessions: 1, maximumSessions: 2 },
    { movementPattern: 'ELBOW_EXTENSION', minimumSessions: 1, maximumSessions: 2 },
  ],
}

const PROFESSIONAL_SESSION_BLUEPRINTS: Record<
  Exclude<ProfessionalSessionFocus, 'FULL_BODY'>,
  ProfessionalSessionBlueprint
> = {
  UPPER: {
    allowedMovementPatterns: [
      'HORIZONTAL_PUSH', 'VERTICAL_PUSH', 'HORIZONTAL_PULL', 'VERTICAL_PULL',
      'SHOULDER_ABDUCTION', 'SHOULDER_HORIZONTAL_ABDUCTION', 'SCAPULAR_ELEVATION',
      'ELBOW_FLEXION', 'ELBOW_EXTENSION',
    ],
    requiredMovementPatterns: [
      'HORIZONTAL_PUSH', 'HORIZONTAL_PULL', 'ELBOW_FLEXION', 'ELBOW_EXTENSION',
    ],
  },
  LOWER: {
    allowedMovementPatterns: ['SQUAT', 'HINGE', 'CALF_RAISE', 'CORE'],
    requiredMovementPatterns: ['SQUAT', 'HINGE'],
  },
  PUSH: {
    allowedMovementPatterns: [
      'HORIZONTAL_PUSH', 'VERTICAL_PUSH', 'SHOULDER_ABDUCTION', 'ELBOW_EXTENSION',
    ],
    requiredMovementPatterns: ['HORIZONTAL_PUSH', 'VERTICAL_PUSH', 'ELBOW_EXTENSION'],
  },
  PULL: {
    allowedMovementPatterns: [
      'HORIZONTAL_PULL', 'VERTICAL_PULL', 'SHOULDER_HORIZONTAL_ABDUCTION',
      'SCAPULAR_ELEVATION', 'ELBOW_FLEXION',
    ],
    requiredMovementPatterns: ['HORIZONTAL_PULL', 'VERTICAL_PULL', 'ELBOW_FLEXION'],
  },
  CHEST: {
    allowedMovementPatterns: ['HORIZONTAL_PUSH', 'ELBOW_EXTENSION', 'CORE'],
    requiredMovementPatterns: ['HORIZONTAL_PUSH'],
  },
  BACK: {
    allowedMovementPatterns: [
      'HORIZONTAL_PULL', 'VERTICAL_PULL', 'SHOULDER_HORIZONTAL_ABDUCTION',
      'SCAPULAR_ELEVATION', 'ELBOW_FLEXION',
    ],
    requiredMovementPatterns: ['HORIZONTAL_PULL', 'VERTICAL_PULL'],
  },
  ARMS: {
    allowedMovementPatterns: ['ELBOW_FLEXION', 'ELBOW_EXTENSION'],
    requiredMovementPatterns: ['ELBOW_FLEXION', 'ELBOW_EXTENSION'],
  },
  SHOULDERS: {
    allowedMovementPatterns: [
      'VERTICAL_PUSH', 'SHOULDER_ABDUCTION', 'SHOULDER_HORIZONTAL_ABDUCTION',
      'SCAPULAR_ELEVATION',
    ],
    requiredMovementPatterns: ['VERTICAL_PUSH', 'SHOULDER_ABDUCTION'],
  },
}

export function createValidatedAiContentGenerator(
  provider: AiTextGenerationPort,
): ValidatedAiContentGenerator {
  return {
    async generate(purpose, facts, fallback) {
      try {
        const approvedFacts = projectApprovedFacts(purpose, facts)
        const raw = await provider.generate({
          purpose,
          systemPrompt: systemPrompt(purpose),
          factsJson: JSON.stringify(approvedFacts),
        })
        const summary = validateSummary(raw, collectNumbers(approvedFacts))
        return {
          status: 'READY',
          content: summary.explanation,
          validationStatus: 'VALID',
          structured: summary,
        }
      } catch (error) {
        if (isOrdinaryAiFallbackError(error)) return fallback()
        throw error
      }
    },
  }
}

export function createValidatedAiPlanGenerator(
  provider: AiTextGenerationPort,
): AiPlanGenerator {
  return {
    async generate(context, options) {
      if (options.consentGranted !== true) {
        throw new AiDiagnosticError(
          'ELIGIBILITY',
          'AI_CONSENT_REQUIRED',
          'Explicit user AI consent is required',
        )
      }
      const facts = projectPlanGenerationFacts(context, options.repairIssues)
      const raw = await provider.generate({
        purpose: 'PLAN_GENERATION',
        systemPrompt: planGenerationSystemPrompt(),
        factsJson: JSON.stringify(facts),
        explicitUserConsent: true,
      })
      return validatePlanSelection(raw, context)
    },
  }
}

function projectPlanGenerationFacts(
  context: PlanGenerationContextData,
  repairIssues: ValidationIssue[] | undefined,
): Record<string, unknown> {
  const weeklyFrequency = boundedInteger(context.profile.weeklyFrequency, 1, 7)
  const sessionMinutes = boundedInteger(context.profile.sessionMinutes, 1, 180)
  const maximumExercises = boundedInteger(
    context.constraints.maximumExercisesPerSession,
    1,
    12,
  )
  if (!Array.isArray(context.exercises) || context.exercises.length === 0
    || context.exercises.length > 80) {
    throw contractError('Plan generation exercise facts are invalid')
  }
  const exercises = context.exercises.map((exercise) => {
    const code = boundedCode(exercise.code, 64)
    const movementPattern = boundedCode(exercise.movementPattern, 64)
    const difficulty = boundedCode(exercise.difficulty, 64)
    if (!code || !movementPattern || !difficulty
      || typeof exercise.preferred !== 'boolean'
      || typeof exercise.bodyweight !== 'boolean') {
      throw contractError('Plan generation exercise fact is invalid')
    }
    return {
      code,
      movementPattern,
      difficulty,
      equipment: projectCodes(exercise.equipment, 12),
      primaryMuscles: projectCodes(exercise.primaryMuscles, 12),
      preferred: exercise.preferred,
      bodyweight: exercise.bodyweight,
    }
  })
  const targetMaximum = sessionMinutes === 45 ? Math.min(5, maximumExercises) : maximumExercises
  const targetMinimum = sessionMinutes === 45 ? Math.min(4, targetMaximum) : 1
  const availableMovementPatterns = new Set(exercises.map((exercise) => exercise.movementPattern))
  const weeklyRequiredMovementPatterns = ['ELBOW_FLEXION', 'ELBOW_EXTENSION']
    .filter((pattern) => availableMovementPatterns.has(pattern))
  return compactRecord({
    profile: {
      experience: requiredCode(context.profile.experience, 64),
      trainingSplit: requiredCode(resolveTrainingSplit(context), 64),
      goal: requiredCode(context.profile.goal, 64),
      weeklyFrequency,
      sessionMinutes,
      location: requiredCode(context.profile.location, 64),
    },
    exercises,
    targetExercisesPerSession: { minimum: targetMinimum, maximum: targetMaximum },
    professionalSessionStructure: projectProfessionalSessionStructure(
      resolveTrainingSplit(context),
      weeklyFrequency,
      exercises.map((exercise) => exercise.movementPattern),
    ),
    professionalWeeklyStructure: projectProfessionalWeeklyStructure(
      weeklyFrequency,
      availableMovementPatterns,
      weeklyRequiredMovementPatterns,
    ),
    selectionConstraints: {
      maximumExercisesPerSession: maximumExercises,
      maximumMovementPatternOccurrencesPerSession: boundedInteger(
        context.constraints.maximumMovementPatternOccurrencesPerSession,
        1,
        12,
      ),
      minimumRecoveryHoursBetweenPrimaryMuscleSessions: boundedInteger(
        context.constraints.minimumRecoveryHoursBetweenPrimaryMuscleSessions,
        1,
        336,
      ),
    },
    ruleReference: projectRuleReference(context.ruleReference),
    repairIssues: projectRepairIssues(repairIssues),
  })
}

function projectProfessionalWeeklyStructure(
  weeklyFrequency: number,
  availableMovementPatterns: ReadonlySet<string>,
  requiredMovementPatterns: readonly string[],
): Record<string, unknown> | undefined {
  const configuredTargets = FULL_BODY_WEEKLY_MOVEMENT_TARGETS[weeklyFrequency]
  const applicableTargets = configuredTargets?.every((target) =>
    availableMovementPatterns.has(target.movementPattern))
    ? configuredTargets
    : undefined
  if (requiredMovementPatterns.length === 0 && !applicableTargets) return undefined
  return compactRecord({
    requiredMovementPatterns: requiredMovementPatterns.length > 0
      ? [...requiredMovementPatterns]
      : undefined,
    movementPatternSessionTargets: applicableTargets?.map((target) => ({ ...target })),
  })
}

function projectProfessionalSessionStructure(
  trainingSplit: NonNullable<PlanGenerationContextData['profile']['trainingSplit']>,
  weeklyFrequency: number,
  availableMovementPatterns: readonly string[],
): Array<Record<string, unknown>> {
  const focuses: ProfessionalSessionFocus[] = switchSessionFocus(trainingSplit, weeklyFrequency)
  const allPatterns = [...new Set(availableMovementPatterns)].sort()
  return focuses.map((focus, index) => {
    const blueprint: ProfessionalSessionBlueprint = focus === 'FULL_BODY'
      ? {
          allowedMovementPatterns: allPatterns,
          requiredMovementPatterns: [],
          recommendedMovementPatternGroups: [
            ['SQUAT', 'HINGE'],
            ['HORIZONTAL_PUSH', 'VERTICAL_PUSH'],
            ['HORIZONTAL_PULL', 'VERTICAL_PULL'],
          ],
        }
      : PROFESSIONAL_SESSION_BLUEPRINTS[focus]
    return compactRecord({
      code: `DAY_${index + 1}`,
      focus,
      allowedMovementPatterns: [...blueprint.allowedMovementPatterns],
      requiredMovementPatterns: [...blueprint.requiredMovementPatterns],
      recommendedMovementPatternGroups: blueprint.recommendedMovementPatternGroups
        ?.map((group) => [...group]),
    })
  })
}

function switchSessionFocus(
  trainingSplit: NonNullable<PlanGenerationContextData['profile']['trainingSplit']>,
  weeklyFrequency: number,
): ProfessionalSessionFocus[] {
  if (trainingSplit === 'UPPER_LOWER') {
    return Array.from({ length: weeklyFrequency }, (_, index) => index % 2 === 0 ? 'UPPER' : 'LOWER')
  }
  if (trainingSplit === 'PUSH_PULL_LEGS') {
    return Array.from({ length: weeklyFrequency }, (_, index) => (['PUSH', 'PULL', 'LOWER'] as const)[index % 3])
  }
  return ['CHEST', 'BACK', 'LOWER', 'ARMS', 'SHOULDERS']
}

function resolveTrainingSplit(
  context: PlanGenerationContextData,
): NonNullable<PlanGenerationContextData['profile']['trainingSplit']> {
  if (context.profile.trainingSplit) return context.profile.trainingSplit
  if (context.profile.weeklyFrequency === 2 || context.profile.weeklyFrequency === 4) return 'UPPER_LOWER'
  if (context.profile.weeklyFrequency === 3 || context.profile.weeklyFrequency === 6) return 'PUSH_PULL_LEGS'
  return 'BODY_PART_FIVE_DAY'
}

function projectRepairIssues(value: ValidationIssue[] | undefined): Array<Record<string, string>> {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > 20) {
    throw contractError('Plan repair issues are invalid')
  }
  return value.map((issue) => {
    const reasonCode = boundedCode(issue.reasonCode, 96)
    const fieldPath = optionalBoundedString(issue.fieldPath, 240)
    if (!reasonCode || !fieldPath || !/^[A-Za-z0-9_./:-]+$/.test(fieldPath)) {
      throw contractError('Plan repair issue is invalid')
    }
    return { reasonCode, fieldPath }
  })
}

function planGenerationSystemPrompt(): string {
  return [
    '你是训练计划中的动作选择与编排模块。',
    '只能使用用户消息里的结构化事实，从 exercises 白名单选择 exercise code，并按训练日分组和排序。',
    '不得输出或决定组数、次数、休息、重量、训练量、进阶、医疗、康复或安全结论；这些全部由确定性规则引擎计算和校验。',
    '必须生成与 weeklyFrequency 完全一致的训练日；45 分钟训练日必须选择 4 到 5 个动作。',
    '必须逐日遵守 professionalSessionStructure 的 code、focus、allowedMovementPatterns 和 requiredMovementPatterns。',
    '如果提供 professionalWeeklyStructure，整周必须覆盖其中每个 requiredMovementPatterns；有可用动作时，直接二头和直接三头都不能省略。',
    '如果 professionalWeeklyStructure 提供 movementPatternSessionTargets，必须按 movementPattern 统计它出现在多少个训练日，并同时满足 minimumSessions 与 maximumSessions。',
    'PUSH 必须包含 HORIZONTAL_PUSH、VERTICAL_PUSH、ELBOW_EXTENSION（直接三头）；PULL 必须包含 HORIZONTAL_PULL、VERTICAL_PULL、ELBOW_FLEXION（直接二头）。',
    'LOWER 必须包含 SQUAT 和 HINGE；UPPER 必须包含水平推、水平拉、直接二头和直接三头。',
    '五分化必须依次编排 CHEST、BACK、LOWER、ARMS、SHOULDERS；ARMS 必须同时包含直接二头和直接三头，肩部日不得堆叠两个肩上推举。',
    'FULL_BODY 每天只需从每个 recommendedMovementPatternGroups 选择一个模式：一个下肢、一个推、一个拉；剩余名额用于 CORE、直接二头或直接三头，不要把同组两个模式都机械堆进同一天。',
    '三日 FULL_BODY 的整周结构应让水平胸推和水平拉各出现两天、垂直肩推和垂直拉各出现一天；直接二头、直接三头要覆盖整周，但不要机械地每天都安排。',
    '修正 WEEKLY_DIRECT_ARM_PATTERN_MISSING 时，必须在目标总数内用直接二头或直接三头替换可选动作，不能在原列表后追加导致超过 targetExercisesPerSession.maximum。',
    '修正 WEEKLY_MOVEMENT_PATTERN_UNDERFILLED 或 WEEKLY_MOVEMENT_PATTERN_OVERFILLED 时，必须替换对应训练日的模式，不能只追加动作。',
    '同一训练日通常不得重复 movementPattern；CHEST 可有最多 2 个 HORIZONTAL_PUSH，ARMS 可各有最多 2 个 ELBOW_FLEXION 与 ELBOW_EXTENSION，其他情况仍不得重复。',
    '只输出一个 JSON 对象，不要 Markdown、代码围栏或额外文字。',
    'JSON 必须且只能包含：{"name":"非空名称","days":[{"code":"DAY_1","name":"非空名称","exerciseCodes":["白名单动作代码"]}]}。',
  ].join('\n')
}

function validatePlanSelection(
  raw: string,
  context: PlanGenerationContextData,
): AiPlanProposal {
  if (!raw || raw.length > 12_000) throw contractError('AI plan response is empty or too long')
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    throw contractError('AI plan response is not JSON')
  }
  if (!isRecord(value) || !hasExactKeys(value, ['name', 'days'])) {
    throw contractError('AI plan response schema is invalid')
  }
  const name = validatedAiLabel(value.name, 80)
  if (!Array.isArray(value.days) || value.days.length !== context.profile.weeklyFrequency) {
    throw contractError('AI plan day count is invalid')
  }
  const knownExerciseCodes = new Set(context.exercises.map((exercise) => exercise.code))
  const maximumExercises = boundedInteger(
    context.constraints.maximumExercisesPerSession,
    1,
    12,
  )
  const dayCodes = new Set<string>()
  const days = value.days.map((day) => {
    if (!isRecord(day) || !hasExactKeys(day, ['code', 'name', 'exerciseCodes'])) {
      throw contractError('AI plan day schema is invalid')
    }
    const code = boundedCode(day.code, 16)
    if (!code || !/^DAY_[1-6]$/.test(code) || dayCodes.has(code)) {
      throw contractError('AI plan day code is invalid')
    }
    dayCodes.add(code)
    const dayName = validatedAiLabel(day.name, 80)
    // Keep the client boundary structural only. Professional count, focus,
    // recovery and duration constraints belong to the authoritative backend,
    // which returns field-level issues for one targeted AI repair attempt.
    if (!Array.isArray(day.exerciseCodes)
      || day.exerciseCodes.length < 1
      || day.exerciseCodes.length > maximumExercises) {
      throw contractError('AI plan exercise count is invalid')
    }
    const selected = new Set<string>()
    const exercises = day.exerciseCodes.map((item) => {
      const exerciseCode = boundedCode(item, 64)
      if (!exerciseCode || !knownExerciseCodes.has(exerciseCode) || selected.has(exerciseCode)) {
        throw contractError('AI plan exercise selection is invalid')
      }
      selected.add(exerciseCode)
      return {
        exerciseCode,
        workSets: boundedInteger(context.constraints.minimumWorkSets, 1, 12),
        repMin: boundedInteger(context.constraints.minimumReps, 1, 100),
        repMax: boundedInteger(context.constraints.minimumReps, 1, 100),
        restSeconds: boundedInteger(context.constraints.minimumRestSeconds, 1, 900),
      }
    })
    return { code, name: dayName, exercises }
  })
  return { name, days }
}

function validatedAiLabel(value: unknown, maximumLength: number): string {
  const label = boundedText(value, maximumLength)
  if (!label) throw contractError('AI plan label is invalid')
  if (isUnsafe(label) || containsAbsoluteWeightClaim(label)) {
    throw new AiDiagnosticError(
      'UNSAFE_OUTPUT',
      'AI_UNSAFE_OUTPUT',
      'AI plan output conflicts with the approved safety boundary',
    )
  }
  return label
}

function containsAbsoluteWeightClaim(value: string): boolean {
  return /\b\d+(?:\.\d+)?\s*(?:kg|公斤|千克|lb|lbs|磅)\b/i.test(value)
    || /\b(?:one|two|three|four|five|six|seven|eight|nine|ten|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)\s+(?:kg|kilos?|pounds?|lbs?)\b/i.test(value)
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(value).sort().join('|') === [...keys].sort().join('|')
}

function requiredCode(value: unknown, maximumLength: number): string {
  const code = boundedCode(value, maximumLength)
  if (!code) throw contractError('Required structured code is missing')
  return code
}

function boundedInteger(value: unknown, minimum: number, maximum: number): number {
  if (!Number.isSafeInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw contractError('Structured integer is out of range')
  }
  return value as number
}

function projectApprovedFacts(
  purpose: AiContentGenerationPurpose,
  input: Record<string, unknown>,
): Record<string, unknown> {
  switch (purpose) {
    case 'PLAN_EXPLANATION':
      return projectPlanExplanation(input)
    case 'WORKOUT_SUMMARY':
      return projectWorkoutSummary(input)
    case 'ALTERNATIVE_RANKING':
      return projectAlternativeRanking(input)
  }
}

function projectPlanExplanation(input: Record<string, unknown>): Record<string, unknown> {
  const candidateId = optionalBoundedString(input.candidateId, 128)
  const exercises = projectExercises(input.exercises)
  const ruleReference = projectRuleReference(input.ruleReference)
  if (!candidateId && exercises.length === 0 && !ruleReference) {
    throw contractError('PLAN_EXPLANATION facts are missing')
  }
  return compactRecord({ candidateId, exercises, ruleReference })
}

function projectExercises(value: unknown): Array<Record<string, unknown>> {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > 40) throw contractError('Invalid exercise facts')
  return value.map((item) => {
    if (!isRecord(item)) throw contractError('Invalid exercise fact')
    const exerciseCode = boundedCode(item.exerciseCode, 64)
    if (!exerciseCode) throw contractError('Exercise code is required')
    return compactRecord({
      exerciseCode,
      workSets: optionalSafeNumber(item.workSets),
      repMin: optionalSafeNumber(item.repMin),
      repMax: optionalSafeNumber(item.repMax),
      restSeconds: optionalSafeNumber(item.restSeconds),
      weightStatus: optionalEnum(item.weightStatus),
    })
  })
}

function projectRuleReference(value: unknown): Record<string, unknown> | undefined {
  if (value === undefined) return undefined
  if (!isRecord(value)) throw contractError('Invalid rule reference')
  const ruleVersion = optionalBoundedString(value.ruleVersion, 64)
  const templateVersion = optionalBoundedString(value.templateVersion, 64)
  const contentVersion = optionalBoundedString(value.contentVersion, 64)
  if (!ruleVersion && !templateVersion && !contentVersion) {
    throw contractError('Rule reference is empty')
  }
  return compactRecord({ ruleVersion, templateVersion, contentVersion })
}

function projectWorkoutSummary(input: Record<string, unknown>): Record<string, unknown> {
  const sessionId = optionalBoundedString(input.sessionId, 128)
  const status = optionalEnum(input.status)
  const completedWorkSets = optionalSafeNumber(input.completedWorkSets)
  const completedVolumeKg = optionalSafeNumber(input.completedVolumeKg)
  const reasonCodes = projectCodes(input.reasonCodes, 20)
  const progressionConclusion = input.progressionConclusion === null
    ? null
    : optionalEnum(input.progressionConclusion)
  if (!sessionId && !status && completedWorkSets === undefined
    && completedVolumeKg === undefined && reasonCodes.length === 0
    && progressionConclusion === undefined) {
    throw contractError('WORKOUT_SUMMARY facts are missing')
  }
  return compactRecord({
    sessionId,
    status,
    completedWorkSets,
    completedVolumeKg,
    reasonCodes,
    progressionConclusion,
  })
}

function projectAlternativeRanking(input: Record<string, unknown>): Record<string, unknown> {
  const sourceExerciseCode = boundedCode(input.sourceExerciseCode, 64)
  const candidateExerciseCodes = projectCodes(input.candidateExerciseCodes, 4)
  if (!sourceExerciseCode || candidateExerciseCodes.length < 2) {
    throw contractError('ALTERNATIVE_RANKING requires one source and 2 to 4 candidates')
  }
  return { sourceExerciseCode, candidateExerciseCodes }
}

function projectCodes(value: unknown, maximum: number): string[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > maximum) throw contractError('Invalid code list')
  const codes = value.map((item) => boundedCode(item, 64))
  if (codes.some((item) => !item)) throw contractError('Invalid code')
  return codes as string[]
}

function systemPrompt(purpose: AiContentGenerationPurpose): string {
  const context = purpose === 'PLAN_EXPLANATION'
    ? '候选训练计划解释'
    : purpose === 'WORKOUT_SUMMARY'
      ? '已完成训练总结'
      : '合法替代动作排序'
  return [
    `你是 AI 健身助手的${context}模块。`,
    '只使用用户消息中的结构化事实，不提供医疗诊断或康复处方。',
    '关键数字、训练处方和进阶结论由确定性规则引擎产生；不得新增、推算或修改任何数字，不得改变规则结论，不得覆盖用户锁定字段。',
    '所有数字必须使用半角阿拉伯数字，不得使用中文或全角数字。',
    '只输出一个 JSON 对象，不要 Markdown、代码围栏或额外文字。',
    'JSON 必须且只能包含：{"summary":"非空字符串","highlights":["字符串"],"issues":["字符串"],"nextActions":["字符串"],"explanation":"非空字符串","safetyNotice":null或"字符串"}。',
    'highlights、issues、nextActions 各最多 5 项；没有安全提示时 safetyNotice 必须为 null。',
  ].join('\n')
}

function validateSummary(raw: string, allowedNumbers: Set<string>): AiStructuredSummary {
  if (!raw || raw.length > 4000) throw contractError('AI response is empty or too long')
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    throw contractError('AI response is not JSON')
  }
  if (!isRecord(value)
    || Object.keys(value).sort().join('|') !== [...SUMMARY_FIELDS].sort().join('|')) {
    throw contractError('AI response schema is invalid')
  }
  const summary = boundedText(value.summary, 300)
  const highlights = boundedTextArray(value.highlights)
  const issues = boundedTextArray(value.issues)
  const nextActions = boundedTextArray(value.nextActions)
  const explanation = boundedText(value.explanation, 500)
  const safetyNotice = value.safetyNotice === null ? null : boundedText(value.safetyNotice, 240)
  if (!summary || !highlights || !issues || !nextActions || !explanation
    || (value.safetyNotice !== null && !safetyNotice)) {
    throw contractError('AI response fields are invalid')
  }
  const result: AiStructuredSummary = {
    summary,
    highlights,
    issues,
    nextActions,
    explanation,
    safetyNotice,
  }
  const allText = [
    summary,
    ...highlights,
    ...issues,
    ...nextActions,
    explanation,
    safetyNotice || '',
  ].join(' ')
  if (allText.length > 1800 || isUnsafe(allText)
    || NON_ARABIC_NUMBER_PATTERN.test(allText)
    || hasNumericConflict(allText, allowedNumbers)) {
    throw new AiDiagnosticError(
      'UNSAFE_OUTPUT',
      'AI_UNSAFE_OUTPUT',
      'AI output conflicts with the approved safety boundary',
    )
  }
  return result
}

function boundedText(value: unknown, maxLength: number): string | null {
  if (typeof value !== 'string') return null
  const text = value.trim()
  return text.length > 0 && text.length <= maxLength ? text : null
}

function boundedTextArray(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > 5) return null
  const items = value.map((item) => boundedText(item, 160))
  return items.every((item): item is string => item !== null) ? items : null
}

function optionalBoundedString(value: unknown, maxLength: number): string | undefined {
  if (value === undefined) return undefined
  const text = boundedText(value, maxLength)
  if (!text) throw contractError('Invalid structured text fact')
  return text
}

function boundedCode(value: unknown, maxLength: number): string | undefined {
  const text = optionalBoundedString(value, maxLength)
  if (text !== undefined && !/^[A-Za-z0-9_.:-]+$/.test(text)) {
    throw contractError('Invalid structured code fact')
  }
  return text
}

function optionalEnum(value: unknown): string | undefined {
  return boundedCode(value, 64)
}

function optionalSafeNumber(value: unknown): number | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > 1_000_000) {
    throw contractError('Invalid numeric fact')
  }
  return value
}

function compactRecord(
  value: Record<string, unknown>,
): Record<string, unknown> {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined))
}

function collectNumbers(value: unknown, result = new Set<string>()): Set<string> {
  if (typeof value === 'number' && Number.isFinite(value)) {
    result.add(normalizeNumber(value))
  } else if (Array.isArray(value)) {
    value.forEach((item) => collectNumbers(item, result))
  } else if (isRecord(value)) {
    Object.values(value).forEach((item) => collectNumbers(item, result))
  }
  return result
}

function hasNumericConflict(text: string, allowedNumbers: Set<string>): boolean {
  for (const match of text.match(NUMBER_PATTERN) || []) {
    if (!allowedNumbers.has(normalizeNumber(Number(match)))) return true
  }
  return false
}

function normalizeNumber(value: number): string {
  return String(Number(value.toFixed(3)))
}

function isUnsafe(text: string): boolean {
  const normalized = text.toLowerCase()
  return normalizeSafeTrainingPreference(text, 2_000) === null
    || UNSAFE_MARKERS.some((marker) => normalized.includes(marker))
    || /1[3-9]\d{9}/.test(text)
}

function contractError(message: string): AiDiagnosticError {
  return new AiDiagnosticError('CONTRACT', 'AI_CONTRACT_INVALID', message)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
