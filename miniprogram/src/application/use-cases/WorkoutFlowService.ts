import { adjustRestTimer, resumeRestTimer, skipRestTimer, startRestTimer } from '../../domain/workout/RestTimer'
import { acknowledgeOperation, enqueueOperation } from '../../domain/sync/OperationQueue'
import type { Clock } from '../ports/Clock'
import type { WorkoutDraftStore } from '../ports/WorkoutDraftStore'
import type { WorkoutOperationSyncPort } from '../ports/WorkoutOperationSyncPort'
import {
  createWorkoutFlow,
  recordWorkoutSet,
  type RecordWorkoutSetInput,
  type WorkoutExerciseSnapshot,
  type WorkoutFlowState,
} from '../workoutFlow'
import { restoreFlowFromDraft, toWorkoutDraft } from '../workoutFlowDraftMapper'

export class WorkoutFlowService {
  constructor(
    private readonly drafts: WorkoutDraftStore,
    private readonly clock: Clock,
    private readonly remote?: WorkoutOperationSyncPort,
  ) {}

  async start(input: {
    clientSessionKey: string
    planVersionId: string
    exercises: readonly WorkoutExerciseSnapshot[]
    serverSessionId?: string
    serverVersion?: number
  }): Promise<WorkoutFlowState> {
    const state = createWorkoutFlow(input)
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
    if (input.setType === 'WORK' && input.status === 'COMPLETED') {
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
        setOrder: state.currentSetIndex + 1,
        target: { weight: { value: record.actualWeightKg ?? 0, unit: 'KG' }, reps: updated.exercises[input.exerciseIndex].targetReps },
        actual: { weight: { value: record.actualWeightKg ?? 0, unit: 'KG' }, reps: record.actualReps ?? 0 },
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
    clockRollbackDetected: boolean
  }> {
    if (!state.restTimer) return { state, remainingSeconds: 0, clockRollbackDetected: false }
    const snapshot = resumeRestTimer(state.restTimer, this.clock.nowUtc())
    const updated = await this.save({ ...state, restTimer: snapshot.timer })
    return { state: updated, remainingSeconds: snapshot.remainingSeconds, clockRollbackDetected: snapshot.clockRollbackDetected }
  }

  async adjustRest(state: WorkoutFlowState, seconds: 15 | -15): Promise<WorkoutFlowState> {
    if (!state.restTimer) return state
    return this.save({ ...state, restTimer: adjustRestTimer(state.restTimer, seconds, this.clock.nowUtc()) })
  }

  async skipRest(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    if (!state.restTimer) return state
    return this.save({ ...state, restTimer: skipRestTimer(state.restTimer, this.clock.nowUtc()) })
  }

  async flush(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    if (!this.remote) return state
    const draft = await this.drafts.loadActive()
    if (!draft || draft.clientSessionKey !== state.clientSessionKey) return state
    const pending = draft.queue.operations.filter((operation) => operation.status === 'PENDING' && operation.type === 'UPSERT_SET')
    if (pending.length === 0) return { ...state, syncStatus: 'SYNCED' }
    const results = await this.remote.syncWorkoutOperations(pending.map((operation) => ({
      clientOperationSeq: operation.clientOperationSeq,
      operationType: 'UPSERT_SET' as const,
      clientKey: operation.idempotencyKey,
      payload: operation.payload as Readonly<Record<string, unknown>>,
    })))
    let queue = draft.queue
    let serverVersion = draft.lastServerVersion
    let syncStatus: WorkoutFlowState['syncStatus'] = 'SYNCED'
    for (const result of results) {
      const operation = pending.find((item) => item.clientOperationSeq === result.clientOperationSeq)
      if (!operation) continue
      queue = acknowledgeOperation(queue, result.clientOperationSeq)
      if (result.status === 'CONFLICT') syncStatus = 'CONFLICT'
      if (result.status === 'REJECTED') syncStatus = 'SYNC_REJECTED'
      if (result.status === 'APPLIED' || result.status === 'DUPLICATE') {
        const expected = Number((operation.payload as { expectedSessionVersion?: unknown }).expectedSessionVersion)
        if (Number.isSafeInteger(expected)) serverVersion = Math.max(serverVersion, expected + 1)
      }
    }
    if (queue.operations.some((operation) => operation.status === 'PENDING') && syncStatus === 'SYNCED') {
      syncStatus = 'OFFLINE_PENDING'
    }
    const updated = { ...state, syncStatus }
    const mapped = toWorkoutDraft(updated, draft, this.clock.nowUtc())
    await this.drafts.save({ ...mapped, queue, lastServerVersion: serverVersion })
    return updated
  }

  private async save(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    const previous = await this.drafts.loadActive()
    await this.drafts.save(toWorkoutDraft(state, previous, this.clock.nowUtc()))
    return state
  }
}
