import type { RestTimerState } from '../domain/workout/RestTimer'

export type WorkoutSetType = 'WARMUP' | 'WORK' | 'EXTRA'
export type WorkoutSetStatus = 'COMPLETED' | 'FAILED' | 'SKIPPED'
export type WorkoutRir = '0' | '1' | '2' | '3_PLUS' | 'UNKNOWN'
export type WorkoutSyncStatus = 'LOCAL_ONLY' | 'OFFLINE_PENDING' | 'SYNCED' | 'CONFLICT' | 'SYNC_REJECTED'

export interface WorkoutExerciseSnapshot {
  readonly snapshotExerciseKey: string
  readonly exerciseCode: string
  readonly name: string
  readonly targetWorkSets: number
  readonly targetReps: number
  readonly restSeconds: number
}

export interface WorkoutSetRecord {
  readonly clientSetKey: string
  readonly setType: WorkoutSetType
  readonly status: WorkoutSetStatus
  readonly actualWeightKg: number | null
  readonly actualReps: number | null
  readonly rir: WorkoutRir
  readonly discomfort: 'NONE' | 'DISCOMFORT' | 'PAIN'
}

export interface WorkoutExerciseState extends WorkoutExerciseSnapshot {
  readonly replacedExerciseCode?: string
  readonly sets: readonly WorkoutSetRecord[]
}

export interface WorkoutFlowState {
  readonly schemaVersion: 1
  readonly clientSessionKey: string
  readonly planVersionId: string
  readonly exercises: readonly WorkoutExerciseState[]
  readonly currentExerciseIndex: number
  readonly currentSetIndex: number
  readonly restTimer: RestTimerState | null
  readonly syncStatus: WorkoutSyncStatus
  readonly automaticProgressionEligible: boolean
  readonly safetyNotice: string | null
}

export interface RecordWorkoutSetInput {
  readonly clientSetKey: string
  readonly exerciseIndex: number
  readonly setType: WorkoutSetType
  readonly status: WorkoutSetStatus
  readonly actualWeightKg?: number
  readonly actualReps?: number
  readonly rir?: WorkoutRir
  readonly discomfort?: 'NONE' | 'DISCOMFORT' | 'PAIN'
}

export function createWorkoutFlow(input: {
  clientSessionKey: string
  planVersionId: string
  exercises: readonly WorkoutExerciseSnapshot[]
}): WorkoutFlowState {
  if (input.clientSessionKey.trim().length === 0 || input.planVersionId.trim().length === 0) {
    throw new Error('workout session and plan version are required')
  }
  if (input.exercises.length === 0) throw new Error('workout requires at least one exercise')
  input.exercises.forEach(validateExercise)
  return {
    schemaVersion: 1,
    clientSessionKey: input.clientSessionKey,
    planVersionId: input.planVersionId,
    exercises: input.exercises.map((exercise) => ({ ...exercise, sets: [] })),
    currentExerciseIndex: 0,
    currentSetIndex: 0,
    restTimer: null,
    syncStatus: 'LOCAL_ONLY',
    automaticProgressionEligible: true,
    safetyNotice: null,
  }
}

export function recordWorkoutSet(state: WorkoutFlowState, input: RecordWorkoutSetInput): WorkoutFlowState {
  validateStateShape(state)
  const exercise = state.exercises[input.exerciseIndex]
  if (!exercise) throw new Error('exerciseIndex is outside the workout snapshot')
  const record = normalizeSet(input)
  const existing = state.exercises.flatMap((item) => item.sets)
    .find((item) => item.clientSetKey === record.clientSetKey)
  if (existing) {
    if (JSON.stringify(existing) === JSON.stringify(record)) return state
    throw new Error('clientSetKey already identifies different workout facts')
  }

  const exercises = state.exercises.map((item, index) => index === input.exerciseIndex
    ? { ...item, sets: [...item.sets, record] }
    : item)
  const position = derivePosition(exercises)
  const unsafe = record.discomfort === 'PAIN' || record.discomfort === 'DISCOMFORT'
  return {
    ...state,
    exercises,
    ...position,
    syncStatus: 'LOCAL_ONLY',
    automaticProgressionEligible: state.automaticProgressionEligible && !unsafe,
    safetyNotice: unsafe ? '出现疼痛或明显不适，请停止训练；必要时寻求专业医疗帮助。' : state.safetyNotice,
  }
}

export function replaceExerciseForSession(
  state: WorkoutFlowState,
  exerciseIndex: number,
  replacement: WorkoutExerciseSnapshot,
): WorkoutFlowState {
  validateStateShape(state)
  validateExercise(replacement)
  const current = state.exercises[exerciseIndex]
  if (!current) throw new Error('exerciseIndex is outside the workout snapshot')
  const exercises = state.exercises.map((item, index) => index === exerciseIndex
    ? { ...replacement, replacedExerciseCode: current.exerciseCode, sets: item.sets }
    : item)
  return { ...state, exercises }
}

export function markWorkoutSyncPending(state: WorkoutFlowState): WorkoutFlowState {
  validateStateShape(state)
  return { ...state, syncStatus: 'OFFLINE_PENDING' }
}

