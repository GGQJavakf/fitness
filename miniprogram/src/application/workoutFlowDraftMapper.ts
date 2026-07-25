import { createOperationQueue } from '../domain/sync/OperationQueue'
import type { RestTimerState } from '../domain/workout/RestTimer'
import type { WorkoutDraft } from './ports/WorkoutDraftStore'
import {
  restoreWorkoutFlow,
  type WorkoutExerciseSnapshot,
  type WorkoutExerciseState,
  type WorkoutFlowState,
  type WorkoutSetRecord,
  type WorkoutSyncStatus,
} from './workoutFlow'

interface PersistedPlanSnapshot {
  planVersionId: string
  exercises: readonly Omit<WorkoutExerciseState, 'sets'>[]
  syncStatus: WorkoutSyncStatus
  automaticProgressionEligible: boolean
  safetyNotice: string | null
  warmup: WorkoutFlowState['warmup']
}

interface PersistedSetRecord extends WorkoutSetRecord {
  exerciseIndex: number
}

export function toWorkoutDraft(
  state: WorkoutFlowState,
  previous: WorkoutDraft | null,
  nowUtc: string,
): WorkoutDraft {
  const planSnapshot: PersistedPlanSnapshot = {
    planVersionId: state.planVersionId,
    exercises: state.exercises.map(({ sets: _sets, ...exercise }) => exercise),
    syncStatus: state.syncStatus,
    automaticProgressionEligible: state.automaticProgressionEligible,
    safetyNotice: state.safetyNotice,
    warmup: state.warmup,
  }
  const setRecords: PersistedSetRecord[] = state.exercises.flatMap((exercise, exerciseIndex) =>
    exercise.sets.map((set) => ({ ...set, exerciseIndex })))
  return {
    schemaVersion: 1,
    draftId: previous?.draftId ?? `workout-${state.clientSessionKey}`,
    revision: previous?.clientSessionKey === state.clientSessionKey ? previous.revision + 1 : 0,
    clientSessionKey: state.clientSessionKey,
    sessionId: previous?.clientSessionKey === state.clientSessionKey ? previous.sessionId : null,
    planSnapshot: planSnapshot as unknown as Readonly<Record<string, unknown>>,
    currentExerciseIndex: state.currentExerciseIndex,
    currentSetIndex: state.currentSetIndex,
    setRecords: setRecords as unknown as readonly Readonly<Record<string, unknown>>[],
    restTimer: state.restTimer as unknown as Readonly<Record<string, unknown>> | null,
    queue: previous?.clientSessionKey === state.clientSessionKey ? previous.queue : createOperationQueue(),
    lastServerVersion: previous?.clientSessionKey === state.clientSessionKey ? previous.lastServerVersion : 0,
    updatedAtUtc: nowUtc,
  }
}

export function restoreFlowFromDraft(draft: WorkoutDraft): WorkoutFlowState {
  const snapshot = draft.planSnapshot as unknown
  if (!isRecord(snapshot) || typeof snapshot.planVersionId !== 'string' || !Array.isArray(snapshot.exercises)) {
    throw new Error('workout state is invalid')
  }
  const exercises = snapshot.exercises.map((exercise) => ({
    ...(exercise as WorkoutExerciseSnapshot),
    sets: [] as PersistedSetRecord[],
  }))
  for (const value of draft.setRecords) {
    if (!isRecord(value) || !Number.isSafeInteger(value.exerciseIndex)) throw new Error('workout state is invalid')
    const exerciseIndex = value.exerciseIndex as number
    if (!exercises[exerciseIndex]) throw new Error('workout state is invalid')
    const { exerciseIndex: _index, ...set } = value as unknown as PersistedSetRecord
    exercises[exerciseIndex].sets.push(set as PersistedSetRecord)
  }
  const warmup = isRecord(snapshot.warmup) ? snapshot.warmup : {
    phase: 'WORK', generalDurationSeconds: 180, generalTimer: null, rampExerciseIndex: 0, maximumRampSets: 3,
  }
  return restoreWorkoutFlow({
    schemaVersion: 1,
    clientSessionKey: draft.clientSessionKey,
    planVersionId: snapshot.planVersionId,
    exercises,
    currentExerciseIndex: draft.currentExerciseIndex,
    currentSetIndex: draft.currentSetIndex,
    restTimer: draft.restTimer as RestTimerState | null,
    warmup,
    syncStatus: typeof snapshot.syncStatus === 'string' ? snapshot.syncStatus : 'LOCAL_ONLY',
    automaticProgressionEligible: typeof snapshot.automaticProgressionEligible === 'boolean'
      ? snapshot.automaticProgressionEligible
      : true,
    safetyNotice: typeof snapshot.safetyNotice === 'string' ? snapshot.safetyNotice : null,
  })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
