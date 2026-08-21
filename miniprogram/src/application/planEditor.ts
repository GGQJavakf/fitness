import type {
  CreatePlanVersionRequest,
  LockCommandStatus,
  LockStatus,
  PlanDraft,
  PlanDayOption,
  PlanExercise,
  PlanExerciseOption,
  PlanValidationData,
  PlanValidationDraft,
  PlanVersionResultData,
} from './models'

export type EditableNumericField = 'workSets' | 'repMin' | 'repMax' | 'restSeconds' | 'targetWeightKg'

export interface PlanEditorState {
  planId: string
  baseVersion: number
  basePlan: PlanDraft
  workingCopy: PlanDraft
  validationResult: PlanValidationData
  baseLocks: Record<string, LockStatus>
  lockCommands: Record<string, LockCommandStatus>
  locks: Record<string, LockStatus>
  lockedFieldOutcomes: Record<string, LockStatus>
  warningConfirmationToken?: string
  warningConfirmed: boolean
  rebalanceDiffs: NumericFieldDiff[]
  conflict?: {
    code: 'VERSION_CONFLICT'
    message: string
  }
}

export interface NumericFieldDiff {
  fieldPath: string
  before: number
  after: number
}

interface CreatePlanEditorStateInput {
  planId: string
  baseVersion: number
  plan: PlanDraft
  validationResult: PlanValidationData
}

export function createPlanEditorState(input: CreatePlanEditorStateInput): PlanEditorState {
  const baseLocks = { ...input.plan.locks }
  return {
    planId: input.planId,
    baseVersion: input.baseVersion,
    basePlan: clonePlan(input.plan),
    workingCopy: clonePlan(input.plan),
    validationResult: cloneValidation(input.validationResult),
    baseLocks,
    lockCommands: {},
    locks: { ...baseLocks },
    lockedFieldOutcomes: {},
    warningConfirmed: false,
    rebalanceDiffs: [],
  }
}

export function numericFieldPath(
  dayCode: string,
  exerciseCode: string,
  field: EditableNumericField,
): string {
  assertPathSegment(dayCode, 'dayCode')
  assertPathSegment(exerciseCode, 'exerciseCode')
  return `/days/${dayCode}/exercises/${exerciseCode}/${field}`
}

export function changeNumericField(
  state: PlanEditorState,
  dayCode: string,
  exerciseCode: string,
  field: EditableNumericField,
  value: number,
): PlanEditorState {
  const fieldPath = numericFieldPath(dayCode, exerciseCode, field)
  const currentLock = effectiveLock(state, fieldPath)
  if (currentLock === 'RULE_LOCKED') {
    return {
      ...state,
      lockedFieldOutcomes: {
        ...state.lockedFieldOutcomes,
        [fieldPath]: 'RULE_LOCKED',
      },
    }
  }
  if (!validEditableNumber(field, value)) {
    return state
  }

  const workingCopy = clonePlan(state.workingCopy)
  const exercise = workingCopy.days
    .find((day) => day.code === dayCode)
    ?.exercises.find((item) => item.exerciseCode === exerciseCode)
  if (!exercise) {
    return state
  }
  exercise[field] = value
  if (field === 'targetWeightKg') exercise.weightStatus = 'KNOWN'

  const lockCommands = { ...state.lockCommands }
  if (currentLock !== 'USER_LOCKED' && lockCommands[fieldPath] !== 'UNLOCKED') {
    lockCommands[fieldPath] = 'USER_LOCKED'
  }

  return {
    ...state,
    workingCopy,
    lockCommands,
    locks: effectiveLocks(state.baseLocks, lockCommands),
    validationResult: { valid: true, validationIssues: [] },
    warningConfirmationToken: undefined,
    warningConfirmed: false,
    conflict: undefined,
  }
}

