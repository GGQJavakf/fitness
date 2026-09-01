import { describe, expect, it, vi } from 'vitest'

import type { WorkoutDraft, WorkoutDraftStore } from '../src/application/ports/WorkoutDraftStore'
import { WorkoutFlowService } from '../src/application/use-cases/WorkoutFlowService'
import { createWorkoutFlow, type WorkoutFlowState } from '../src/application/workoutFlow'
import { createStartupUseCases } from '../src/application/onboarding'
import { ActiveWorkoutExistsError } from '../src/application/errors'

describe('R4 workout lifecycle reliability', () => {
  it('discards only the explicitly identified orphaned local workout', async () => {
    const store = memoryStore()
    const service = new WorkoutFlowService(store, fixedClock())
    const state = await service.start(localStartInput('orphaned-session'))

    await expect(service.discardOrphanedLocalWorkout({
      ...state,
      clientSessionKey: 'different-session',
    })).rejects.toThrow('active workout changed')
    expect(await store.loadActive()).not.toBeNull()

    await service.discardOrphanedLocalWorkout(state)
    expect(await store.loadActive()).toBeNull()
  })

  it('does not create a second server session while an unfinished draft exists', async () => {
    const store = memoryStore()
    const starter = {
      startWorkoutSession: vi.fn().mockResolvedValue(serverSession('new-server-session')),
    }
    const service = new WorkoutFlowService(
      store,
      fixedClock(),
      undefined,
      undefined,
      undefined,
      starter,
    )
    await service.start(localStartInput('existing-session'))

    const pending = await service.startOrResume(startRequest('new-session'))

    expect(pending.kind).toBe('RESUME_REQUIRED')
    expect(starter.startWorkoutSession).not.toHaveBeenCalled()
    if (pending.kind !== 'RESUME_REQUIRED') throw new Error('expected active draft decision')
    expect(pending.state.clientSessionKey).toBe('existing-session')

    const resumed = await service.startOrResume({ ...startRequest('new-session'), activeDraftDecision: 'RESUME' })
    expect(resumed.kind).toBe('RESUMED')
    expect(starter.startWorkoutSession).not.toHaveBeenCalled()
  })

  it('re-enters the same durable start intent without asking the user to recover it again', async () => {
    const store = memoryStore()
    const starter = {
      startWorkoutSession: vi.fn().mockResolvedValue(serverSession('unused-server-session')),
    }
    const service = new WorkoutFlowService(
      store,
      fixedClock(),
      undefined,
      undefined,
      undefined,
      starter,
    )
    await service.start(localStartInput('same-session'))

    const resumed = await service.startOrResume(startRequest('same-session'))

    expect(resumed.kind).toBe('RESUMED')
    expect(starter.startWorkoutSession).not.toHaveBeenCalled()
  })

  it('creates the server session only after startOrResume observes no active draft', async () => {
    const store = memoryStore()
    const starter = {
      startWorkoutSession: vi.fn().mockResolvedValue(serverSession('server-session-id')),
    }
    const service = new WorkoutFlowService(
      store,
      fixedClock(),
      undefined,
      undefined,
      undefined,
      starter,
    )

    const started = await service.startOrResume(startRequest('fresh-session'))

    expect(started.kind).toBe('STARTED')
    expect(starter.startWorkoutSession).toHaveBeenCalledOnce()
    expect((await store.loadActive())?.sessionId).toBe('server-session-id')
    if (started.kind !== 'STARTED') throw new Error('expected a started workout')
    expect(started.state.warmup).toMatchObject({
      prescriptionVersion: 'workout-warmup-prescription-v1',
      ruleVersion: '1.3.0',
      rampExerciseIndex: 0,
      rampSets: [{ weightKg: 10, reps: 10 }, { weightKg: 17.5, reps: 6 }],
    })
  })

  it('rebuilds a synced local draft when the server reports an active workout from another client key', async () => {
    const store = memoryStore()
    const session = serverSession('existing-server-session')
    const starter = {
      startWorkoutSession: vi.fn().mockRejectedValue(new ActiveWorkoutExistsError({
        session: { ...session, clientSessionKey: 'existing-server-client-key', version: 2 },
        sets: [{
          setId: 'set-id-1', sessionExerciseId: 'exercise-id', clientSetKey: 'server-set-key-0001',
          clientOperationSeq: 1, setType: 'WORK' as const, setOrder: 1,
          target: { weight: { value: 25, unit: 'KG' as const }, reps: 10 },
          actual: { weight: { value: 25, unit: 'KG' as const }, reps: 8 },
          remainingReps: 2, completionStatus: 'COMPLETED' as const,
          completedAt: '2026-08-11T08:10:00Z', serverRevision: 0,
          sessionVersion: 2, syncStatus: 'APPLIED' as const,
        }],
      })),
    }
    const service = new WorkoutFlowService(store, fixedClock(), undefined, undefined, undefined, starter)

    const result = await service.startOrResume(startRequest('new-local-client-key'))

    expect(result.kind).toBe('RESUME_REQUIRED')
    expect(result.state.clientSessionKey).toBe('existing-server-client-key')
    expect(result.state.exercises[0].sets).toEqual([
      expect.objectContaining({
        clientSetKey: 'server-set-key-0001', actualWeightKg: 25, actualReps: 8, rir: '2',
      }),
    ])
    expect(result.state.syncStatus).toBe('SYNCED')
    expect(await store.loadActive()).toMatchObject({
      clientSessionKey: 'existing-server-client-key',
      sessionId: 'existing-server-session',
      lastServerVersion: 2,
      queue: { operations: [] },
    })
  })

  it('activates a response-loss CREATED session before rebuilding the local draft', async () => {
    const store = memoryStore()
    const session = serverSession('created-server-session')
    const active = {
      session: {
        ...session,
        clientSessionKey: 'created-server-client-key',
        status: 'CREATED' as const,
        version: 0,
      },
      sets: [],
    }
    const activateWorkoutSession = vi.fn().mockResolvedValue({
      ...active.session,
      status: 'IN_PROGRESS' as const,
      version: 1,
    })
    const starter = {
      startWorkoutSession: vi.fn().mockRejectedValue(new ActiveWorkoutExistsError(active)),
      activateWorkoutSession,
    }
    const service = new WorkoutFlowService(store, fixedClock(), undefined, undefined, undefined, starter)

    const result = await service.startOrResume(startRequest('new-local-client-key'))

    expect(result.kind).toBe('RESUME_REQUIRED')
    expect(activateWorkoutSession).toHaveBeenCalledWith('created-server-session', 0)
    expect(await store.loadActive()).toMatchObject({
      clientSessionKey: 'created-server-client-key',
      sessionId: 'created-server-session',
      lastServerVersion: 1,
    })
  })

  it('reactivates a PAUSED server session before rebuilding the local draft', async () => {
    const store = memoryStore()
    const session = serverSession('paused-server-session')
    const active = {
      session: {
        ...session,
        clientSessionKey: 'paused-server-client-key',
        status: 'PAUSED' as const,
        version: 3,
      },
      sets: [],
    }
    const activateWorkoutSession = vi.fn().mockResolvedValue({
      ...active.session,
      status: 'IN_PROGRESS' as const,
      version: 4,
    })
    const starter = {
      startWorkoutSession: vi.fn().mockRejectedValue(new ActiveWorkoutExistsError(active)),
      activateWorkoutSession,
    }
    const service = new WorkoutFlowService(store, fixedClock(), undefined, undefined, undefined, starter)

    const result = await service.startOrResume(startRequest('new-local-client-key'))

    expect(result.kind).toBe('RESUME_REQUIRED')
    expect(activateWorkoutSession).toHaveBeenCalledWith('paused-server-session', 3)
    expect(await store.loadActive()).toMatchObject({
      sessionId: 'paused-server-session',
      lastServerVersion: 4,
    })
  })

  it('keeps the general warmup when an active session has no recorded set facts', async () => {
    const store = memoryStore()
    const session = serverSession('legacy-server-session')
    const active = {
      session: {
        ...session,
        clientSessionKey: 'legacy-server-client-key',
        warmupPrescription: undefined,
      },
      sets: [],
    }
    const starter = {
      startWorkoutSession: vi.fn().mockRejectedValue(new ActiveWorkoutExistsError(active)),
    }
    const service = new WorkoutFlowService(store, fixedClock(), undefined, undefined, undefined, starter)

    const result = await service.startOrResume(startRequest('new-local-client-key'))

    expect(result.kind).toBe('RESUME_REQUIRED')
    expect(result.state.warmup).toMatchObject({
      phase: 'GENERAL',
      prescriptionVersion: 'legacy-client-v1',
      generalTimer: {
        timerStatus: 'RUNNING',
        configuredDurationSeconds: 180,
      },
    })
  })

  it('serializes resume and recordSet so neither can overwrite the other and revisions increase', async () => {
    const store = memoryStore()
    const service = new WorkoutFlowService(store, fixedClock())
    let state = await service.start(localStartInput('serialized-session'))
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    const revisionBefore = (await store.loadActive())!.revision

    await Promise.all([
      service.resume(state),
      service.recordSet(state, {
        clientSetKey: 'serialized-set-0001',
        exerciseIndex: 0,
        setType: 'WORK',
        status: 'COMPLETED',
        actualWeightKg: 25,
        actualReps: 8,
      }),
    ])

    const latest = await service.load()
    expect(latest?.exercises[0].sets).toEqual([
      expect.objectContaining({ clientSetKey: 'serialized-set-0001', actualReps: 8 }),
    ])
    expect((await store.loadActive())!.revision).toBeGreaterThan(revisionBefore)
  })

  it.each([
    ['REJECTED', { status: 'REJECTED' as const, reasonCode: 'VALIDATION_FAILED' }],
    ['CONFLICT', { status: 'CONFLICT' as const, conflictId: 'conflict-id-0001', reasonCode: 'IDEMPOTENCY_KEY_REUSED' }],
  ])('converges a %s operation through explicit abandon and permits completion', async (_label, syncResult) => {
    const store = memoryStore()
    const completeWorkout = vi.fn().mockResolvedValue({
      session: { id: 'server-session-id', status: 'ABORTED', version: 3 },
      completedWorkSets: 0,
      complete: false,
      automaticProgressionEligible: false,
    })
    const service = new WorkoutFlowService(
      store,
      fixedClock(),
      {
        syncWorkoutOperations: async (operations) => operations.map((operation) => ({
          clientOperationSeq: operation.clientOperationSeq,
          ...syncResult,
        })),
      },
      { completeWorkout },
    )
    let state = await service.start({
      ...localStartInput('blocked-session'),
      serverSessionId: 'server-session-id',
      serverVersion: 1,
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'blocked-set-0001',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 25,
      actualReps: 8,
    })

    const blocked = await service.flush(state)
    expect((await store.loadActive())!.queue.operations[0]).toMatchObject({
      status: syncResult.status,
      reasonCode: syncResult.reasonCode,
    })

    const converged = await service.abandonBlockedOperations(blocked)
    expect((await store.loadActive())!.queue.operations).toEqual([])
    await expect(service.complete(converged, 'EARLY_END')).resolves.toMatchObject({ complete: false })
    expect(completeWorkout).toHaveBeenCalledOnce()
  })

  it('converges a server-selected conflict to the authoritative set and version before clearing the queue', async () => {
    const store = memoryStore()
    const service = new WorkoutFlowService(
      store,
      fixedClock(),
      {
        syncWorkoutOperations: async (operations) => operations.map((operation) => ({
          clientOperationSeq: operation.clientOperationSeq,
          status: 'CONFLICT' as const,
          conflictId: 'conflict-authority-0001',
          reasonCode: 'IDEMPOTENCY_KEY_REUSED',
        })),
      },
    )
    let state = await service.start({
      ...localStartInput('authority-session'),
      serverSessionId: 'server-session-id',
      serverVersion: 1,
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'authority-set-0001',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 25,
      actualReps: 8,
      rir: '2',
    })
    const conflicted = await service.flush(state)

    const converged = await service.convergeConflict({
      conflictId: 'conflict-authority-0001',
      clientOperationSeq: 1,
      clientKey: 'authority-set-0001',
      resolution: 'KEEP_SERVER',
      outcome: 'ABANDONED',
      authoritativeSessionVersion: 2,
      authoritativePayload: {
        kind: 'WORKOUT_SET',
        sessionId: 'server-session-id',
        sessionExerciseId: 'exercise-id',
        clientSetKey: 'authority-set-0001',
        setType: 'WORK',
        actual: { actualWeightKg: 30, actualReps: 9, unit: 'KG' },
        remainingReps: 1,
        completionStatus: 'COMPLETED',
        safetyFlag: null,
        authoritativeSessionVersion: 2,
      },
    })

    expect(converged?.exercises[0].sets).toEqual([
      expect.objectContaining({
        clientSetKey: 'authority-set-0001', actualWeightKg: 30, actualReps: 9, rir: '1',
      }),
    ])
    expect(converged?.syncStatus).toBe('SYNCED')
    expect((await store.loadActive())?.queue.operations).toEqual([])
    expect((await store.loadActive())?.lastServerVersion).toBe(2)
    expect(conflicted.syncStatus).toBe('CONFLICT')
  })

  it('persists and restores a conflict decision before the server call can complete', async () => {
    const store = memoryStore()
    const service = new WorkoutFlowService(
      store,
      fixedClock(),
      {
        syncWorkoutOperations: async (operations) => operations.map((operation) => ({
          clientOperationSeq: operation.clientOperationSeq,
          status: 'CONFLICT' as const,
          conflictId: 'conflict-replay-0001',
        })),
      },
    )
    let state = await service.start({
      ...localStartInput('decision-replay-session'),
      serverSessionId: 'server-session-id',
      serverVersion: 1,
    })
    state = await service.beginWorkSets(await service.completeGeneralWarmup(state))
    state = await service.recordSet(state, {
      clientSetKey: 'decision-set-0001', exerciseIndex: 0, setType: 'WORK',
      status: 'COMPLETED', actualWeightKg: 25, actualReps: 8,
    })
    await service.flush(state)

    await expect(service.rememberConflictResolution({
      conflictId: 'conflict-replay-0001',
      clientKey: 'decision-set-0001',
      resolution: 'KEEP_SERVER',
      expectedConflictVersion: 0,
    })).resolves.toBe(true)

    await expect(service.pendingConflictResolutions()).resolves.toEqual([{
      conflictId: 'conflict-replay-0001',
      clientKey: 'decision-set-0001',
      resolution: 'KEEP_SERVER',
      expectedConflictVersion: 0,
    }])
    await expect(service.rememberConflictResolution({
      conflictId: 'conflict-replay-0001',
      clientKey: 'decision-set-0001',
      resolution: 'KEEP_LOCAL',
      expectedConflictVersion: 0,
    })).rejects.toThrow('a different conflict resolution is already pending')
    await expect(service.pendingConflictResolutions()).resolves.toEqual([{
      conflictId: 'conflict-replay-0001',
      clientKey: 'decision-set-0001',
      resolution: 'KEEP_SERVER',
      expectedConflictVersion: 0,
    }])
  })

  it('routes an authenticated user to dedicated workout recovery without clearing the session', async () => {
    const clear = vi.fn()
    const navigate = vi.fn()
    const profileExists = vi.fn()
    const startup = createStartupUseCases({
      sessionStore: {
        load: async () => ({ accessToken: 'redacted', refreshToken: 'redacted', expiresAt: '2099-01-01T00:00:00Z' }),
        save: vi.fn(),
        clear,
      },
      wechatLogin: { getCode: vi.fn() },
      auth: { login: vi.fn() },
      workout: {
        hasActive: vi.fn().mockResolvedValue(false),
        getStartupState: vi.fn().mockResolvedValue('RECOVERY_REQUIRED'),
      },
      profile: { exists: profileExists },
      plan: { hasActivePlan: vi.fn() },
      navigation: { replace: navigate },
    })

    await expect(startup.start()).resolves.toBe('WORKOUT_SESSION')
    expect(navigate).toHaveBeenCalledWith('WORKOUT_SESSION')
    expect(clear).not.toHaveBeenCalled()
    expect(profileExists).not.toHaveBeenCalled()
  })
})

