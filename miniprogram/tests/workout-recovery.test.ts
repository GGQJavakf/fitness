import { describe, expect, it } from 'vitest'

import { resumeRestTimer, startRestTimer } from '../src/domain/workout/RestTimer'
import {
  beginWorkSets,
  completeGeneralWarmup,
  createWorkoutFlow,
  markWorkoutSyncPending,
  recordWorkoutSet,
  restoreWorkoutFlow,
} from '../src/application/workoutFlow'
import { restoreFlowFromDraft, toWorkoutDraft } from '../src/application/workoutFlowDraftMapper'
import type { WorkoutDraft, WorkoutDraftStore } from '../src/application/ports/WorkoutDraftStore'
import { WorkoutFlowService } from '../src/application/use-cases/WorkoutFlowService'

describe('workout recovery', () => {
  it('restores position, completed facts, offline status, and timestamp rest state after process loss', () => {
    let state = createWorkoutFlow({
      clientSessionKey: 'recover-session',
      planVersionId: 'plan-version-9',
      exercises: [{
        snapshotExerciseKey: 'exercise-a', exerciseCode: 'row', name: '划船',
        targetWorkSets: 2, targetReps: 8, restSeconds: 90,
      }],
    })
    state = beginWorkSets(completeGeneralWarmup(state))
    state = recordWorkoutSet(state, {
      clientSetKey: 'set-a', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 30, actualReps: 8,
    })
    state = markWorkoutSyncPending({
      ...state,
      restTimer: startRestTimer({
        sourceSetKey: 'set-a', configuredDurationSeconds: 90, nowUtc: '2026-07-24T09:00:00.000Z',
      }),
    })

    const persisted = toWorkoutDraft(state, null, '2026-07-24T09:00:01.000Z')
    const restored = restoreFlowFromDraft(JSON.parse(JSON.stringify(persisted)))
    const rest = resumeRestTimer(restored.restTimer!, '2026-07-24T09:01:00.000Z')

    expect(restored.currentExerciseIndex).toBe(0)
    expect(restored.currentSetIndex).toBe(1)
    expect(restored.exercises[0].sets[0].status).toBe('COMPLETED')
    expect(restored.syncStatus).toBe('OFFLINE_PENDING')
    expect(restored.warmup.phase).toBe('WORK')
    expect(rest.remainingSeconds).toBe(30)
  })

  it('restores a timestamp-based general warmup timer after process loss', async () => {
    let stored: WorkoutDraft | null = null
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft } },
      { nowUtc: () => '2026-07-24T09:00:00.000Z' },
    )
    const started = await service.start({
      clientSessionKey: 'warmup-session', planVersionId: 'plan-version', warmupDurationSeconds: 300 as const,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }],
    })

    const restored = await service.load()

    expect(started.warmup.generalTimer?.configuredDurationSeconds).toBe(300)
    expect(restored?.warmup).toMatchObject({ phase: 'GENERAL', generalDurationSeconds: 300 })

    const ramp = await service.completeGeneralWarmup(restored!)
    expect(ramp.warmup).toMatchObject({ phase: 'RAMP', generalTimer: { timerStatus: 'SKIPPED' } })
  })

  it('rejects malformed persisted state instead of fabricating completed work', () => {
    expect(() => restoreWorkoutFlow({ clientSessionKey: 'broken' })).toThrow(/workout state/i)
  })

  it('atomically keeps a completed set and its pending server operation in the active draft', async () => {
    let stored: WorkoutDraft | null = null
    const store: WorkoutDraftStore = {
      loadActive: async () => stored,
      save: async (draft) => { stored = draft },
    }
    const service = new WorkoutFlowService(
      store,
      { nowUtc: () => '2026-07-24T09:00:00.000Z' },
      {
        syncWorkoutOperations: async (operations) => operations.map((operation) => ({
          clientOperationSeq: operation.clientOperationSeq,
          status: 'APPLIED' as const,
        })),
      },
    )
    let state = await service.start({
      clientSessionKey: 'server-session-key',
      planVersionId: '00000000-0000-0000-0000-000000000010',
      serverSessionId: '00000000-0000-0000-0000-000000000020',
      serverVersion: 1,
      exercises: [{
        snapshotExerciseKey: '00000000-0000-0000-0000-000000000030',
        exerciseCode: 'row', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 90,
      }],
    })

    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    const updated = await service.recordSet(state, {
      clientSetKey: 'set-server-0001', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 30, actualReps: 8, rir: '3_PLUS',
    })

    expect(updated.syncStatus).toBe('OFFLINE_PENDING')
    expect(stored!.setRecords).toHaveLength(1)
    expect(stored!.setRecords[0]).toMatchObject({ rir: '3_PLUS' })
    expect(stored!.queue.operations).toHaveLength(1)
    expect(stored!.queue.operations[0]).toMatchObject({
      idempotencyKey: 'set-server-0001',
      type: 'UPSERT_SET',
      payload: { completionStatus: 'COMPLETED', expectedSessionVersion: 1, remainingReps: 3 },
    })

    const synced = await service.flush(updated)
    expect(synced.syncStatus).toBe('SYNCED')
    expect(stored!.queue.operations).toHaveLength(0)
    expect(stored!.lastServerVersion).toBe(2)
  })

  it('queues successive ramp warmup sets with their own order and no work-set rest timer', async () => {
    let stored: WorkoutDraft | null = null
    const store: WorkoutDraftStore = { loadActive: async () => stored, save: async (draft) => { stored = draft } }
    const service = new WorkoutFlowService(store, { nowUtc: () => '2026-07-24T09:00:00.000Z' })
    let state = await service.start({
      clientSessionKey: 'ramp-server-session', planVersionId: 'plan-version', serverSessionId: 'session-id', serverVersion: 0,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'SQUAT', name: '深蹲', targetWorkSets: 3, targetReps: 8, restSeconds: 90 }],
    })
    state = await service.completeGeneralWarmup(state)
    state = await service.recordSet(state, { clientSetKey: 'ramp-set-0001', exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED', actualWeightKg: 10, actualReps: 10 })
    state = await service.recordSet(state, { clientSetKey: 'ramp-set-0002', exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED', actualWeightKg: 15, actualReps: 8 })

    expect(state.currentSetIndex).toBe(0)
    expect(state.restTimer).toBeNull()
    expect(stored!.queue.operations.map((operation) => operation.payload)).toMatchObject([
      { setType: 'WARMUP', setOrder: 1, target: { reps: 10 } },
      { setType: 'WARMUP', setOrder: 2, target: { reps: 8 } },
    ])
  })

  it('synchronizes pending facts before idempotent early completion', async () => {
    let stored: WorkoutDraft | null = null
    const store: WorkoutDraftStore = {
      loadActive: async () => stored,
      save: async (draft) => { stored = draft },
    }
    const completionRequests: unknown[] = []
    const service = new WorkoutFlowService(
      store,
      { nowUtc: () => '2026-07-24T09:00:00.000Z' },
      { syncWorkoutOperations: async (operations) => operations.map((operation) => ({ clientOperationSeq: operation.clientOperationSeq, status: 'APPLIED' as const })) },
      { completeWorkout: async (sessionId, request, key) => {
        completionRequests.push({ sessionId, request, key })
        return { session: { status: 'ABORTED', version: 4 }, completedWorkSets: 1, complete: false, automaticProgressionEligible: false }
      } },
    )
    const started = await service.start({
      clientSessionKey: 'server-session-key', planVersionId: 'plan-version', serverSessionId: 'session-id', serverVersion: 1,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }],
    })
    const ready = await service.beginWorkSets(await service.completeGeneralWarmup(started))
    const recorded = await service.recordSet(ready, {
      clientSetKey: 'set-key-0001', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED', actualWeightKg: 20, actualReps: 8,
    })

    const result = await service.complete(recorded, 'EARLY_END')

    expect(completionRequests).toEqual([{ sessionId: 'session-id', request: { expectedVersion: 2, completionType: 'EARLY_END' }, key: 'server-session-key-complete-EARLY_END' }])
    expect(result.automaticProgressionEligible).toBe(false)
    expect(stored!.lastServerVersion).toBe(4)
    expect(stored!.queue.operations).toHaveLength(0)
  })

  it('persists a server-approved replacement only in the current workout draft', async () => {
    let stored: WorkoutDraft | null = null
    const store: WorkoutDraftStore = { loadActive: async () => stored, save: async (draft) => { stored = draft } }
    const service = new WorkoutFlowService(
      store, { nowUtc: () => '2026-07-24T09:00:00.000Z' }, undefined, undefined,
      {
        listExerciseReplacements: async () => [{ id: 'replacement-id', code: 'SAFE_ROW', name: '安全划船', movementPattern: 'HORIZONTAL_PULL', difficulty: 'BEGINNER', equipment: ['CABLE'], primaryMuscles: ['BACK'] }],
        replaceWorkoutExercise: async () => ({ version: 2, exercises: [{ id: 'exercise-id', exerciseCode: 'SAFE_ROW', exerciseName: '安全划船', prescription: { workSets: 2, repMax: 10, restSeconds: 60 } }] }),
      },
    )
    const state = await service.start({ clientSessionKey: 'replace-session', planVersionId: 'plan-version', serverSessionId: 'session-id', serverVersion: 1,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }] })
    const candidate = (await service.replacementCandidates(state))[0]

    const updated = await service.replaceCurrentExercise(state, candidate)

    expect(updated.exercises[0]).toMatchObject({ exerciseCode: 'SAFE_ROW', replacedExerciseCode: 'ROW', targetReps: 10 })
    expect(stored!.lastServerVersion).toBe(2)
    expect(stored!.planSnapshot).toMatchObject({ planVersionId: 'plan-version' })
  })
})
