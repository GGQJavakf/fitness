import { describe, expect, it } from 'vitest'

import { resumeRestTimer, startRestTimer } from '../src/domain/workout/RestTimer'
import {
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
    expect(rest.remainingSeconds).toBe(30)
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
    const state = await service.start({
      clientSessionKey: 'server-session-key',
      planVersionId: '00000000-0000-0000-0000-000000000010',
      serverSessionId: '00000000-0000-0000-0000-000000000020',
      serverVersion: 1,
      exercises: [{
        snapshotExerciseKey: '00000000-0000-0000-0000-000000000030',
        exerciseCode: 'row', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 90,
      }],
    })

    const updated = await service.recordSet(state, {
      clientSetKey: 'set-server-0001', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 30, actualReps: 8,
    })

    expect(updated.syncStatus).toBe('OFFLINE_PENDING')
    expect(stored!.setRecords).toHaveLength(1)
    expect(stored!.queue.operations).toHaveLength(1)
    expect(stored!.queue.operations[0]).toMatchObject({
      idempotencyKey: 'set-server-0001',
      type: 'UPSERT_SET',
      payload: { completionStatus: 'COMPLETED', expectedSessionVersion: 1 },
    })

    const synced = await service.flush(updated)
    expect(synced.syncStatus).toBe('SYNCED')
    expect(stored!.queue.operations).toHaveLength(0)
    expect(stored!.lastServerVersion).toBe(2)
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
    const recorded = await service.recordSet(started, {
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
