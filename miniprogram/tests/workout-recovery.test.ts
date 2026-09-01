import { describe, expect, it } from 'vitest'

import { resumeRestTimer, startRestTimer } from '../src/domain/workout/RestTimer'
import { ActiveWorkoutExistsError } from '../src/application/errors'
import {
  beginWorkSets,
  completeGeneralWarmup,
  createWorkoutFlow,
  isWorkoutPrescriptionFinished,
  markWorkoutSyncPending,
  recordWorkoutSet,
  restoreWorkoutFlow,
} from '../src/application/workoutFlow'
import { restoreFlowFromDraft, toWorkoutDraft } from '../src/application/workoutFlowDraftMapper'
import type { WorkoutDraft, WorkoutDraftStore } from '../src/application/ports/WorkoutDraftStore'
import { WorkoutFlowService } from '../src/application/use-cases/WorkoutFlowService'

describe('workout recovery', () => {
  it('preserves server repetition ranges through fresh start, active recovery, and draft persistence', async () => {
    let stored: WorkoutDraft | null = null
    const store: WorkoutDraftStore = {
      loadActive: async () => stored,
      save: async (draft) => { stored = draft },
      clearActive: async () => { stored = null },
    }
    const serverSession = {
      id: 'server-range-session',
      planVersionId: 'server-plan-version',
      version: 1,
      startedAt: '2026-08-21T09:00:00.000Z',
      exercises: [{
        id: 'server-exercise',
        exerciseCode: 'SERVER_ROW',
        exerciseName: '服务端划船',
        prescription: {
          workSets: 3,
          repMin: 8,
          repMax: 12,
          restSeconds: 90,
          weightStatus: 'BODYWEIGHT' as const,
        },
      }],
    }
    const fresh = new WorkoutFlowService(
      store,
      { nowUtc: () => '2026-08-21T09:00:00.000Z' },
      undefined, undefined, undefined,
      { startWorkoutSession: async () => serverSession },
    )

    const started = await fresh.startOrResume({
      clientSessionKey: 'fresh-range-client',
      planId: 'plan-id',
      planVersionNo: 1,
      planDayId: 'day-id',
    })
    expect(started.state.exercises[0]).toMatchObject({ targetRepMin: 8, targetRepMax: 12 })
    expect(started.state.exercises[0]).not.toHaveProperty('targetReps')
    expect((stored!.planSnapshot.exercises as Array<Record<string, unknown>>)[0]).toMatchObject({
      targetRepMin: 8,
      targetRepMax: 12,
    })

    stored = null
    const activeSession = {
      ...serverSession,
      clientSessionKey: 'active-range-client',
      status: 'IN_PROGRESS' as const,
    }
    const recovery = new WorkoutFlowService(
      store,
      { nowUtc: () => '2026-08-21T09:00:00.000Z' },
      undefined, undefined, undefined,
      { startWorkoutSession: async () => { throw new ActiveWorkoutExistsError({ session: activeSession, sets: [] }) } },
    )
    const recovered = await recovery.startOrResume({
      clientSessionKey: 'competing-range-client',
      planId: 'plan-id',
      planVersionNo: 1,
      planDayId: 'day-id',
    })

    expect(recovered.kind).toBe('RESUME_REQUIRED')
    expect(recovered.state.exercises[0]).toMatchObject({ targetRepMin: 8, targetRepMax: 12 })
    expect(recovered.state.exercises[0]).not.toHaveProperty('targetReps')
  })

  it('restores position, completed facts, offline status, and timestamp rest state after process loss', () => {
    let state = createWorkoutFlow({
      clientSessionKey: 'recover-session',
      planVersionId: 'plan-version-9',
      exercises: [{
        snapshotExerciseKey: 'exercise-a', exerciseCode: 'row', name: '划船',
        targetWorkSets: 2, targetRepMin: 8, targetRepMax: 10, restSeconds: 90,
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
    expect(restored.exercises[0]).toMatchObject({ targetRepMin: 8, targetRepMax: 10 })
    expect(restored.exercises[0]).not.toHaveProperty('targetReps')
    expect(restored.syncStatus).toBe('OFFLINE_PENDING')
    expect(restored.warmup.phase).toBe('WORK')
    expect(rest.remainingSeconds).toBe(30)
  })

  it('recomputes the current position from immutable set facts when a persisted cursor is stale', () => {
    let state = createWorkoutFlow({
      clientSessionKey: 'stale-position-session',
      planVersionId: 'plan-version',
      exercises: [{
        snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船',
        targetWorkSets: 2, targetReps: 8, restSeconds: 60,
      }],
    })
    state = beginWorkSets(completeGeneralWarmup(state))
    state = recordWorkoutSet(state, {
      clientSetKey: 'stale-position-session-0-0',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 25,
      actualReps: 8,
    })
    const persisted = {
      ...toWorkoutDraft(state, null, '2026-07-24T09:00:01.000Z'),
      currentExerciseIndex: 0,
      currentSetIndex: 0,
    }

    const restored = restoreFlowFromDraft(persisted)

    expect(restored.currentExerciseIndex).toBe(0)
    expect(restored.currentSetIndex).toBe(1)
  })

  it('restores a timestamp-based general warmup timer after process loss', async () => {
    let stored: WorkoutDraft | null = null
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
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
    expect(ramp.warmup).toMatchObject({ phase: 'WORK', generalTimer: { timerStatus: 'SKIPPED' } })
  })

  it('persists one confirmed formal weight for the rest of the workout', async () => {
    let stored: WorkoutDraft | null = null
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-07-24T09:00:00.000Z' },
    )
    const started = await service.start({
      clientSessionKey: 'weight-session',
      planVersionId: 'plan-version',
      exercises: [{
        snapshotExerciseKey: 'exercise-id',
        exerciseCode: 'ROW',
        name: '划船',
        targetWorkSets: 2,
        targetReps: 8,
        restSeconds: 60,
        weightStatus: 'NEEDS_CALIBRATION',
      }],
    })

    await service.setExerciseWeight(started, 0, 22.5)
    const restored = await service.load()

    expect(restored?.exercises[0].sessionWeightKg).toBe(22.5)
  })

  it('persists the selected Tuesday optional-set branch across page recovery', async () => {
    let stored: WorkoutDraft | null = null
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-08-21T09:41:00.000Z' },
    )
    let state = await service.start({
      clientSessionKey: 'optional-recovery-session',
      planVersionId: 'tuesday-plan',
      startedAtUtc: '2026-08-21T09:00:00.000Z',
      exercises: [
        {
          snapshotExerciseKey: 'machine-row', exerciseCode: 'MACHINE_SEATED_ROW', name: '器械坐姿划船',
          targetWorkSets: 1, targetReps: 12, restSeconds: 90, weightStatus: 'BODYWEIGHT',
          optionalSetRule: {
            conditionCode: 'TUESDAY_UNDER_42_GOOD_STATE',
            exclusiveChoiceGroup: 'TUESDAY_BONUS',
            additionalSets: 1,
          },
        },
        {
          snapshotExerciseKey: 'dumbbell-curl', exerciseCode: 'DUMBBELL_CURL', name: '哑铃弯举',
          targetWorkSets: 1, targetReps: 12, restSeconds: 60, weightStatus: 'BODYWEIGHT',
          optionalSetRule: {
            conditionCode: 'TUESDAY_UNDER_42_GOOD_STATE',
            exclusiveChoiceGroup: 'TUESDAY_BONUS',
            additionalSets: 1,
          },
        },
      ],
    })
    state = await service.completeGeneralWarmup(state)
    state = await service.recordSet(state, {
      clientSetKey: 'row-work', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 0, actualReps: 12,
    })
    state = await service.recordSet(state, {
      clientSetKey: 'curl-work', exerciseIndex: 1, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 0, actualReps: 12,
    })
    await service.chooseOptionalSet(state, 'TUESDAY_BONUS', 0)

    const restored = await service.load()
    expect(restored).toMatchObject({
      startedAtUtc: '2026-08-21T09:00:00.000Z',
      currentExerciseIndex: 0,
      currentSetIndex: 1,
      optionalSetChoices: { TUESDAY_BONUS: 0 },
    })
  })

  it('restores a safety-stopped optional workout without reopening the bonus choice', async () => {
    let stored: WorkoutDraft | null = null
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-08-21T09:41:00.000Z' },
    )
    let state = await service.start({
      clientSessionKey: 'optional-safety-recovery',
      planVersionId: 'tuesday-plan',
      startedAtUtc: '2026-08-21T09:00:00.000Z',
      exercises: [{
        snapshotExerciseKey: 'machine-row', exerciseCode: 'MACHINE_SEATED_ROW', name: '器械坐姿划船',
        targetWorkSets: 1, targetRepMin: 10, targetRepMax: 12, restSeconds: 90,
        weightStatus: 'BODYWEIGHT',
        optionalSetRule: {
          conditionCode: 'TUESDAY_UNDER_42_GOOD_STATE',
          exclusiveChoiceGroup: 'TUESDAY_BONUS',
          additionalSets: 1,
        },
      }],
    })
    state = await service.completeGeneralWarmup(state)
    await service.recordSet(state, {
      clientSetKey: 'unsafe-work', exerciseIndex: 0, setType: 'WORK', status: 'FAILED',
      actualWeightKg: 0, actualReps: 0, safetyFlag: 'PAIN',
    })

    const restored = await service.load()
    expect(restored?.safetyNotice).toMatch(/立即停止训练/)
    expect(restored?.exercises[0]).toMatchObject({ targetRepMin: 10, targetRepMax: 12 })
    await expect(service.chooseOptionalSet(restored!, 'TUESDAY_BONUS', 0))
      .rejects.toThrow(/not available/i)
    expect((stored!.planSnapshot.optionalSetChoices as Record<string, number | null>)).toEqual({})
  })

  it('rejects malformed persisted state instead of fabricating completed work', () => {
    expect(() => restoreWorkoutFlow({ clientSessionKey: 'broken' })).toThrow(/workout state/i)

    const malformedSets = JSON.parse(JSON.stringify(createWorkoutFlow({
      clientSessionKey: 'broken-sets',
      planVersionId: 'plan-version',
      exercises: [{
        snapshotExerciseKey: 'exercise-id',
        exerciseCode: 'ROW',
        name: '划船',
        targetWorkSets: 2,
        targetReps: 8,
        restSeconds: 60,
      }],
    })))
    malformedSets.exercises[0].sets = null

    expect(() => restoreWorkoutFlow(malformedSets)).toThrow('workout state is invalid')
  })

  it('restores legacy ramp drafts without inferring client-side warmup weights', () => {
    const legacy = JSON.parse(JSON.stringify(createWorkoutFlow({
      clientSessionKey: 'legacy-ramp-session',
      planVersionId: 'legacy-plan',
      exercises: [{
        snapshotExerciseKey: 'exercise-id',
        exerciseCode: 'ROW',
        name: '划船',
        targetWorkSets: 2,
        targetReps: 8,
        restSeconds: 60,
        weightStatus: 'KNOWN',
        targetWeightKg: 20,
      }],
    })))
    legacy.warmup.phase = 'RAMP'
    legacy.warmup.rampExerciseIndex = 0
    legacy.warmup.maximumRampSets = 3
    delete legacy.warmup.prescriptionVersion
    delete legacy.warmup.ruleVersion
    delete legacy.warmup.rampStatus
    delete legacy.warmup.rampSets
    delete legacy.warmup.calibrationMessage
    delete legacy.exercises[0].sessionWeightKg

    const restored = restoreWorkoutFlow(legacy)

    expect(restored.warmup).toMatchObject({
      phase: 'WORK', prescriptionVersion: 'legacy-client-v1',
      rampExerciseIndex: null, rampStatus: 'NOT_REQUIRED', maximumRampSets: 0,
    })
    expect(restored.exercises[0].sessionWeightKg).toBe(20)
  })

  it('atomically keeps a completed set and its pending server operation in the active draft', async () => {
    let stored: WorkoutDraft | null = null
    const store: WorkoutDraftStore = {
      loadActive: async () => stored,
      save: async (draft) => { stored = draft },
      clearActive: async () => { stored = null },
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

  it('retains a rejected operation until a later retry is accepted by the server', async () => {
    let stored: WorkoutDraft | null = null
    let attempts = 0
    const replayedSafetyFlags: unknown[] = []
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-07-24T09:00:00.000Z' },
      {
        syncWorkoutOperations: async (operations) => {
          attempts += 1
          replayedSafetyFlags.push(...operations.map((operation) => operation.payload.safetyFlag))
          return operations.map((operation) => ({
            clientOperationSeq: operation.clientOperationSeq,
            status: attempts === 1 ? 'REJECTED' as const : 'APPLIED' as const,
          }))
        },
      },
    )
    let state = await service.start({
      clientSessionKey: 'rejected-sync-session',
      planVersionId: 'plan-version',
      serverSessionId: 'session-id',
      serverVersion: 1,
      exercises: [{
        snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船',
        targetWorkSets: 2, targetReps: 8, restSeconds: 60,
      }],
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'rejected-set-1', exerciseIndex: 0, setType: 'WORK', status: 'FAILED',
      actualWeightKg: 25, actualReps: 0, safetyFlag: 'DIZZINESS',
    })

    const rejected = await service.flush(state)

    expect(rejected.syncStatus).toBe('SYNC_REJECTED')
    expect(stored!.queue.operations).toHaveLength(1)
    expect(stored!.queue.operations[0].status).toBe('REJECTED')
    expect(stored!.setRecords[0]).toMatchObject({ safetyFlag: 'DIZZINESS' })
    expect(stored!.queue.operations[0].payload).toMatchObject({ safetyFlag: 'DIZZINESS' })

    const retrying = await service.retryRejectedOperations(rejected)
    expect(stored!.queue.operations[0]).toMatchObject({ status: 'PENDING', clientOperationSeq: 2 })
    const recovered = await service.flush(retrying)

    expect(recovered.syncStatus).toBe('SYNCED')
    expect(replayedSafetyFlags).toEqual(['DIZZINESS', 'DIZZINESS'])
    expect(stored!.queue.operations).toHaveLength(0)
    expect(stored!.lastServerVersion).toBe(2)
  })

  it('retries pending operations when the workout returns to the foreground', async () => {
    let stored: WorkoutDraft | null = null
    const synchronizedSequences: number[][] = []
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-07-24T09:00:30.000Z' },
      {
        syncWorkoutOperations: async (operations) => {
          synchronizedSequences.push(operations.map((operation) => operation.clientOperationSeq))
          return operations.map((operation) => ({ clientOperationSeq: operation.clientOperationSeq, status: 'APPLIED' as const }))
        },
      },
    )
    let state = await service.start({
      clientSessionKey: 'foreground-sync-session', planVersionId: 'plan-version',
      serverSessionId: 'session-id', serverVersion: 4,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }],
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'foreground-set-1', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 25, actualReps: 8,
    })

    const recovered = await service.resume(state)

    expect(synchronizedSequences).toEqual([[1]])
    expect(recovered.state.syncStatus).toBe('SYNCED')
    expect(recovered.syncFailed).toBe(false)
    expect(stored!.queue.operations).toHaveLength(0)
    expect(stored!.lastServerVersion).toBe(5)
  })

  it('restores an offline-pending draft without waiting for or starting remote synchronization', async () => {
    let stored: WorkoutDraft | null = null
    let synchronizationAttempts = 0
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-07-24T09:00:30.000Z' },
      {
        syncWorkoutOperations: async () => {
          synchronizationAttempts += 1
          throw new Error('resumeLocal must not synchronize')
        },
      },
    )
    let state = await service.start({
      clientSessionKey: 'local-first-resume-session', planVersionId: 'plan-version',
      serverSessionId: 'session-id', serverVersion: 2,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }],
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'local-first-set-1', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 25, actualReps: 8,
    })

    const recovered = await service.resumeLocal(state)

    expect(synchronizationAttempts).toBe(0)
    expect(recovered.state.syncStatus).toBe('OFFLINE_PENDING')
    expect(stored!.queue.operations).toHaveLength(1)
  })

  it('keeps local interaction and explicit abandonment responsive while background synchronization is slow', async () => {
    let stored: WorkoutDraft | null = null
    let releaseSynchronization!: () => void
    let reportSynchronizationStarted!: () => void
    const synchronizationGate = new Promise<void>((resolve) => { releaseSynchronization = resolve })
    const synchronizationStarted = new Promise<void>((resolve) => { reportSynchronizationStarted = resolve })
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-07-24T09:00:30.000Z' },
      {
        syncWorkoutOperations: async (operations) => {
          reportSynchronizationStarted()
          await synchronizationGate
          return operations.map((operation) => ({
            clientOperationSeq: operation.clientOperationSeq,
            status: 'APPLIED' as const,
          }))
        },
      },
      {
        completeWorkout: async (sessionId) => ({
          session: { id: sessionId, status: 'ABORTED', version: 4 },
          completedWorkSets: 1,
          complete: false,
          automaticProgressionEligible: false,
        }),
      },
    )
    let state = await service.start({
      clientSessionKey: 'non-blocking-background-sync', planVersionId: 'plan-version',
      serverSessionId: 'session-id', serverVersion: 2,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }],
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'non-blocking-set-1', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 25, actualReps: 8,
    })

    const synchronization = service.flush(state)
    await synchronizationStarted
    const adjustment = service.adjustRest(state, 15)
    const adjustmentOutcome = await Promise.race([
      adjustment.then(() => 'LOCAL' as const),
      new Promise<'BLOCKED'>((resolve) => setTimeout(() => resolve('BLOCKED'), 25)),
    ])
    const abandonment = service.abandonActive(state)
    const abandonmentOutcome = await Promise.race([
      abandonment.then(() => 'LOCAL' as const),
      new Promise<'BLOCKED'>((resolve) => setTimeout(() => resolve('BLOCKED'), 25)),
    ])

    releaseSynchronization()
    await Promise.all([synchronization, adjustment, abandonment])
    expect(adjustmentOutcome).toBe('LOCAL')
    expect(abandonmentOutcome).toBe('LOCAL')
    expect(stored).toBeNull()
  })

  it('replaces an active workout with one authoritative start command and no legacy completion request', async () => {
    let stored: WorkoutDraft | null = null
    const requests: unknown[] = []
    let legacyCompletions = 0
    const service = new WorkoutFlowService(
      {
        loadActive: async () => stored,
        save: async (draft) => { stored = draft },
        replaceActive: async (draftId, replacement) => {
          if (stored?.draftId !== draftId) throw new Error('stale draft')
          stored = replacement
        },
        clearActive: async (draftId) => {
          if (stored?.draftId === draftId) stored = null
        },
      },
      { nowUtc: () => '2026-08-29T08:00:00.000Z' },
      undefined,
      {
        completeWorkout: async () => {
          legacyCompletions += 1
          throw new Error('legacy completion must not run')
        },
      },
      undefined,
      {
        startWorkoutSession: async (request) => {
          requests.push(request)
          return {
            id: 'replacement-server-session',
            planVersionId: 'replacement-plan-version',
            version: 1,
            startedAt: '2026-08-29T08:00:01.000Z',
            exercises: [{
              id: 'replacement-exercise',
              exerciseCode: 'ROW',
              exerciseName: '划船',
              prescription: {
                workSets: 2,
                repMin: 8,
                repMax: 10,
                restSeconds: 60,
                weightStatus: 'NEEDS_CALIBRATION' as const,
              },
            }],
          }
        },
      },
    )
    const active = await service.start({
      clientSessionKey: 'active-local-session',
      planVersionId: 'active-plan-version',
      serverSessionId: 'active-server-session',
      serverVersion: 4,
      exercises: [{
        snapshotExerciseKey: 'active-exercise', exerciseCode: 'SQUAT', name: '深蹲',
        targetWorkSets: 2, targetReps: 8, restSeconds: 60,
      }],
    })

    const result = await service.replaceActiveAndStart(active, {
      clientSessionKey: 'replacement-local-session',
      planId: 'plan-id',
      planVersionNo: 2,
      planDayId: 'DAY_B',
    })

    expect(result).toMatchObject({
      kind: 'STARTED',
      state: { clientSessionKey: 'replacement-local-session' },
    })
    expect(requests).toEqual([{
      clientSessionKey: 'replacement-local-session',
      planId: 'plan-id',
      planVersionNo: 2,
      planDayId: 'DAY_B',
      recoveryConfirmationToken: undefined,
      activeWorkoutReplacement: {
        sessionId: 'active-server-session',
        expectedVersion: 4,
      },
    }])
    expect(legacyCompletions).toBe(0)
    expect(stored).toMatchObject({
      clientSessionKey: 'replacement-local-session',
      sessionId: 'replacement-server-session',
      lastServerVersion: 1,
    })
  })

  it('preserves the active local draft when the atomic replacement request fails', async () => {
    let stored: WorkoutDraft | null = null
    const service = new WorkoutFlowService(
      {
        loadActive: async () => stored,
        save: async (draft) => { stored = draft },
        replaceActive: async (draftId, replacement) => {
          if (stored?.draftId !== draftId) throw new Error('stale draft')
          stored = replacement
        },
        clearActive: async () => { stored = null },
      },
      { nowUtc: () => '2026-08-29T08:00:00.000Z' },
      undefined,
      undefined,
      undefined,
      { startWorkoutSession: async () => { throw new Error('offline') } },
    )
    const active = await service.start({
      clientSessionKey: 'preserved-active-session',
      planVersionId: 'active-plan-version',
      serverSessionId: 'active-server-session',
      serverVersion: 4,
      exercises: [{
        snapshotExerciseKey: 'active-exercise', exerciseCode: 'SQUAT', name: '深蹲',
        targetWorkSets: 2, targetReps: 8, restSeconds: 60,
      }],
    })

    await expect(service.replaceActiveAndStart(active, {
      clientSessionKey: 'failed-replacement-session',
      planId: 'plan-id',
      planVersionNo: 2,
      planDayId: 'DAY_B',
    })).rejects.toThrow('offline')

    expect(stored).toMatchObject({
      clientSessionKey: 'preserved-active-session',
      sessionId: 'active-server-session',
      lastServerVersion: 4,
    })
  })

  it('does not let a stale synchronization result overwrite newer workout facts', async () => {
    let stored: WorkoutDraft | null = null
    let releaseSynchronization!: () => void
    let reportSynchronizationStarted!: () => void
    const synchronizationGate = new Promise<void>((resolve) => { releaseSynchronization = resolve })
    const synchronizationStarted = new Promise<void>((resolve) => { reportSynchronizationStarted = resolve })
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-07-24T09:00:30.000Z' },
      {
        syncWorkoutOperations: async (operations) => {
          reportSynchronizationStarted()
          await synchronizationGate
          return operations.map((operation) => ({
            clientOperationSeq: operation.clientOperationSeq,
            status: 'APPLIED' as const,
          }))
        },
      },
    )
    let state = await service.start({
      clientSessionKey: 'stale-flush-session',
      planVersionId: 'plan-version',
      serverSessionId: 'session-id',
      serverVersion: 0,
      exercises: [{
        snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船',
        targetWorkSets: 3, targetReps: 8, restSeconds: 60,
      }],
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    const afterFirstSet = await service.recordSet(state, {
      clientSetKey: 'stale-flush-set-1', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 25, actualReps: 8,
    })
    const staleFlush = service.flush(afterFirstSet)
    await synchronizationStarted
    const secondRecord = service.recordSet(afterFirstSet, {
      clientSetKey: 'stale-flush-set-2', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 25, actualReps: 8,
    })

    releaseSynchronization()
    await Promise.all([staleFlush, secondRecord])
    const restored = await service.load()

    expect(restored?.exercises[0].sets).toHaveLength(2)
    expect(restored?.currentSetIndex).toBe(2)
    expect((stored as WorkoutDraft | null)?.queue.operations).toHaveLength(1)
  })

  it('serializes background synchronization flights without sending the same pending operation twice', async () => {
    let stored: WorkoutDraft | null = null
    let releaseSynchronization!: () => void
    let reportSynchronizationStarted!: () => void
    let synchronizationAttempts = 0
    const synchronizationGate = new Promise<void>((resolve) => { releaseSynchronization = resolve })
    const synchronizationStarted = new Promise<void>((resolve) => { reportSynchronizationStarted = resolve })
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-07-24T09:00:30.000Z' },
      {
        syncWorkoutOperations: async (operations) => {
          synchronizationAttempts += 1
          reportSynchronizationStarted()
          await synchronizationGate
          return operations.map((operation) => ({
            clientOperationSeq: operation.clientOperationSeq,
            status: 'APPLIED' as const,
          }))
        },
      },
    )
    let state = await service.start({
      clientSessionKey: 'serialized-sync-flight', planVersionId: 'plan-version',
      serverSessionId: 'session-id', serverVersion: 2,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }],
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'serialized-sync-set-1', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 25, actualReps: 8,
    })

    const first = service.flush(state)
    await synchronizationStarted
    const second = service.flush(state)
    expect(synchronizationAttempts).toBe(1)

    releaseSynchronization()
    await Promise.all([first, second])
    expect(synchronizationAttempts).toBe(1)
    expect(stored!.queue.operations).toHaveLength(0)
  })

  it('keeps the restored draft usable when foreground synchronization is offline', async () => {
    let stored: WorkoutDraft | null = null
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-07-24T09:00:30.000Z' },
      { syncWorkoutOperations: async () => { throw new Error('offline') } },
    )
    let state = await service.start({
      clientSessionKey: 'offline-resume-session', planVersionId: 'plan-version',
      serverSessionId: 'session-id', serverVersion: 2,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }],
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'offline-set-1', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 25, actualReps: 8,
    })

    const recovered = await service.resume(state)

    expect(recovered.state.syncStatus).toBe('OFFLINE_PENDING')
    expect(recovered.syncFailed).toBe(true)
    expect(stored!.queue.operations).toHaveLength(1)
  })

  it('persists a naturally expired rest so the screen advances without an extra tap', async () => {
    let stored: WorkoutDraft | null = null
    let nowUtc = '2026-07-24T09:00:00.000Z'
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => nowUtc },
    )
    let state = await service.start({
      clientSessionKey: 'rest-expiry-session',
      planVersionId: 'plan-version',
      exercises: [{
        snapshotExerciseKey: 'exercise-id',
        exerciseCode: 'ROW',
        name: '划船',
        targetWorkSets: 2,
        targetReps: 8,
        restSeconds: 60,
      }],
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'rest-expiry-set-1',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 25,
      actualReps: 8,
    })
    nowUtc = '2026-07-24T09:01:01.000Z'

    const advanced = await service.finishRest(state)

    expect(advanced.restTimer?.timerStatus).toBe('FINISHED')
    expect((stored as WorkoutDraft | null)?.restTimer).toMatchObject({ timerStatus: 'FINISHED' })
  })

  it('queues successive ramp warmup sets with their own order and no work-set rest timer', async () => {
    let stored: WorkoutDraft | null = null
    const store: WorkoutDraftStore = { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } }
    const service = new WorkoutFlowService(store, { nowUtc: () => '2026-07-24T09:00:00.000Z' })
    let state = await service.start({
      clientSessionKey: 'ramp-server-session', planVersionId: 'plan-version', serverSessionId: 'session-id', serverVersion: 0,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'SQUAT', name: '深蹲', targetWorkSets: 3, targetReps: 8, restSeconds: 90 }],
      warmupPrescription: {
        schemaVersion: 'workout-warmup-prescription-v1', ruleVersion: '1.3.0',
        generalWarmup: { occurrences: 1, durationSeconds: 180 },
        rampWarmup: {
          exerciseId: 'exercise-id', exerciseOrder: 1, status: 'READY',
          sets: [{ weightKg: 10, reps: 10 }, { weightKg: 15, reps: 8 }],
        },
        countsTowardTrainingVolume: false, countsTowardProgression: false,
      },
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

  it('does not start a rest timer after the final prescribed work set', async () => {
    let stored: WorkoutDraft | null = null
    const service = new WorkoutFlowService(
      { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } },
      { nowUtc: () => '2026-07-24T09:00:00.000Z' },
    )
    let state = await service.start({
      clientSessionKey: 'final-set-session',
      planVersionId: 'plan-version',
      exercises: [{
        snapshotExerciseKey: 'exercise-id',
        exerciseCode: 'ROW',
        name: '划船',
        targetWorkSets: 1,
        targetReps: 8,
        restSeconds: 60,
      }],
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'final-set',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 25,
      actualReps: 8,
    })

    expect(isWorkoutPrescriptionFinished(state)).toBe(true)
    expect(state.restTimer).toBeNull()
  })

  it('synchronizes pending facts before idempotent early completion', async () => {
    let stored: WorkoutDraft | null = null
    let archived: WorkoutDraft | null = null
    const store: WorkoutDraftStore = {
      loadActive: async () => stored,
      save: async (draft) => { stored = draft },
      clearActive: async (draftId) => {
        if (stored?.draftId === draftId) { archived = stored; stored = null }
      },
    }
    const completionRequests: unknown[] = []
    const service = new WorkoutFlowService(
      store,
      { nowUtc: () => '2026-07-24T09:00:00.000Z' },
      { syncWorkoutOperations: async (operations) => operations.map((operation) => ({ clientOperationSeq: operation.clientOperationSeq, status: 'APPLIED' as const })) },
      { completeWorkout: async (sessionId, request, key) => {
        completionRequests.push({ sessionId, request, key })
        return { session: { id: sessionId, status: 'ABORTED', version: 4 }, completedWorkSets: 1, complete: false, automaticProgressionEligible: false }
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
    expect(stored).toBeNull()
    expect(archived!.lastServerVersion).toBe(2)
    expect(archived!.queue.operations).toHaveLength(0)
  })

  it('keeps the active draft when server completion fails', async () => {
    let stored: WorkoutDraft | null = null
    const store: WorkoutDraftStore = {
      loadActive: async () => stored,
      save: async (draft) => { stored = draft },
      clearActive: async () => { stored = null },
    }
    const service = new WorkoutFlowService(
      store,
      { nowUtc: () => '2026-07-24T09:00:00.000Z' },
      undefined,
      { completeWorkout: async () => { throw new Error('offline') } },
    )
    const state = await service.start({
      clientSessionKey: 'failed-completion', planVersionId: 'plan-version', serverSessionId: 'session-id', serverVersion: 1,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }],
    })

    await expect(service.complete(state, 'EARLY_END')).rejects.toThrow('offline')

    expect(stored).toMatchObject({ clientSessionKey: 'failed-completion', sessionId: 'session-id' })
  })

  it('persists a server-approved replacement only in the current workout draft', async () => {
    let stored: WorkoutDraft | null = null
    const store: WorkoutDraftStore = { loadActive: async () => stored, save: async (draft) => { stored = draft }, clearActive: async () => { stored = null } }
    const service = new WorkoutFlowService(
      store, { nowUtc: () => '2026-07-24T09:00:00.000Z' }, undefined, undefined,
      {
        listExerciseReplacements: async () => [{ id: 'replacement-id', code: 'SAFE_ROW', name: '安全划船', movementPattern: 'HORIZONTAL_PULL', difficulty: 'BEGINNER', equipment: ['CABLE'], primaryMuscles: ['BACK'] }],
        replaceWorkoutExercise: async () => ({ version: 2, exercises: [{ id: 'exercise-id', exerciseCode: 'SAFE_ROW', exerciseName: '安全划船', prescription: { workSets: 2, repMin: 8, repMax: 10, restSeconds: 60, weightStatus: 'NEEDS_CALIBRATION', unit: 'KG' } }] }),
      },
    )
    const state = await service.start({ clientSessionKey: 'replace-session', planVersionId: 'plan-version', serverSessionId: 'session-id', serverVersion: 1,
      exercises: [{ snapshotExerciseKey: 'exercise-id', exerciseCode: 'ROW', name: '划船', targetWorkSets: 2, targetReps: 8, restSeconds: 60 }] })
    const candidate = (await service.replacementCandidates(state))[0]

    const updated = await service.replaceCurrentExercise(state, candidate)

    expect(updated.exercises[0]).toMatchObject({
      exerciseCode: 'SAFE_ROW', replacedExerciseCode: 'ROW',
      targetRepMin: 8, targetRepMax: 10, weightStatus: 'NEEDS_CALIBRATION',
    })
    expect(updated.exercises[0]).not.toHaveProperty('targetReps')
    expect(stored!.lastServerVersion).toBe(2)
    expect(stored!.planSnapshot).toMatchObject({ planVersionId: 'plan-version' })
  })
})
