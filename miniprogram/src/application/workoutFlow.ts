import type { RestTimerState } from '../domain/workout/RestTimer'

export type WorkoutSetType = 'WARMUP' | 'WORK' | 'EXTRA'
export type WorkoutSetStatus = 'COMPLETED' | 'FAILED' | 'SKIPPED'
export type WorkoutRir = '0' | '1' | '2' | '3_PLUS' | 'UNKNOWN'
export type WorkoutSyncStatus = 'LOCAL_ONLY' | 'OFFLINE_PENDING' | 'SYNCED' | 'CONFLICT' | 'SYNC_REJECTED'
export type WarmupPhase = 'GENERAL' | 'RAMP' | 'WORK'

export interface WarmupExecutionState {
  readonly phase: WarmupPhase
  readonly generalDurationSeconds: 180 | 300 | 480
  readonly generalTimer: RestTimerState | null
  readonly rampExerciseIndex: number
  readonly maximumRampSets: 2 | 3
}

export interface WorkoutExerciseSnapshot {
  readonly snapshotExerciseKey: string
  readonly exerciseCode: string
  readonly name: string
  readonly targetWorkSets: number
  readonly targetReps: number
  readonly restSeconds: number
  readonly weightStatus?: 'KNOWN' | 'NEEDS_CALIBRATION' | 'BODYWEIGHT'
  readonly targetWeightKg?: number
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
  readonly sessionWeightKg?: number
  readonly sets: readonly WorkoutSetRecord[]
}

export interface RampWarmupSet {
  readonly weightKg: number
  readonly reps: number
}

export interface WorkoutFlowState {
  readonly schemaVersion: 1
  readonly clientSessionKey: string
  readonly planVersionId: string
  readonly exercises: readonly WorkoutExerciseState[]
  readonly currentExerciseIndex: number
  readonly currentSetIndex: number
  readonly restTimer: RestTimerState | null
  readonly warmup: WarmupExecutionState
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
  warmupDurationSeconds?: 180 | 300 | 480
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
    exercises: input.exercises.map(createExerciseState),
    currentExerciseIndex: 0,
    currentSetIndex: 0,
    restTimer: null,
    warmup: {
      phase: 'GENERAL',
      generalDurationSeconds: input.warmupDurationSeconds ?? 180,
      generalTimer: null,
      rampExerciseIndex: 0,
      maximumRampSets: 2,
    },
    syncStatus: 'LOCAL_ONLY',
    automaticProgressionEligible: true,
    safetyNotice: null,
  }
}

export function completeGeneralWarmup(state: WorkoutFlowState): WorkoutFlowState {
  validateStateShape(state)
  if (state.warmup.phase !== 'GENERAL') return state
  const generalTimer = state.warmup.generalTimer?.timerStatus === 'RUNNING'
    ? { ...state.warmup.generalTimer, timerStatus: 'SKIPPED' as const }
    : state.warmup.generalTimer
  const rampExercise = state.exercises[state.warmup.rampExerciseIndex]
  const nextPhase: WarmupPhase = rampExercise?.weightStatus === 'BODYWEIGHT' ? 'WORK' : 'RAMP'
  return { ...state, warmup: { ...state.warmup, phase: nextPhase, generalTimer } }
}

export function beginWorkSets(state: WorkoutFlowState): WorkoutFlowState {
  validateStateShape(state)
  if (state.warmup.phase === 'WORK') return state
  if (state.warmup.phase !== 'RAMP') throw new Error('general warmup must finish before work sets begin')
  return { ...state, warmup: { ...state.warmup, phase: 'WORK' } }
}

export function completedRampSets(state: WorkoutFlowState): number {
  validateStateShape(state)
  return state.exercises[state.warmup.rampExerciseIndex].sets
    .filter((set) => set.setType === 'WARMUP' && set.status === 'COMPLETED').length
}

export function buildRampWarmupSets(formalWeightKg: number): readonly RampWarmupSet[] {
  if (!Number.isFinite(formalWeightKg) || formalWeightKg <= 0) return []
  const candidates = formalWeightKg <= 10
    ? [2, 5]
    : [roundToHalfKg(formalWeightKg * 0.5), roundToHalfKg(formalWeightKg * 0.7)]
  const reps = [10, 6] as const
  return candidates
    .filter((weightKg, index, weights) =>
      weightKg > 0 && weightKg < formalWeightKg && weights.indexOf(weightKg) === index)
    .map((weightKg, index) => ({ weightKg, reps: reps[index] ?? reps[reps.length - 1] }))
}

export function buildRemainingRampWarmupSets(
  formalWeightKg: number,
  completedWeightKg: readonly number[],
): readonly RampWarmupSet[] {
  const completed = completedWeightKg.filter((weightKg) => Number.isFinite(weightKg) && weightKg >= 0)
  const highestCompletedWeight = completed.length === 0 ? 0 : Math.max(...completed)
  return buildRampWarmupSets(formalWeightKg)
    .slice(Math.min(completed.length, 2))
    .filter((set) => set.weightKg > highestCompletedWeight)
}

