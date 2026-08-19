import type { RestTimerState } from '../domain/workout/RestTimer'

export type WorkoutSetType = 'WARMUP' | 'WORK' | 'EXTRA'
export type WorkoutSetStatus = 'COMPLETED' | 'FAILED' | 'SKIPPED'
export type WorkoutRir = '0' | '1' | '2' | '3_PLUS' | 'UNKNOWN'
export type WorkoutSyncStatus = 'LOCAL_ONLY' | 'OFFLINE_PENDING' | 'SYNCED' | 'CONFLICT' | 'SYNC_REJECTED'
export type WarmupPhase = 'GENERAL' | 'RAMP' | 'WORK'
export type RampWarmupStatus = 'READY' | 'CALIBRATION_REQUIRED' | 'NOT_REQUIRED'
export type WorkoutSafetyFlag = 'PAIN' | 'INJURY' | 'CHEST_DISCOMFORT' | 'DIZZINESS' | 'SEVERE_UNWELL'

export interface WarmupExecutionState {
  readonly phase: WarmupPhase
  readonly prescriptionVersion: string
  readonly ruleVersion: string
  readonly generalDurationSeconds: number
  readonly generalTimer: RestTimerState | null
  readonly rampExerciseIndex: number | null
  readonly rampStatus: RampWarmupStatus
  readonly rampSets: readonly RampWarmupSet[]
  readonly calibrationMessage: string | null
  readonly maximumRampSets: number
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
  readonly safetyFlag: WorkoutSafetyFlag | null
  /** Legacy local-only field retained so schemaVersion 1 drafts remain recoverable. */
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

export interface WorkoutWarmupPrescriptionSnapshot {
  readonly schemaVersion: 'workout-warmup-prescription-v1'
  readonly ruleVersion: string
  readonly generalWarmup: {
    readonly occurrences: 1
    readonly durationSeconds: number
  }
  readonly rampWarmup?: {
    readonly exerciseId: string
    readonly exerciseOrder: number
    readonly status: 'READY' | 'CALIBRATION_REQUIRED'
    readonly sets: readonly RampWarmupSet[]
    readonly calibrationMessage?: string
  }
  readonly countsTowardTrainingVolume: false
  readonly countsTowardProgression: false
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
  readonly safetyFlag?: WorkoutSafetyFlag
  readonly discomfort?: 'NONE' | 'DISCOMFORT' | 'PAIN'
}

export function createWorkoutFlow(input: {
  clientSessionKey: string
  planVersionId: string
  exercises: readonly WorkoutExerciseSnapshot[]
  warmupDurationSeconds?: 180 | 300 | 480
  warmupPrescription?: WorkoutWarmupPrescriptionSnapshot
}): WorkoutFlowState {
  if (input.clientSessionKey.trim().length === 0 || input.planVersionId.trim().length === 0) {
    throw new Error('workout session and plan version are required')
  }
  if (input.exercises.length === 0) throw new Error('workout requires at least one exercise')
  input.exercises.forEach(validateExercise)
  const warmup = createWarmupState(input)
  const state: WorkoutFlowState = {
    schemaVersion: 1,
    clientSessionKey: input.clientSessionKey,
    planVersionId: input.planVersionId,
    exercises: input.exercises.map(createExerciseState),
    currentExerciseIndex: 0,
    currentSetIndex: 0,
    restTimer: null,
    warmup,
    syncStatus: 'LOCAL_ONLY',
    automaticProgressionEligible: true,
    safetyNotice: null,
  }
  validateStateShape(state)
  return state
}

export function completeGeneralWarmup(state: WorkoutFlowState): WorkoutFlowState {
  validateStateShape(state)
  if (state.warmup.phase !== 'GENERAL') return state
  const generalTimer = state.warmup.generalTimer?.timerStatus === 'RUNNING'
    ? { ...state.warmup.generalTimer, timerStatus: 'SKIPPED' as const }
    : state.warmup.generalTimer
  const nextPhase: WarmupPhase = state.warmup.rampExerciseIndex === null ? 'WORK' : 'RAMP'
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
  if (state.warmup.rampExerciseIndex === null) return 0
  return state.exercises[state.warmup.rampExerciseIndex].sets
    .filter((set) => set.setType === 'WARMUP' && set.status === 'COMPLETED').length
}

export function remainingRampWarmupSets(state: WorkoutFlowState): readonly RampWarmupSet[] {
  validateStateShape(state)
  const exerciseIndex = state.warmup.rampExerciseIndex
  if (exerciseIndex === null || state.warmup.rampStatus !== 'READY') return []
  const exercise = state.exercises[exerciseIndex]
  const completed = exercise.sets
    .filter((set) => set.setType === 'WARMUP' && set.status === 'COMPLETED')
  const highestCompletedWeight = completed
    .map((set) => set.actualWeightKg)
    .filter((weight): weight is number => weight !== null)
    .reduce((highest, weight) => Math.max(highest, weight), 0)
  return state.warmup.rampSets
    .slice(Math.min(completed.length, state.warmup.rampSets.length))
    .filter((set) => set.weightKg > highestCompletedWeight)
    .filter((set) => exercise.sessionWeightKg === undefined || set.weightKg < exercise.sessionWeightKg)
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
    if (state.warmup.rampExerciseIndex === null || input.exerciseIndex !== state.warmup.rampExerciseIndex) {
      throw new Error('ramp warmup sets belong to the server-prescribed exercise')
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
  const unsafe = record.safetyFlag !== null || record.discomfort !== 'NONE'
  return {
    ...state,
    exercises,
    ...position,
    syncStatus: 'LOCAL_ONLY',
    automaticProgressionEligible: state.automaticProgressionEligible && !unsafe,
    safetyNotice: unsafe ? workoutSafetyNotice(record.safetyFlag) : state.safetyNotice,
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
  const candidate = value as unknown as WorkoutFlowState
  const warmup = normalizeRestoredWarmup(candidate.warmup)
  const state = { ...candidate, warmup }
  try {
    validateStateShape(state)
  } catch {
    throw new Error('workout state is invalid')
  }
  const exercises = state.exercises.map((exercise) => {
    const normalized = { ...exercise, sets: exercise.sets.map((set) => normalizeSet(set)) }
    return normalized.weightStatus !== 'BODYWEIGHT'
        && normalized.sessionWeightKg === undefined
        && initialSessionWeight(normalized) !== undefined
      ? { ...normalized, sessionWeightKg: initialSessionWeight(normalized) }
      : normalized
  })
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
  readonly safetyFlag?: WorkoutSafetyFlag | null
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
    safetyFlag: input.safetyFlag ?? (input.discomfort === 'PAIN' ? 'PAIN' : null),
    discomfort: input.discomfort ?? 'NONE',
  }
}

export function workoutSafetyNotice(flag: WorkoutSafetyFlag | null): string {
  if (flag === 'CHEST_DISCOMFORT' || flag === 'DIZZINESS' || flag === 'SEVERE_UNWELL') {
    return '请立即停止训练并寻求身边帮助；如情况严重、持续或加重，请联系当地急救服务。本提示不作诊断。'
  }
  if (flag === 'PAIN' || flag === 'INJURY') {
    return '请立即停止训练，不要勉强继续；如需帮助，请咨询合格专业人员。本提示不作诊断。'
  }
  return '出现明显不适，请停止训练；如需帮助，请咨询合格专业人员。本提示不作诊断。'
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
    || typeof state.warmup.prescriptionVersion !== 'string' || state.warmup.prescriptionVersion.length === 0
    || typeof state.warmup.ruleVersion !== 'string' || state.warmup.ruleVersion.length === 0
    || !Number.isSafeInteger(state.warmup.generalDurationSeconds) || state.warmup.generalDurationSeconds <= 0
    || !(state.warmup.generalTimer === null || isRecord(state.warmup.generalTimer))
    || !(state.warmup.rampExerciseIndex === null
      || Number.isSafeInteger(state.warmup.rampExerciseIndex)
        && state.warmup.rampExerciseIndex >= 0
        && state.warmup.rampExerciseIndex < state.exercises.length)
    || !['READY', 'CALIBRATION_REQUIRED', 'NOT_REQUIRED'].includes(state.warmup.rampStatus)
    || !Array.isArray(state.warmup.rampSets)
    || !Number.isSafeInteger(state.warmup.maximumRampSets) || state.warmup.maximumRampSets < 0
    || !(typeof state.warmup.calibrationMessage === 'string' || state.warmup.calibrationMessage === null)) {
    throw new Error('workout state is invalid')
  }
  state.warmup.rampSets.forEach((set) => {
    if (!Number.isFinite(set.weightKg) || set.weightKg <= 0
      || !Number.isSafeInteger(set.reps) || set.reps <= 0) throw new Error('workout state is invalid')
  })
  if (state.warmup.rampStatus === 'READY'
    && (state.warmup.rampExerciseIndex === null || state.warmup.rampSets.length === 0)) {
    throw new Error('workout state is invalid')
  }
  if (state.warmup.rampStatus === 'CALIBRATION_REQUIRED'
    && (state.warmup.rampExerciseIndex === null || state.warmup.rampSets.length !== 0
      || state.warmup.calibrationMessage === null)) {
    throw new Error('workout state is invalid')
  }
  if (state.warmup.rampStatus === 'NOT_REQUIRED'
    && (state.warmup.rampExerciseIndex !== null || state.warmup.rampSets.length !== 0)) {
    throw new Error('workout state is invalid')
  }
  if (state.warmup.prescriptionVersion !== 'legacy-client-v1'
    && state.warmup.maximumRampSets !== state.warmup.rampSets.length) {
    throw new Error('workout state is invalid')
  }
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

function createWarmupState(input: {
  readonly exercises: readonly WorkoutExerciseSnapshot[]
  readonly warmupDurationSeconds?: 180 | 300 | 480
  readonly warmupPrescription?: WorkoutWarmupPrescriptionSnapshot
}): WarmupExecutionState {
  const prescription = input.warmupPrescription
  if (!prescription) return legacyWarmupState(input.warmupDurationSeconds)
  if (prescription.schemaVersion !== 'workout-warmup-prescription-v1'
    || typeof prescription.ruleVersion !== 'string'
    || prescription.ruleVersion.trim().length === 0
    || prescription.generalWarmup.occurrences !== 1
    || !Number.isSafeInteger(prescription.generalWarmup.durationSeconds)
    || prescription.generalWarmup.durationSeconds <= 0
    || prescription.countsTowardTrainingVolume !== false
    || prescription.countsTowardProgression !== false) {
    throw new Error('server warmup prescription is invalid')
  }
  const ramp = prescription.rampWarmup
  if (!ramp) {
    return {
      phase: 'GENERAL',
      prescriptionVersion: prescription.schemaVersion,
      ruleVersion: prescription.ruleVersion,
      generalDurationSeconds: prescription.generalWarmup.durationSeconds,
      generalTimer: null,
      rampExerciseIndex: null,
      rampStatus: 'NOT_REQUIRED',
      rampSets: [],
      calibrationMessage: null,
      maximumRampSets: 0,
    }
  }
  const rampExerciseIndex = input.exercises.findIndex((exercise) => exercise.snapshotExerciseKey === ramp.exerciseId)
  if (rampExerciseIndex < 0) throw new Error('server warmup exercise is missing from workout snapshot')
  const rampSets = ramp.sets.map((set) => ({ weightKg: set.weightKg, reps: set.reps }))
  return {
    phase: 'GENERAL',
    prescriptionVersion: prescription.schemaVersion,
    ruleVersion: prescription.ruleVersion,
    generalDurationSeconds: prescription.generalWarmup.durationSeconds,
    generalTimer: null,
    rampExerciseIndex,
    rampStatus: ramp.status,
    rampSets,
    calibrationMessage: ramp.calibrationMessage ?? (ramp.status === 'CALIBRATION_REQUIRED'
      ? '当前无法确定精确器械档位，请先校准正式重量；不会自动生成热身重量。'
      : null),
    maximumRampSets: rampSets.length,
  }
}

function legacyWarmupState(durationSeconds: 180 | 300 | 480 = 180): WarmupExecutionState {
  return {
    phase: 'GENERAL', prescriptionVersion: 'legacy-client-v1', ruleVersion: 'legacy',
    generalDurationSeconds: durationSeconds, generalTimer: null, rampExerciseIndex: null,
    rampStatus: 'NOT_REQUIRED', rampSets: [], calibrationMessage: null, maximumRampSets: 0,
  }
}

function normalizeRestoredWarmup(value: WarmupExecutionState): WarmupExecutionState {
  if (typeof value.prescriptionVersion === 'string') return value
  const legacy = legacyWarmupState(
    Number.isSafeInteger(value.generalDurationSeconds) && value.generalDurationSeconds > 0
      ? value.generalDurationSeconds as 180 | 300 | 480
      : 180,
  )
  const restoredPhase = ['GENERAL', 'WORK'].includes(String(value.phase)) ? value.phase : 'WORK'
  return {
    ...legacy,
    phase: restoredPhase,
    generalTimer: value.generalTimer ?? null,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