export function addPlanExercise(
  state: PlanEditorState,
  dayCode: string,
  option: PlanExerciseOption,
): PlanEditorState {
  const workingCopy = clonePlan(state.workingCopy)
  const day = requireDay(workingCopy, dayCode)
  if (day.exercises.some((exercise) => exercise.exerciseCode === option.exerciseCode)) {
    throw new Error('动作已在当前训练日中')
  }
  if (day.exercises.length >= 8) throw new Error('每个训练日最多包含 8 个动作')
  const {
    name: _name,
    movementPattern: _movementPattern,
    primaryMuscles: _primaryMuscles,
    equipment: _equipment,
    ...exercise
  } = option
  validatePrescribedExercise(exercise)
  day.exercises.push({ ...exercise })
  return afterStructuralEdit(state, workingCopy, state.lockCommands)
}

export function addPlanDay(state: PlanEditorState, option: PlanDayOption): PlanEditorState {
  const workingCopy = clonePlan(state.workingCopy)
  if (workingCopy.days.some((day) => day.code === option.code)) {
    throw new Error('训练日已在当前计划中')
  }
  if (workingCopy.days.length >= 6) throw new Error('每周最多包含 6 个训练日')
  if (!option.code || option.code.includes('/') || !option.name.trim() || option.exercises.length === 0) {
    throw new Error('服务端训练日处方无效')
  }
  const exercises = option.exercises.map(({
    name: _name,
    movementPattern: _movementPattern,
    primaryMuscles: _primaryMuscles,
    equipment: _equipment,
    ...exercise
  }) => {
    validatePrescribedExercise(exercise)
    return { ...exercise }
  })
  workingCopy.days.push({ code: option.code, name: option.name, exercises })
  return afterStructuralEdit(state, workingCopy, state.lockCommands)
}

export function removePlanDay(state: PlanEditorState, dayCode: string): PlanEditorState {
  if (state.workingCopy.days.length === 1) throw new Error('计划至少保留一个训练日')
  const workingCopy = clonePlan(state.workingCopy)
  const index = workingCopy.days.findIndex((day) => day.code === dayCode)
  if (index < 0) throw new Error('训练日不存在')
  const prefix = `/days/${dayCode}/`
  if (Object.entries(state.baseLocks).some(([path, status]) => path.startsWith(prefix) && status === 'RULE_LOCKED')) {
    throw new Error('规则锁定训练日不能删除')
  }
  const lockCommands = Object.fromEntries(
    Object.entries(state.lockCommands).filter(([path]) => !path.startsWith(prefix)),
  ) as Record<string, LockCommandStatus>
  Object.entries(state.baseLocks).forEach(([path, status]) => {
    if (path.startsWith(prefix) && status === 'USER_LOCKED') lockCommands[path] = 'UNLOCKED'
  })
  workingCopy.days.splice(index, 1)
  return afterStructuralEdit(state, workingCopy, lockCommands)
}

export function movePlanDay(
  state: PlanEditorState,
  dayCode: string,
  direction: -1 | 1,
): PlanEditorState {
  const workingCopy = clonePlan(state.workingCopy)
  const index = workingCopy.days.findIndex((day) => day.code === dayCode)
  if (index < 0) throw new Error('训练日不存在')
  const target = index + direction
  if (target < 0 || target >= workingCopy.days.length) return state
  const [day] = workingCopy.days.splice(index, 1)
  workingCopy.days.splice(target, 0, day)
  return afterStructuralEdit(state, workingCopy, state.lockCommands)
}

export function removePlanExercise(
  state: PlanEditorState,
  dayCode: string,
  exerciseCode: string,
): PlanEditorState {
  const workingCopy = clonePlan(state.workingCopy)
  const day = requireDay(workingCopy, dayCode)
  if (day.exercises.length === 1) throw new Error('每个训练日至少保留一个动作')
  const index = day.exercises.findIndex((exercise) => exercise.exerciseCode === exerciseCode)
  if (index < 0) throw new Error('动作不存在')
  const lockCommands = commandsWithoutExercise(state, dayCode, exerciseCode, '规则锁定动作不能删除')
  day.exercises.splice(index, 1)
  return afterStructuralEdit(state, workingCopy, lockCommands)
}