export function setWorkoutExerciseWeight(
  state: WorkoutFlowState,
  exerciseIndex: number,
  weightKg: number,
): WorkoutFlowState {
  validateStateShape(state)
  const exercise = state.exercises[exerciseIndex]
  if (!exercise) throw new Error('exerciseIndex is outside the workout snapshot')
  if (exercise.weightStatus === 'BODYWEIGHT') throw new Error('bodyweight exercises do not accept a formal weight')
  if (!Number.isFinite(weightKg) || weightKg <= 0) throw new Error('formal weight must be greater than zero')
  if (exercise.sessionWeightKg === weightKg) return state
  return {
    ...state,
    exercises: state.exercises.map((item, index) =>
      index === exerciseIndex ? { ...item, sessionWeightKg: weightKg } : item),
  }
}

export function isWorkoutPrescriptionFinished(state: WorkoutFlowState): boolean {
  validateStateShape(state)
  return state.exercises.every((exercise) =>
    exercise.sets.filter((set) => set.setType === 'WORK').length >= exercise.targetWorkSets)
}

export function recordWorkoutSet(state: WorkoutFlowState, input: RecordWorkoutSetInput): WorkoutFlowState {
  validateStateShape(state)
  const exercise = state.exercises[input.exerciseIndex]
  if (!exercise) throw new Error('exerciseIndex is outside the workout snapshot')
  if (input.setType === 'WORK'
    && input.status !== 'SKIPPED'
    && exercise.weightStatus !== 'BODYWEIGHT'
    && input.actualWeightKg === undefined
    && exercise.sessionWeightKg === undefined) {
    throw new Error('external-load work sets require a formal weight')
  }
  const defaultWorkWeight = input.setType === 'WORK' && input.status !== 'SKIPPED'
    ? exercise.sessionWeightKg
    : undefined
  const record = normalizeSet({
    ...input,
    actualWeightKg: input.actualWeightKg ?? defaultWorkWeight,
  })
  const existing = state.exercises.flatMap((item) => item.sets)
    .find((item) => item.clientSetKey === record.clientSetKey)
  if (existing) {
    if (JSON.stringify(existing) === JSON.stringify(record)) return state
    throw new Error('clientSetKey already identifies different workout facts')
  }
  if (record.setType === 'WORK'
    && exercise.sets.filter((set) => set.setType === 'WORK').length >= exercise.targetWorkSets) {
    throw new Error('prescribed work sets are already complete')
  }
  if (record.setType === 'WARMUP') {
    if (state.warmup.phase !== 'RAMP') throw new Error('ramp warmup sets can only be recorded during ramp warmup')
    if (input.exerciseIndex !== state.warmup.rampExerciseIndex) {
      throw new Error('ramp warmup sets belong to the first prescribed exercise')
    }
    const rampSetCount = exercise.sets.filter((set) => set.setType === 'WARMUP').length
    if (rampSetCount >= state.warmup.maximumRampSets) throw new Error('maximum ramp warmup sets exceeded')
  } else if (state.warmup.phase !== 'WORK') {
    throw new Error('work sets can only be recorded after warmup')
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
    ? {
        ...replacement,
        replacedExerciseCode: current.exerciseCode,
        sessionWeightKg: initialSessionWeight(replacement),
        sets: item.sets,
      }
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
    || !isRecord(value.warmup)
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
  const exercises = state.exercises.map((exercise) => exercise.weightStatus !== 'BODYWEIGHT'
      && exercise.sessionWeightKg === undefined
      && initialSessionWeight(exercise) !== undefined
    ? { ...exercise, sessionWeightKg: initialSessionWeight(exercise) }
    : exercise)
  return { ...state, exercises, ...derivePosition(exercises) }
}

export function summarizeWorkout(state: WorkoutFlowState): {
  completedWorkSets: number
  completedVolumeKg: number
  completedReps: number
  usesExternalLoad: boolean
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
    completedReps: completed.reduce((sum, set) => sum + (set.actualReps ?? 0), 0),
    usesExternalLoad: completed.some((set) => (set.actualWeightKg ?? 0) > 0),
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
  if (!['GENERAL', 'RAMP', 'WORK'].includes(state.warmup.phase)
    || ![180, 300, 480].includes(state.warmup.generalDurationSeconds)
    || !(state.warmup.generalTimer === null || isRecord(state.warmup.generalTimer))
    || state.warmup.rampExerciseIndex !== 0
    || ![2, 3].includes(state.warmup.maximumRampSets)) throw new Error('workout state is invalid')
  state.exercises.forEach((exercise) => {
    validateExercise(exercise)
    if (exercise.sessionWeightKg !== undefined
      && (!Number.isFinite(exercise.sessionWeightKg) || exercise.sessionWeightKg <= 0)) {
      throw new Error('workout state is invalid')
    }
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

function createExerciseState(exercise: WorkoutExerciseSnapshot): WorkoutExerciseState {
  const sessionWeightKg = initialSessionWeight(exercise)
  return {
    ...exercise,
    ...(sessionWeightKg === undefined ? {} : { sessionWeightKg }),
    sets: [],
  }
}

function initialSessionWeight(exercise: WorkoutExerciseSnapshot): number | undefined {
  return exercise.weightStatus !== 'BODYWEIGHT'
    && Number.isFinite(exercise.targetWeightKg)
    && Number(exercise.targetWeightKg) > 0
    ? exercise.targetWeightKg
    : undefined
}

function roundToHalfKg(value: number): number {
  return Math.round(value * 2) / 2
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
