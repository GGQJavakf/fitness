import type {
  CreatePlanVersionRequest,
  LockCommandStatus,
  LockStatus,
  PlanDraft,
  PlanValidationData,
  PlanValidationDraft,
  PlanVersionResultData,
} from './models'

export type EditableNumericField = 'workSets' | 'repMin' | 'repMax' | 'restSeconds'

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
  if (!Number.isInteger(value) || value <= 0) {
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
    name: plan.name,
    days: plan.days.map((day) => ({
      ...day,
      exercises: day.exercises.map((exercise) => ({ ...exercise })),
    })),
  }
}

function findNumericDiffs(before: PlanDraft, after: PlanDraft): NumericFieldDiff[] {
  const fields: readonly EditableNumericField[] = ['workSets', 'repMin', 'repMax', 'restSeconds']
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
        return typeof afterValue === 'number' && afterValue !== exercise[field]
          ? [{
              fieldPath: numericFieldPath(day.code, exercise.exerciseCode, field),
              before: exercise[field],
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
    targetExercise[field] = sourceExercise[field]
  }
}

function assertPathSegment(value: string, name: 'dayCode' | 'exerciseCode'): void {
  if (value.length === 0 || value.includes('/')) {
    throw new TypeError(`${name} must be a non-empty path segment without /`)
  }
}