export function replacePlanExercise(
  state: PlanEditorState,
  dayCode: string,
  exerciseCode: string,
  option: PlanExerciseOption | PlanExercise,
): PlanEditorState {
  if (exerciseCode === option.exerciseCode) return state
  const workingCopy = clonePlan(state.workingCopy)
  const day = requireDay(workingCopy, dayCode)
  const index = day.exercises.findIndex((exercise) => exercise.exerciseCode === exerciseCode)
  if (index < 0) throw new Error('动作不存在')
  if (day.exercises.some((exercise) => exercise.exerciseCode === option.exerciseCode)) {
    throw new Error('动作已在当前训练日中')
  }
  const lockCommands = commandsWithoutExercise(state, dayCode, exerciseCode, '规则锁定动作不能替换')
  const replacement: PlanExercise = {
    exerciseCode: option.exerciseCode,
    workSets: option.workSets,
    repMin: option.repMin,
    repMax: option.repMax,
    restSeconds: option.restSeconds,
    weightStatus: option.weightStatus,
    ...(typeof option.targetWeightKg === 'number' ? { targetWeightKg: option.targetWeightKg } : {}),
  }
  validatePrescribedExercise(replacement)
  day.exercises[index] = replacement
  return afterStructuralEdit(state, workingCopy, lockCommands)
}

export function movePlanExercise(
  state: PlanEditorState,
  dayCode: string,
  exerciseCode: string,
  direction: -1 | 1,
): PlanEditorState {
  const workingCopy = clonePlan(state.workingCopy)
  const day = requireDay(workingCopy, dayCode)
  const index = day.exercises.findIndex((exercise) => exercise.exerciseCode === exerciseCode)
  if (index < 0) throw new Error('动作不存在')
  const target = index + direction
  if (target < 0 || target >= day.exercises.length) return state
  const [exercise] = day.exercises.splice(index, 1)
  day.exercises.splice(target, 0, exercise)
  return afterStructuralEdit(state, workingCopy, state.lockCommands)
}

export function setFieldLock(
  state: PlanEditorState,
  fieldPath: string,
  status: LockCommandStatus,
): PlanEditorState {
  if (effectiveLock(state, fieldPath) === 'RULE_LOCKED') {
    return {
      ...state,
      lockedFieldOutcomes: {
        ...state.lockedFieldOutcomes,
        [fieldPath]: 'RULE_LOCKED',
      },
    }
  }
  const lockCommands = { ...state.lockCommands, [fieldPath]: status }
  return {
    ...state,
    lockCommands,
    locks: effectiveLocks(state.baseLocks, lockCommands),
    warningConfirmationToken: undefined,
    warningConfirmed: false,
  }
}

export function applyValidation(
  state: PlanEditorState,
  validationResult: PlanValidationData,
): PlanEditorState {
  return {
    ...state,
    validationResult: cloneValidation(validationResult),
    warningConfirmationToken: undefined,
    warningConfirmed: false,
  }
}

export function applyPreSaveValidation(
  state: PlanEditorState,
  validationResult: PlanValidationData,
): PlanEditorState {
  const keepConfirmedToken = Boolean(
    state.warningConfirmationToken
      && state.warningConfirmed
      && sameValidation(state.validationResult, validationResult),
  )
  const validated = applyValidation(state, validationResult)
  return keepConfirmedToken
    ? {
        ...validated,
        warningConfirmationToken: state.warningConfirmationToken,
        warningConfirmed: true,
      }
    : validated
}

export function buildSaveCommand(state: PlanEditorState): CreatePlanVersionRequest {
  if (state.validationResult.validationIssues.some((issue) => issue.severity === 'ERROR')) {
    throw new Error('计划包含错误，不能保存')
  }
  if (state.warningConfirmationToken && !state.warningConfirmed) {
    throw new Error('请先确认所有警告')
  }

  return {
    plan: toValidationDraft(state.workingCopy),
    baseVersionNumber: state.baseVersion,
    locks: { ...state.lockCommands },
    ...(state.warningConfirmationToken
      ? { warningConfirmationToken: state.warningConfirmationToken }
      : {}),
  }
}

