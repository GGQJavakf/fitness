import { adjustRestTimer, resumeRestTimer, skipRestTimer, startRestTimer } from '../../domain/workout/RestTimer'
import { acknowledgeOperation, enqueueOperation } from '../../domain/sync/OperationQueue'
import type { Clock } from '../ports/Clock'
import type { WorkoutDraftStore } from '../ports/WorkoutDraftStore'
import type { WorkoutOperationSyncPort } from '../ports/WorkoutOperationSyncPort'
import type { WorkoutCompletionPort, WorkoutCompletionResult, WorkoutCompletionType } from '../ports/WorkoutCompletionPort'
import type { ExerciseReplacementCandidate, WorkoutReplacementPort } from '../ports/WorkoutReplacementPort'
import {
  createWorkoutFlow,
  beginWorkSets,
  completeGeneralWarmup,
  isWorkoutPrescriptionFinished,
  recordWorkoutSet,
  replaceExerciseForSession,
  setWorkoutExerciseWeight,
  type RecordWorkoutSetInput,
  type WorkoutExerciseSnapshot,
  type WorkoutFlowState,
  type WorkoutRir,
} from '../workoutFlow'
import { restoreFlowFromDraft, toWorkoutDraft } from '../workoutFlowDraftMapper'

export class WorkoutFlowService {
  constructor(
    private readonly drafts: WorkoutDraftStore,
    private readonly clock: Clock,
    private readonly remote?: WorkoutOperationSyncPort,
    private readonly completion?: WorkoutCompletionPort,
    private readonly replacements?: WorkoutReplacementPort,
  ) {}

  async start(input: {
    clientSessionKey: string
    planVersionId: string
    exercises: readonly WorkoutExerciseSnapshot[]
    warmupDurationSeconds?: 180 | 300 | 480
    serverSessionId?: string
    serverVersion?: number
  }): Promise<WorkoutFlowState> {
    const created = createWorkoutFlow(input)
    const state = {
      ...created,
      warmup: {
        ...created.warmup,
        generalTimer: startRestTimer({
          sourceSetKey: `${input.clientSessionKey}-general-warmup`,
          configuredDurationSeconds: created.warmup.generalDurationSeconds,
          nowUtc: this.clock.nowUtc(),
        }),
      },
    }
    const draft = toWorkoutDraft(state, null, this.clock.nowUtc())
    await this.drafts.save({
      ...draft,
      sessionId: input.serverSessionId ?? null,
      lastServerVersion: input.serverVersion ?? 0,
    })
    return state
  }

  async load(): Promise<WorkoutFlowState | null> {
    const draft = await this.drafts.loadActive()
    return draft ? restoreFlowFromDraft(draft) : null
  }

  async recordSet(state: WorkoutFlowState, input: RecordWorkoutSetInput): Promise<WorkoutFlowState> {
    let updated = recordWorkoutSet(state, input)
    if (updated === state) return state
    const prescriptionFinished = isWorkoutPrescriptionFinished(updated)
    if (prescriptionFinished) {
      updated = { ...updated, restTimer: null }
    } else if (input.setType === 'WORK' && input.status === 'COMPLETED') {
      const exercise = updated.exercises[input.exerciseIndex]
      updated = {
        ...updated,
        restTimer: startRestTimer({
          sourceSetKey: input.clientSetKey,
          configuredDurationSeconds: exercise.restSeconds,
          nowUtc: this.clock.nowUtc(),
        }),
      }
    }
    const previous = await this.drafts.loadActive()
    if (!previous || previous.clientSessionKey !== state.clientSessionKey) {
      throw new Error('active workout draft does not match the workout session')
    }
    if (!previous.sessionId) return this.save(updated)
    updated = { ...updated, syncStatus: 'OFFLINE_PENDING' }
    const draft = toWorkoutDraft(updated, previous, this.clock.nowUtc())
    const record = updated.exercises[input.exerciseIndex].sets
      .find((set) => set.clientSetKey === input.clientSetKey)!
    const pendingBefore = previous.queue.operations.filter((operation) => operation.status === 'PENDING').length
    const queued = enqueueOperation(draft.queue, {
      idempotencyKey: input.clientSetKey,
      type: 'UPSERT_SET',
      createdAtUtc: this.clock.nowUtc(),
      payload: {
        sessionId: previous.sessionId,
        sessionExerciseId: updated.exercises[input.exerciseIndex].snapshotExerciseKey,
        setType: record.setType,
        setOrder: input.setType === 'WARMUP'
          ? updated.exercises[input.exerciseIndex].sets.filter((set) => set.setType === 'WARMUP').length
          : state.currentSetIndex + 1,
        target: { weight: { value: record.actualWeightKg ?? 0, unit: 'KG' }, reps: input.setType === 'WARMUP' ? (record.actualReps ?? 0) : updated.exercises[input.exerciseIndex].targetReps },
        actual: { weight: { value: record.actualWeightKg ?? 0, unit: 'KG' }, reps: record.actualReps ?? 0 },
        remainingReps: toRemainingReps(record.rir),
        completionStatus: record.status,
        completedAt: record.status === 'COMPLETED' ? this.clock.nowUtc() : undefined,
        expectedSessionVersion: previous.lastServerVersion + pendingBefore,
        confirmAnomaly: false,
      },
    })
    await this.drafts.save({ ...draft, queue: queued.queue })
    return updated
  }