export function restoreWorkoutFlow(value: unknown): WorkoutFlowState {
  if (!isRecord(value)
    || value.schemaVersion !== 1
    || typeof value.clientSessionKey !== 'string'
    || typeof value.planVersionId !== 'string'
    || !Array.isArray(value.exercises)
    || !Number.isSafeInteger(value.currentExerciseIndex)
    || !Number.isSafeInteger(value.currentSetIndex)
    || !(value.restTimer === null || isRecord(value.restTimer))
    || !['LOCAL_ONLY', 'OFFLINE_PENDING', 'SYNCED', 'CONFLICT', 'SYNC_REJECTED'].includes(String(value.syncStatus))
    || typeof value.automaticProgressionEligible !== 'boolean'
    || !(typeof value.safetyNotice === 'string' || value.safetyNotice === null)) {
    throw new Error('workout state is invalid')
  }
  const state = value as unknown as WorkoutFlowState
  try {
    validateStateShape(state)
  } catch {
    throw new Error('workout state is invalid')
  }
  return state
}

export function summarizeWorkout(state: WorkoutFlowState): {
  completedWorkSets: number
  completedVolumeKg: number
  failedSets: number
  skippedSets: number
  complete: boolean
} {
  validateStateShape(state)
  const sets = state.exercises.flatMap((exercise) => exercise.sets)
  const completed = sets.filter((set) => set.setType === 'WORK' && set.status === 'COMPLETED')
  const failedSets = sets.filter((set) => set.status === 'FAILED').length
  const skippedSets = sets.filter((set) => set.status === 'SKIPPED').length
  const required = state.exercises.reduce((sum, exercise) => sum + exercise.targetWorkSets, 0)
  return {
    completedWorkSets: completed.length,
    completedVolumeKg: completed.reduce(
      (sum, set) => sum + (set.actualWeightKg ?? 0) * (set.actualReps ?? 0),
      0,
    ),
    failedSets,
    skippedSets,
    complete: completed.length >= required && failedSets === 0 && skippedSets === 0,
  }
}

function normalizeSet(input: {
  readonly clientSetKey: string
  readonly setType: WorkoutSetType
  readonly status: WorkoutSetStatus
  readonly actualWeightKg?: number | null
  readonly actualReps?: number | null
  readonly rir?: WorkoutRir
  readonly discomfort?: 'NONE' | 'DISCOMFORT' | 'PAIN'
}): WorkoutSetRecord {
  if (input.clientSetKey.trim().length === 0) throw new Error('clientSetKey is required')
  const actualWeightKg = input.actualWeightKg ?? null
  const actualReps = input.actualReps ?? null
  if (actualWeightKg !== null && (!Number.isFinite(actualWeightKg) || actualWeightKg < 0)) {
    throw new Error('actualWeightKg must be zero or greater')
  }
  if (actualReps !== null && (!Number.isSafeInteger(actualReps) || actualReps < 0)) {
    throw new Error('actualReps must be a non-negative integer')
  }
  if (input.status === 'COMPLETED' && (actualReps === null || actualReps <= 0 || actualWeightKg === null)) {
    throw new Error('completed sets require actual weight and repetitions')
  }
  return {
    clientSetKey: input.clientSetKey,
    setType: input.setType,
    status: input.status,
    actualWeightKg,
    actualReps,
    rir: input.rir ?? 'UNKNOWN',
    discomfort: input.discomfort ?? 'NONE',
  }
}

function derivePosition(exercises: readonly WorkoutExerciseState[]): Pick<WorkoutFlowState, 'currentExerciseIndex' | 'currentSetIndex'> {
  for (let exerciseIndex = 0; exerciseIndex < exercises.length; exerciseIndex += 1) {
    const exercise = exercises[exerciseIndex]
    const attemptedWorkSets = exercise.sets.filter((set) => set.setType === 'WORK').length
    if (attemptedWorkSets < exercise.targetWorkSets) {
      return { currentExerciseIndex: exerciseIndex, currentSetIndex: attemptedWorkSets }
    }
  }
  const lastIndex = exercises.length - 1
  return { currentExerciseIndex: lastIndex, currentSetIndex: exercises[lastIndex].targetWorkSets }
}

function validateStateShape(state: WorkoutFlowState): void {
  if (state.schemaVersion !== 1 || state.clientSessionKey.trim().length === 0 || state.planVersionId.trim().length === 0) {
    throw new Error('workout state is invalid')
  }
  if (!Array.isArray(state.exercises) || state.exercises.length === 0) throw new Error('workout state is invalid')
  state.exercises.forEach((exercise) => {
    validateExercise(exercise)
    if (!Array.isArray(exercise.sets)) throw new Error('workout state is invalid')
    exercise.sets.forEach((set: WorkoutSetRecord) => normalizeSet(set))
  })
}

function validateExercise(exercise: WorkoutExerciseSnapshot): void {
  if (exercise.snapshotExerciseKey.trim().length === 0
    || exercise.exerciseCode.trim().length === 0
    || exercise.name.trim().length === 0
    || !Number.isSafeInteger(exercise.targetWorkSets) || exercise.targetWorkSets <= 0
    || !Number.isSafeInteger(exercise.targetReps) || exercise.targetReps <= 0
    || !Number.isSafeInteger(exercise.restSeconds) || exercise.restSeconds <= 0) {
    throw new Error('workout exercise snapshot is invalid')
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