function memoryStore(): WorkoutDraftStore {
  let stored: WorkoutDraft | null = null
  return {
    loadActive: async () => stored,
    save: async (draft) => { stored = draft },
    clearActive: async (draftId) => { if (stored?.draftId === draftId) stored = null },
    discardCorrupted: async () => undefined,
  }
}

function fixedClock() {
  return { nowUtc: () => '2026-08-11T08:00:00.000Z' }
}

function localStartInput(clientSessionKey: string) {
  return {
    clientSessionKey,
    planVersionId: 'plan-version-id',
    exercises: [{
      snapshotExerciseKey: 'exercise-id',
      exerciseCode: 'ROW',
      name: '划船',
      targetWorkSets: 2,
      targetReps: 8,
      restSeconds: 60,
    }],
  }
}

function startRequest(clientSessionKey: string) {
  return {
    clientSessionKey,
    planId: 'plan-id',
    planVersionNo: 1,
    planDayId: 'DAY_A',
    warmupDurationSeconds: 180 as const,
  }
}

function serverSession(id: string) {
  return {
    id,
    planId: 'plan-id',
    planVersionId: 'plan-version-id',
    planVersionNo: 1,
    planDayId: 'DAY_A',
    status: 'IN_PROGRESS' as const,
    startedAt: '2026-08-11T08:00:00.000Z',
    version: 1,
    warmupPrescription: {
      schemaVersion: 'workout-warmup-prescription-v1' as const,
      ruleVersion: '1.3.0',
      generalWarmup: { occurrences: 1 as const, durationSeconds: 180 },
      rampWarmup: {
        exerciseId: 'exercise-id', exerciseOrder: 1, status: 'READY' as const,
        sets: [{ weightKg: 10, reps: 10 }, { weightKg: 17.5, reps: 6 }],
      },
      countsTowardTrainingVolume: false as const,
      countsTowardProgression: false as const,
    },
    exercises: [{
      id: 'exercise-id',
      order: 1,
      exerciseCode: 'ROW',
      exerciseName: '划船',
      contentVersion: 'v1',
      equipment: ['CABLE'],
      prescription: {
        workSets: 2,
        repMin: 8,
        repMax: 10,
        restSeconds: 60,
        weightStatus: 'KNOWN' as const,
        targetWeightKg: 25,
        unit: 'KG' as const,
      },
      status: 'ACTIVE' as const,
    }],
  }
}