  async resume(state: WorkoutFlowState): Promise<{
    state: WorkoutFlowState
    remainingSeconds: number
    warmupRemainingSeconds: number
    clockRollbackDetected: boolean
    syncFailed: boolean
  }> {
    const nowUtc = this.clock.nowUtc()
    const rest = state.restTimer ? resumeRestTimer(state.restTimer, nowUtc) : null
    const general = state.warmup.generalTimer ? resumeRestTimer(state.warmup.generalTimer, nowUtc) : null
    const updated = await this.save({
      ...state,
      restTimer: rest?.timer ?? state.restTimer,
      warmup: { ...state.warmup, generalTimer: general?.timer ?? state.warmup.generalTimer },
    })
    let recovered = updated
    let syncFailed = false
    try {
      recovered = await this.flush(updated)
    } catch {
      syncFailed = true
    }
    return {
      state: recovered,
      remainingSeconds: rest?.remainingSeconds ?? 0,
      warmupRemainingSeconds: general?.remainingSeconds ?? 0,
      clockRollbackDetected: Boolean(rest?.clockRollbackDetected || general?.clockRollbackDetected),
      syncFailed,
    }
  }

  async completeGeneralWarmup(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    return this.save(completeGeneralWarmup(state))
  }

  async beginWorkSets(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    return this.save(beginWorkSets(state))
  }

  async setExerciseWeight(
    state: WorkoutFlowState,
    exerciseIndex: number,
    weightKg: number,
  ): Promise<WorkoutFlowState> {
    return this.save(setWorkoutExerciseWeight(state, exerciseIndex, weightKg))
  }

  async adjustRest(state: WorkoutFlowState, seconds: 15 | -15): Promise<WorkoutFlowState> {
    if (!state.restTimer) return state
    return this.save({ ...state, restTimer: adjustRestTimer(state.restTimer, seconds, this.clock.nowUtc()) })
  }

  async skipRest(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    if (!state.restTimer) return state
    return this.save({ ...state, restTimer: skipRestTimer(state.restTimer, this.clock.nowUtc()) })
  }

  async finishRest(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    if (!state.restTimer) return state
    const resumed = resumeRestTimer(state.restTimer, this.clock.nowUtc())
    if (resumed.timer.timerStatus === 'RUNNING') return state
    return this.save({ ...state, restTimer: resumed.timer })
  }