export function applyVersionResult(
  state: PlanEditorState,
  result: PlanVersionResultData,
): PlanEditorState {
  return {
    ...state,
    workingCopy: clonePlan(result.plan),
    validationResult: {
      valid: !result.validationIssues.some((issue) => issue.severity === 'ERROR'),
      validationIssues: result.validationIssues.map((issue) => ({ ...issue })),
    },
    warningConfirmationToken: result.warningConfirmationToken,
    warningConfirmed: false,
    ...(result.version ? {
      baseVersion: result.version.versionNumber,
      basePlan: clonePlan(result.version.plan),
      baseLocks: { ...result.version.plan.locks },
      lockCommands: {},
      locks: { ...result.version.plan.locks },
    } : {}),
  }
}

export function confirmWarnings(state: PlanEditorState): PlanEditorState {
  if (!state.warningConfirmationToken) {
    return state
  }
  return { ...state, warningConfirmed: true }
}

export function applyRebalancePreview(
  state: PlanEditorState,
  preview: PlanVersionResultData,
): PlanEditorState {
  const previewPlan = clonePlan(preview.plan)
  const diffs = findNumericDiffs(state.workingCopy, previewPlan)

  for (const [fieldPath, status] of Object.entries(effectiveLocks(
    state.baseLocks,
    state.lockCommands,
  ))) {
    if (status === 'USER_LOCKED') {
      copyNumericField(state.workingCopy, previewPlan, fieldPath)
    }
  }

  return {
    ...state,
    workingCopy: previewPlan,
    validationResult: {
      valid: !preview.validationIssues.some((issue) => issue.severity === 'ERROR'),
      validationIssues: preview.validationIssues.map((issue) => ({ ...issue })),
    },
    lockedFieldOutcomes: { ...state.lockedFieldOutcomes },
    rebalanceDiffs: diffs.filter((diff) => effectiveLock(state, diff.fieldPath) !== 'USER_LOCKED'),
    warningConfirmationToken: preview.warningConfirmationToken,
    warningConfirmed: false,
  }
}

export function markVersionConflict(state: PlanEditorState, message?: string): PlanEditorState {
  return {
    ...state,
    conflict: {
      code: 'VERSION_CONFLICT',
      message: message ?? '活动计划已在其他位置更新，请刷新后比较',
    },
  }
}

function clonePlan(plan: PlanDraft): PlanDraft {
  return {
    ...plan,
    days: plan.days.map((day) => ({
      ...day,
      exercises: day.exercises.map((exercise) => ({ ...exercise })),
    })),
    locks: { ...plan.locks },
  }
}

function cloneValidation(validation: PlanValidationData): PlanValidationData {
  return {
    valid: validation.valid,
    validationIssues: validation.validationIssues.map((issue) => ({
      ...issue,
      ...(issue.parameters ? { parameters: { ...issue.parameters } } : {}),
    })),
  }
}

function sameValidation(left: PlanValidationData, right: PlanValidationData): boolean {
  return JSON.stringify(left) === JSON.stringify(right)
}

function effectiveLocks(
  baseLocks: Record<string, LockStatus>,
  lockCommands: Record<string, LockCommandStatus>,
): Record<string, LockStatus> {
  return { ...baseLocks, ...lockCommands }
}

function effectiveLock(state: PlanEditorState, fieldPath: string): LockStatus | undefined {
  return state.lockCommands[fieldPath] ?? state.baseLocks[fieldPath]
}

function toValidationDraft(plan: PlanDraft): PlanValidationDraft {
  return {
    templateCode: plan.templateCode,
    trainingSplit: plan.trainingSplit,
    name: plan.name,
    days: plan.days.map((day) => ({
      ...day,
      exercises: day.exercises.map((exercise) => ({ ...exercise })),
    })),
  }
}

function findNumericDiffs(before: PlanDraft, after: PlanDraft): NumericFieldDiff[] {
  const fields: readonly EditableNumericField[] = ['workSets', 'repMin', 'repMax', 'restSeconds', 'targetWeightKg']
  return before.days.flatMap((day) => {
    const afterDay = after.days.find((item) => item.code === day.code)
    if (!afterDay) return []
    return day.exercises.flatMap((exercise) => {
      const afterExercise = afterDay.exercises.find(
        (item) => item.exerciseCode === exercise.exerciseCode,
      )
      if (!afterExercise) return []
      return fields.flatMap((field) => {
        const afterValue = afterExercise[field]
        const beforeValue = exercise[field]
        return typeof beforeValue === 'number' && typeof afterValue === 'number' && afterValue !== beforeValue
          ? [{
              fieldPath: numericFieldPath(day.code, exercise.exerciseCode, field),
              before: beforeValue,
              after: afterValue,
            }]
          : []
      })
    })
  })
}

