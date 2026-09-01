import { describe, expect, it, vi } from 'vitest'

import type { ActivePlanData } from '../src/application/models'
import { createWorkoutFlow, type WorkoutFlowState } from '../src/application/workoutFlow'
import { createWorkoutGenerationOperations } from '../src/platform/weapp/featureRoots/workoutGenerationOperations'

class TestGenerationInvalidatedError extends Error {}

function createGenerationGuard() {
  let generation = 0
  return {
    capture: () => generation,
    assertCurrent(expected: number): void {
      if (expected !== generation) throw new TestGenerationInvalidatedError()
    },
    begin(): number {
      generation += 1
      return generation
    },
    invalidate(): void {
      generation += 1
    },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

function createDependencies() {
  const userGeneration = createGenerationGuard()
  return {
    userGeneration,
    workouts: {
      load: vi.fn().mockResolvedValue(null),
      loadStatus: vi.fn().mockResolvedValue({ kind: 'NONE' }),
      resume: vi.fn(),
      resumeLocal: vi.fn(),
      complete: vi.fn(),
      abandonActive: vi.fn().mockResolvedValue(undefined),
      replaceActiveAndStart: vi.fn(),
      discardOrphanedLocalWorkout: vi.fn().mockResolvedValue(undefined),
      adjustRest: vi.fn(),
      setExerciseWeight: vi.fn(),
      recordSet: vi.fn(),
      beginWorkSets: vi.fn(),
      chooseOptionalSet: vi.fn(),
      flush: vi.fn(),
      discardCorruptedDraft: vi.fn().mockResolvedValue(undefined),
    },
    workoutStart: {
      start: vi.fn(),
      replaceActive: vi.fn(),
      cancelUncreatedStart: vi.fn().mockResolvedValue(undefined),
    },
    nextTrainingDaySelection: {
      consume: vi.fn().mockResolvedValue(undefined),
      remember: vi.fn().mockResolvedValue(undefined),
    },
    api: {
      getActivePlan: vi.fn().mockResolvedValue(null),
      listHistory: vi.fn().mockResolvedValue({ items: [], hasMore: false }),
      getExerciseTrend: vi.fn().mockResolvedValue({
        exerciseCode: 'SQUAT', unit: 'KG', points: [],
      }),
    },
    navigation: {
      replace: vi.fn().mockResolvedValue(undefined),
    },
    requestWorkoutSummary: vi.fn(),
    delay: vi.fn().mockResolvedValue(undefined),
  }
}

describe('workout generation-composed operations', () => {
  it('does not consume the new user selection when the old plan read finishes after a generation change', async () => {
    const dependencies = createDependencies()
    const planRead = deferred<ActivePlanData | null>()
    dependencies.api.getActivePlan.mockReturnValueOnce(planRead.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const pending = operations.loadWorkoutPreparation()
    await Promise.resolve()
    dependencies.userGeneration.begin()
    planRead.resolve(null)

    await expect(pending).rejects.toBeInstanceOf(TestGenerationInvalidatedError)
    expect(dependencies.nextTrainingDaySelection.consume).not.toHaveBeenCalled()
  })

  it('does not open a replacement for the new user after the atomic command resolves', async () => {
    const dependencies = createDependencies()
    const replaced = deferred<{ kind: 'STARTED'; state: WorkoutFlowState }>()
    dependencies.workoutStart.replaceActive.mockReturnValueOnce(replaced.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const pending = operations.abandonAndStartWorkout(
      { clientSessionKey: 'user-a-workout' } as WorkoutFlowState,
      {
        clientSessionKey: 'user-a-new-workout',
        planId: 'user-a-plan',
        planVersionNo: 1,
        planDayId: 'DAY_A',
      },
    )
    dependencies.userGeneration.begin()
    replaced.resolve({
      kind: 'STARTED',
      state: { clientSessionKey: 'user-a-new-workout' } as WorkoutFlowState,
    })

    await expect(pending).resolves.toBeNull()
    expect(dependencies.navigation.replace).not.toHaveBeenCalled()
  })

  it('does not open the workout session when an old start resolves before the new generation runs', async () => {
    const dependencies = createDependencies()
    const started = deferred<{ kind: 'STARTED'; state: WorkoutFlowState }>()
    dependencies.workoutStart.start.mockReturnValueOnce(started.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const pending = operations.startWorkout({
      clientSessionKey: 'user-a-new-workout',
      planId: 'user-a-plan',
      planVersionNo: 1,
      planDayId: 'DAY_A',
    })
    started.resolve({
      kind: 'STARTED',
      state: { clientSessionKey: 'user-a-new-workout' } as WorkoutFlowState,
    })
    dependencies.userGeneration.begin()

    await expect(pending).resolves.toBeNull()
    expect(dependencies.navigation.replace).not.toHaveBeenCalled()
  })

  it('does not open the workout session when an old abandon-and-start resolves into a new generation', async () => {
    const dependencies = createDependencies()
    const started = deferred<{ kind: 'STARTED'; state: WorkoutFlowState }>()
    dependencies.workoutStart.replaceActive.mockReturnValueOnce(started.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const pending = operations.abandonAndStartWorkout(
      { clientSessionKey: 'user-a-workout' } as WorkoutFlowState,
      {
        clientSessionKey: 'user-a-new-workout',
        planId: 'user-a-plan',
        planVersionNo: 1,
        planDayId: 'DAY_A',
      },
    )
    await Promise.resolve()
    started.resolve({
      kind: 'STARTED',
      state: { clientSessionKey: 'user-a-new-workout' } as WorkoutFlowState,
    })
    dependencies.userGeneration.begin()

    await expect(pending).resolves.toBeNull()
    expect(dependencies.navigation.replace).not.toHaveBeenCalled()
  })

  it('marks a newly created workout as a fresh launch when opening the session page', async () => {
    const dependencies = createDependencies()
    const state = { clientSessionKey: 'fresh-workout' } as WorkoutFlowState
    dependencies.workoutStart.start.mockResolvedValueOnce({ kind: 'STARTED', state })
    const operations = createWorkoutGenerationOperations(dependencies)

    await expect(operations.startWorkout({
      clientSessionKey: 'fresh-workout',
      planId: 'plan-id',
      planVersionNo: 1,
      planDayId: 'DAY_1',
    })).resolves.toMatchObject({ kind: 'STARTED' })

    expect(dependencies.navigation.replace).toHaveBeenCalledWith('WORKOUT_SESSION', {
      workoutLaunchMode: 'FRESH_START',
      clientSessionKey: 'fresh-workout',
    })
  })

  it('marks an interrupted workout as a recovery launch when opening the session page', async () => {
    const dependencies = createDependencies()
    const state = { clientSessionKey: 'interrupted-workout' } as WorkoutFlowState
    dependencies.workoutStart.start.mockResolvedValueOnce({ kind: 'RESUMED', state })
    const operations = createWorkoutGenerationOperations(dependencies)

    await expect(operations.startWorkout({
      clientSessionKey: 'interrupted-workout',
      planId: 'plan-id',
      planVersionNo: 1,
      planDayId: 'DAY_1',
      activeDraftDecision: 'RESUME',
    })).resolves.toMatchObject({ kind: 'RESUMED' })

    expect(dependencies.navigation.replace).toHaveBeenCalledWith('WORKOUT_SESSION', {
      workoutLaunchMode: 'RESUME_INTERRUPTED',
      clientSessionKey: 'interrupted-workout',
    })
  })

  it('reports an atomic replacement failure without claiming the old workout ended', async () => {
    const dependencies = createDependencies()
    const failure = new Error('new workout request failed')
    const phases: string[] = []
    dependencies.workoutStart.replaceActive.mockRejectedValueOnce(failure)
    const operations = createWorkoutGenerationOperations(dependencies)

    const result = operations.abandonAndStartWorkout(
      { clientSessionKey: 'old-workout' } as WorkoutFlowState,
      {
        clientSessionKey: 'new-workout',
        planId: 'plan-id',
        planVersionNo: 1,
        planDayId: 'DAY_1',
      },
      { onPhaseChanged: (phase) => phases.push(phase) },
    ).catch((error: unknown) => error)

    await expect(result).resolves.toMatchObject({
      name: 'WorkoutReplacementWorkflowError',
      phase: 'ENDING_ACTIVE',
      failure,
    })
    expect(phases).toEqual(['ENDING_ACTIVE'])
    expect(dependencies.workouts.abandonActive).not.toHaveBeenCalled()
    expect(dependencies.workoutStart.start).not.toHaveBeenCalled()
  })

  it('keeps the old workout intact while replacement awaits recovery confirmation', async () => {
    const dependencies = createDependencies()
    const phases: string[] = []
    dependencies.workoutStart.replaceActive.mockResolvedValueOnce({
      kind: 'RECOVERY_CONFIRMATION_REQUIRED',
      assessment: {} as never,
      confirmationToken: 'replacement-confirmation-token',
      confirmationExpiresAt: '2026-08-29T08:05:00Z',
    })
    const operations = createWorkoutGenerationOperations(dependencies)

    await expect(operations.abandonAndStartWorkout(
      { clientSessionKey: 'old-workout' } as WorkoutFlowState,
      {
        clientSessionKey: 'new-workout', planId: 'plan-id',
        planVersionNo: 1, planDayId: 'DAY_1',
      },
      { onPhaseChanged: (phase) => phases.push(phase) },
    )).resolves.toMatchObject({ kind: 'RECOVERY_CONFIRMATION_REQUIRED' })

    expect(phases).toEqual(['ENDING_ACTIVE'])
    expect(dependencies.navigation.replace).not.toHaveBeenCalled()
  })

  it('does not open the plan when an old start cancellation resolves into a new generation', async () => {
    const dependencies = createDependencies()
    const cancelled = deferred<void>()
    dependencies.workoutStart.cancelUncreatedStart.mockReturnValueOnce(cancelled.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const pending = operations.cancelWorkoutStartAndOpenPlan({
      clientSessionKey: 'user-a-new-workout',
      planId: 'user-a-plan',
      planVersionNo: 1,
      planDayId: 'DAY_A',
    })
    cancelled.resolve()
    dependencies.userGeneration.begin()

    await expect(pending).resolves.toBe(false)
    expect(dependencies.navigation.replace).not.toHaveBeenCalled()
  })

  it('does not resume the new user draft when the old load status resolves after a generation change', async () => {
    const dependencies = createDependencies()
    const statusRead = deferred<{ kind: 'ACTIVE'; state: WorkoutFlowState }>()
    dependencies.workouts.loadStatus.mockReturnValueOnce(statusRead.promise)
    const operations = createWorkoutGenerationOperations(dependencies)
    const oldState = { clientSessionKey: 'user-a-workout' } as WorkoutFlowState

    const pending = operations.loadWorkoutSession({ launchMode: 'RESUME_INTERRUPTED' })
    dependencies.userGeneration.begin()
    statusRead.resolve({ kind: 'ACTIVE', state: oldState })

    await expect(pending).rejects.toBeInstanceOf(TestGenerationInvalidatedError)
    expect(dependencies.workouts.resumeLocal).not.toHaveBeenCalled()
  })

  it('renders a recovered local workout before a slow background synchronization finishes', async () => {
    const dependencies = createDependencies()
    const state = {
      ...createWorkoutFlow({
        clientSessionKey: 'local-first-recovery',
        planVersionId: 'plan-version-local-first',
        exercises: [{
          snapshotExerciseKey: 'exercise-local-first',
          exerciseCode: 'ROW',
          name: '划船',
          targetWorkSets: 1,
          targetReps: 8,
          restSeconds: 60,
        }],
      }),
      syncStatus: 'OFFLINE_PENDING' as const,
    }
    const resumed = {
      state,
      remainingSeconds: 42,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
    }
    const synchronized = deferred<WorkoutFlowState>()
    dependencies.workouts.loadStatus.mockResolvedValueOnce({ kind: 'ACTIVE', state })
    dependencies.workouts.resumeLocal.mockResolvedValueOnce(resumed)
    dependencies.workouts.flush.mockReturnValueOnce(synchronized.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const loaded = await operations.loadWorkoutSession({ launchMode: 'RESUME_INTERRUPTED' })

    expect(loaded).toMatchObject({ kind: 'ACTIVE', resumed })
    expect(dependencies.workouts.flush).toHaveBeenCalledWith(state)
    if (loaded.kind !== 'ACTIVE') throw new Error('expected active local recovery')

    synchronized.resolve({ ...state, syncStatus: 'SYNCED' })
    await expect(loaded.synchronization).resolves.toMatchObject({
      kind: 'SYNCED',
      state: { syncStatus: 'SYNCED' },
    })
  })

  it('never opens an unrelated active draft for a fresh launch', async () => {
    const dependencies = createDependencies()
    const existing = { clientSessionKey: 'older-workout' } as WorkoutFlowState
    dependencies.workouts.loadStatus.mockResolvedValueOnce({ kind: 'ACTIVE', state: existing })
    const operations = createWorkoutGenerationOperations(dependencies)

    await expect(operations.loadWorkoutSession({
      launchMode: 'FRESH_START',
      clientSessionKey: 'expected-new-workout',
    })).resolves.toEqual({ kind: 'SESSION_MISMATCH' })
    expect(dependencies.workouts.resumeLocal).not.toHaveBeenCalled()
  })

  it('opens the matching active draft as the newly started workout', async () => {
    const dependencies = createDependencies()
    const state = createWorkoutFlow({
      clientSessionKey: 'expected-new-workout',
      planVersionId: 'fresh-plan-version',
      exercises: [{
        snapshotExerciseKey: 'fresh-exercise',
        exerciseCode: 'SQUAT',
        name: '深蹲',
        targetWorkSets: 1,
        targetReps: 8,
        restSeconds: 60,
      }],
    })
    const resumed = {
      state,
      remainingSeconds: 0,
      warmupRemainingSeconds: 60,
      clockRollbackDetected: false,
    }
    dependencies.workouts.loadStatus.mockResolvedValueOnce({ kind: 'ACTIVE', state })
    dependencies.workouts.resumeLocal.mockResolvedValueOnce(resumed)
    const operations = createWorkoutGenerationOperations(dependencies)

    await expect(operations.loadWorkoutSession({
      launchMode: 'FRESH_START',
      clientSessionKey: state.clientSessionKey,
    })).resolves.toMatchObject({ kind: 'ACTIVE', launchMode: 'FRESH_START', resumed })
    expect(dependencies.workouts.resumeLocal).toHaveBeenCalledWith(state)
  })

  it('recovers a failed record locally before a slow synchronization finishes', async () => {
    const dependencies = createDependencies()
    const state = {
      ...createWorkoutFlow({
        clientSessionKey: 'record-recovery-local-first',
        planVersionId: 'plan-version-record-recovery',
        exercises: [{
          snapshotExerciseKey: 'exercise-record-recovery',
          exerciseCode: 'ROW',
          name: '划船',
          targetWorkSets: 1,
          targetReps: 8,
          restSeconds: 60,
        }],
      }),
      syncStatus: 'OFFLINE_PENDING' as const,
    }
    const resumed = {
      state,
      remainingSeconds: 30,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
    }
    const synchronized = deferred<WorkoutFlowState>()
    dependencies.workouts.recordSet.mockRejectedValueOnce(new Error('local write interrupted'))
    dependencies.workouts.load.mockResolvedValueOnce(state)
    dependencies.workouts.resume.mockReturnValueOnce(new Promise(() => undefined))
    dependencies.workouts.resumeLocal.mockResolvedValueOnce(resumed)
    dependencies.workouts.flush.mockReturnValueOnce(synchronized.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const pending = operations.recordWorkoutSetAndSync(state, {
      clientSetKey: 'record-recovery-set',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 10,
      actualReps: 8,
    })
    const outcome = await Promise.race([
      pending,
      new Promise<'BLOCKED'>((resolve) => setTimeout(() => resolve('BLOCKED'), 20)),
    ])

    expect(outcome).not.toBe('BLOCKED')
    if (outcome === 'BLOCKED' || outcome.kind !== 'RECORD_FAILED' || !outcome.recovery) {
      throw new Error('expected local-first record recovery')
    }
    expect(outcome.recovery.resumed).toEqual(resumed)
    expect(dependencies.workouts.resumeLocal).toHaveBeenCalledWith(state)
    expect(dependencies.workouts.resume).not.toHaveBeenCalled()

    synchronized.resolve({ ...state, syncStatus: 'SYNCED' })
    await expect(outcome.recovery.synchronization).resolves.toMatchObject({
      kind: 'SYNCED',
      state: { syncStatus: 'SYNCED' },
    })
  })

  it('returns a rest adjustment before a slow synchronization finishes', async () => {
    const dependencies = createDependencies()
    const state = {
      ...createWorkoutFlow({
        clientSessionKey: 'rest-adjustment-local-first',
        planVersionId: 'plan-version-rest-adjustment',
        exercises: [{
          snapshotExerciseKey: 'exercise-rest-adjustment',
          exerciseCode: 'SQUAT',
          name: '深蹲',
          targetWorkSets: 1,
          targetReps: 8,
          restSeconds: 60,
        }],
      }),
      syncStatus: 'OFFLINE_PENDING' as const,
    }
    const resumed = {
      state,
      remainingSeconds: 45,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
    }
    const synchronized = deferred<WorkoutFlowState>()
    dependencies.workouts.adjustRest.mockResolvedValueOnce(state)
    dependencies.workouts.resume.mockReturnValueOnce(new Promise(() => undefined))
    dependencies.workouts.resumeLocal.mockResolvedValueOnce(resumed)
    dependencies.workouts.flush.mockReturnValueOnce(synchronized.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const pending = operations.adjustAndResumeWorkout(state, 15)
    const outcome = await Promise.race([
      pending,
      new Promise<'BLOCKED'>((resolve) => setTimeout(() => resolve('BLOCKED'), 20)),
    ])

    expect(outcome).not.toBe('BLOCKED')
    if (outcome === 'BLOCKED') throw new Error('expected local-first rest adjustment')
    expect(outcome.resumed).toEqual(resumed)
    expect(dependencies.workouts.resumeLocal).toHaveBeenCalledWith(state)
    expect(dependencies.workouts.resume).not.toHaveBeenCalled()

    synchronized.resolve({ ...state, syncStatus: 'SYNCED' })
    await expect(outcome.synchronization).resolves.toMatchObject({ kind: 'SYNCED' })
  })

  it('does not resume after an old rest adjustment resolves in the new generation', async () => {
    const dependencies = createDependencies()
    const adjusted = deferred<WorkoutFlowState>()
    dependencies.workouts.adjustRest.mockReturnValueOnce(adjusted.promise)
    const operations = createWorkoutGenerationOperations(dependencies)
    const oldState = { clientSessionKey: 'user-a-workout' } as WorkoutFlowState

    const pending = operations.adjustAndResumeWorkout(oldState, 15)
    dependencies.userGeneration.begin()
    adjusted.resolve(oldState)

    await expect(pending).rejects.toBeInstanceOf(TestGenerationInvalidatedError)
    expect(dependencies.workouts.resume).not.toHaveBeenCalled()
  })

  it('does not resume a recovered old draft when record failure recovery crosses generations', async () => {
    const dependencies = createDependencies()
    const draftRead = deferred<WorkoutFlowState | null>()
    dependencies.workouts.recordSet.mockRejectedValueOnce(new Error('write failed'))
    dependencies.workouts.load.mockReturnValueOnce(draftRead.promise)
    const operations = createWorkoutGenerationOperations(dependencies)
    const oldState = { clientSessionKey: 'user-a-workout' } as WorkoutFlowState

    const pending = operations.recordWorkoutSetAndSync(oldState, {
      clientSetKey: 'user-a-set-1',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 10,
      actualReps: 8,
      safetyFlag: 'PAIN',
    })
    await Promise.resolve()
    await Promise.resolve()
    expect(dependencies.workouts.load).toHaveBeenCalledOnce()
    dependencies.userGeneration.begin()
    draftRead.resolve(oldState)

    await expect(pending).rejects.toBeInstanceOf(TestGenerationInvalidatedError)
    expect(dependencies.workouts.resume).not.toHaveBeenCalled()
  })

  it('does not save a weight for the new user when the old trend read resolves late', async () => {
    const dependencies = createDependencies()
    const trend = deferred<{
      exerciseCode: string
      unit: 'KG'
      points: readonly {
        sessionId: string
        completedAt: string
        topWeightKg: number
        totalReps: number
        workSetCount: number
      }[]
    }>()
    dependencies.api.getExerciseTrend.mockReturnValueOnce(trend.promise)
    const operations = createWorkoutGenerationOperations(dependencies)
    const oldState = { clientSessionKey: 'user-a-workout' } as WorkoutFlowState

    const pending = operations.setAutomaticWorkoutWeight(
      oldState,
      0,
      'SQUAT',
      10,
    )
    dependencies.userGeneration.begin()
    trend.resolve({ exerciseCode: 'SQUAT', unit: 'KG', points: [] })

    await expect(pending).rejects.toBeInstanceOf(TestGenerationInvalidatedError)
    expect(dependencies.workouts.setExerciseWeight).not.toHaveBeenCalled()
  })

  it('does not begin work sets for the new user after the old ramp set write resolves', async () => {
    const dependencies = createDependencies()
    const recorded = deferred<WorkoutFlowState>()
    dependencies.workouts.recordSet.mockReturnValueOnce(recorded.promise)
    const operations = createWorkoutGenerationOperations(dependencies)
    const oldState = { clientSessionKey: 'user-a-workout' } as WorkoutFlowState

    const pending = operations.recordRampSetAndMaybeBeginWorkSets(oldState, {
      clientSetKey: 'user-a-ramp-1',
      exerciseIndex: 0,
      setType: 'WARMUP',
      status: 'COMPLETED',
      actualWeightKg: 5,
      actualReps: 8,
    }, true)
    dependencies.userGeneration.begin()
    recorded.resolve(oldState)

    await expect(pending).rejects.toBeInstanceOf(TestGenerationInvalidatedError)
    expect(dependencies.workouts.beginWorkSets).not.toHaveBeenCalled()
  })

  it('cancels a stale background flush without leaving a rejected promise', async () => {
    const dependencies = createDependencies()
    const synchronized = deferred<WorkoutFlowState>()
    const oldState = { clientSessionKey: 'user-a-workout' } as WorkoutFlowState
    dependencies.workouts.recordSet.mockResolvedValueOnce(oldState)
    dependencies.workouts.flush.mockReturnValueOnce(synchronized.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const result = await operations.recordWorkoutSetAndSync(oldState, {
      clientSetKey: 'user-a-set-1',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 10,
      actualReps: 8,
      safetyFlag: 'PAIN',
    })
    expect(result.kind).toBe('RECORDED')
    expect(dependencies.workouts.flush).toHaveBeenCalledWith(oldState)
    dependencies.userGeneration.begin()
    synchronized.resolve(oldState)

    if (result.kind !== 'RECORDED') throw new Error('record unexpectedly failed')
    await expect(result.synchronization).resolves.toEqual({ kind: 'CANCELLED' })
  })

  it('does not navigate after an old corrupted draft discard resolves in the new generation', async () => {
    const dependencies = createDependencies()
    const discarded = deferred<void>()
    dependencies.workouts.discardCorruptedDraft.mockReturnValueOnce(discarded.promise)
    const operations = createWorkoutGenerationOperations(dependencies)

    const pending = operations.discardCorruptedDraftAndOpenPlan()
    dependencies.userGeneration.begin()
    discarded.resolve()

    await expect(pending).rejects.toBeInstanceOf(TestGenerationInvalidatedError)
    expect(dependencies.navigation.replace).not.toHaveBeenCalled()
  })
})