  async flush(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    if (!this.remote) return state
    const draft = await this.drafts.loadActive()
    if (!draft || draft.clientSessionKey !== state.clientSessionKey) return state
    const pending = draft.queue.operations.filter((operation) => operation.status === 'PENDING' && operation.type === 'UPSERT_SET')
    if (pending.length === 0) return restoreFlowFromDraft(draft)
    const results = await this.remote.syncWorkoutOperations(pending.map((operation) => ({
      clientOperationSeq: operation.clientOperationSeq,
      operationType: 'UPSERT_SET' as const,
      clientKey: operation.idempotencyKey,
      payload: operation.payload as Readonly<Record<string, unknown>>,
    })))
    const latest = await this.drafts.loadActive()
    if (!latest || latest.clientSessionKey !== state.clientSessionKey) return state
    const persisted = restoreFlowFromDraft(latest)
    let queue = latest.queue
    let serverVersion = latest.lastServerVersion
    let syncStatus: WorkoutFlowState['syncStatus'] = 'SYNCED'
    for (const result of results) {
      const operation = pending.find((item) => item.clientOperationSeq === result.clientOperationSeq)
      if (!operation) continue
      if (result.status === 'CONFLICT') syncStatus = 'CONFLICT'
      if (result.status === 'REJECTED') syncStatus = 'SYNC_REJECTED'
      if (result.status === 'APPLIED' || result.status === 'DUPLICATE') {
        queue = acknowledgeOperation(queue, result.clientOperationSeq)
        const expected = Number((operation.payload as { expectedSessionVersion?: unknown }).expectedSessionVersion)
        if (Number.isSafeInteger(expected)) serverVersion = Math.max(serverVersion, expected + 1)
      }
    }
    if (queue.operations.some((operation) => operation.status === 'PENDING') && syncStatus === 'SYNCED') {
      syncStatus = 'OFFLINE_PENDING'
    }
    const updated = { ...persisted, syncStatus }
    const mapped = toWorkoutDraft(updated, latest, this.clock.nowUtc())
    await this.drafts.save({ ...mapped, queue, lastServerVersion: serverVersion })
    return updated
  }

  async complete(state: WorkoutFlowState, completionType: WorkoutCompletionType): Promise<WorkoutCompletionResult> {
    if (!this.completion) throw new Error('workout completion is unavailable')
    const synchronized = await this.flush(state)
    const draft = await this.drafts.loadActive()
    if (!draft || !draft.sessionId || draft.clientSessionKey !== state.clientSessionKey) {
      throw new Error('server workout session is unavailable')
    }
    if (draft.queue.operations.some((operation) => operation.status === 'PENDING')
      || synchronized.syncStatus === 'CONFLICT'
      || synchronized.syncStatus === 'SYNC_REJECTED') {
      throw new Error('workout facts must be synchronized before completion')
    }
    const result = await this.completion.completeWorkout(
      draft.sessionId,
      { expectedVersion: draft.lastServerVersion, completionType },
      `${state.clientSessionKey}-complete-${completionType}`,
    )
    await this.drafts.clearActive(draft.draftId)
    return result
  }

  async replacementCandidates(state: WorkoutFlowState): Promise<readonly ExerciseReplacementCandidate[]> {
    if (!this.replacements) throw new Error('exercise replacement is unavailable')
    return this.replacements.listExerciseReplacements(state.exercises[state.currentExerciseIndex].exerciseCode)
  }

  async replaceCurrentExercise(
    state: WorkoutFlowState, candidate: ExerciseReplacementCandidate,
  ): Promise<WorkoutFlowState> {
    if (!this.replacements) throw new Error('exercise replacement is unavailable')
    const synchronized = await this.flush(state)
    const draft = await this.drafts.loadActive()
    if (!draft || !draft.sessionId || draft.clientSessionKey !== state.clientSessionKey
      || draft.queue.operations.some((operation) => operation.status === 'PENDING')
      || synchronized.syncStatus === 'CONFLICT' || synchronized.syncStatus === 'SYNC_REJECTED') {
      throw new Error('workout facts must be synchronized before replacement')
    }
    const index = state.currentExerciseIndex
    const current = state.exercises[index]
    const session = await this.replacements.replaceWorkoutExercise(
      draft.sessionId, current.snapshotExerciseKey, candidate.code, draft.lastServerVersion,
    )
    const effective = session.exercises.find((exercise) => exercise.id === current.snapshotExerciseKey)
    if (!effective) throw new Error('replacement response is missing the current exercise')
    const updated = replaceExerciseForSession(synchronized, index, {
      snapshotExerciseKey: effective.id,
      exerciseCode: effective.exerciseCode,
      name: effective.exerciseName,
      targetWorkSets: effective.prescription.workSets,
      targetReps: effective.prescription.repMax,
      restSeconds: effective.prescription.restSeconds,
    })
    const mapped = toWorkoutDraft(updated, draft, this.clock.nowUtc())
    await this.drafts.save({ ...mapped, lastServerVersion: session.version })
    return updated
  }

  private async save(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    const previous = await this.drafts.loadActive()
    await this.drafts.save(toWorkoutDraft(state, previous, this.clock.nowUtc()))
    return state
  }
}

function toRemainingReps(rir: WorkoutRir): number | undefined {
  if (rir === 'UNKNOWN') return undefined
  if (rir === '3_PLUS') return 3
  return Number(rir)
}