function copyNumericField(source: PlanDraft, target: PlanDraft, fieldPath: string): void {
  const match = /^\/days\/([^/]+)\/exercises\/([^/]+)\/(workSets|repMin|repMax|restSeconds)$/.exec(fieldPath)
  if (!match) {
    return
  }
  const dayCode = match[1]
  const exerciseCode = match[2]
  const field = match[3] as EditableNumericField
  const sourceExercise = source.days
    .find((day) => day.code === dayCode)
    ?.exercises.find((exercise) => exercise.exerciseCode === exerciseCode)
  const targetExercise = target.days
    .find((day) => day.code === dayCode)
    ?.exercises.find((exercise) => exercise.exerciseCode === exerciseCode)
  if (sourceExercise && targetExercise) {
    const value = sourceExercise[field]
    if (typeof value === 'number') {
      targetExercise[field] = value
      if (field === 'targetWeightKg') targetExercise.weightStatus = 'KNOWN'
    }
  }
}

function validEditableNumber(field: EditableNumericField, value: number): boolean {
  if (!Number.isFinite(value)) return false
  if (field === 'targetWeightKg') {
    return value >= 0 && Math.abs(value * 100 - Math.round(value * 100)) < 1e-8
  }
  return Number.isInteger(value) && value > 0
}

function requireDay(plan: PlanDraft, dayCode: string) {
  const day = plan.days.find((item) => item.code === dayCode)
  if (!day) throw new Error('训练日不存在')
  return day
}

function commandsWithoutExercise(
  state: PlanEditorState,
  dayCode: string,
  exerciseCode: string,
  ruleLockedMessage: string,
): Record<string, LockCommandStatus> {
  const prefix = `/days/${dayCode}/exercises/${exerciseCode}/`
  if (Object.entries(state.baseLocks).some(([path, status]) => path.startsWith(prefix) && status === 'RULE_LOCKED')) {
    throw new Error(ruleLockedMessage)
  }
  const commands = Object.fromEntries(
    Object.entries(state.lockCommands).filter(([path]) => !path.startsWith(prefix)),
  ) as Record<string, LockCommandStatus>
  Object.entries(state.baseLocks).forEach(([path, status]) => {
    if (path.startsWith(prefix) && status === 'USER_LOCKED') commands[path] = 'UNLOCKED'
  })
  return commands
}

function afterStructuralEdit(
  state: PlanEditorState,
  workingCopy: PlanDraft,
  lockCommands: Record<string, LockCommandStatus>,
): PlanEditorState {
  return {
    ...state,
    workingCopy,
    lockCommands,
    locks: effectiveLocks(state.baseLocks, lockCommands),
    validationResult: { valid: true, validationIssues: [] },
    warningConfirmationToken: undefined,
    warningConfirmed: false,
    rebalanceDiffs: [],
    conflict: undefined,
  }
}

function validatePrescribedExercise(exercise: PlanExercise): void {
  if (!exercise.exerciseCode || exercise.exerciseCode.includes('/')
    || !Number.isInteger(exercise.workSets) || exercise.workSets <= 0
    || !Number.isInteger(exercise.repMin) || exercise.repMin <= 0
    || !Number.isInteger(exercise.repMax) || exercise.repMax < exercise.repMin
    || !Number.isInteger(exercise.restSeconds) || exercise.restSeconds <= 0) {
    throw new Error('服务端动作处方无效')
  }
}

function assertPathSegment(value: string, name: 'dayCode' | 'exerciseCode'): void {
  if (value.length === 0 || value.includes('/')) {
    throw new TypeError(`${name} must be a non-empty path segment without /`)
  }
}
