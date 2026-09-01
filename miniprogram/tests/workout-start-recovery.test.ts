import { describe, expect, it, vi } from 'vitest'

import { WorkoutStartCoordinator } from '../src/application/use-cases/WorkoutStartCoordinator'
import { createWorkoutFlow } from '../src/application/workoutFlow'
import { WorkoutRecoveryConfirmationRequiredError, WorkoutStartTerminalReplayError } from '../src/application/errors'
import {
  PendingWorkoutStartError,
  workoutStartIntentSchemaVersion,
  type WorkoutStartIntent,
} from '../src/application/ports/WorkoutStartIntentStore'

const state = createWorkoutFlow({
  clientSessionKey: 'recovery-session-0001',
  planVersionId: 'plan-version-1',
  exercises: [{
    snapshotExerciseKey: 'exercise-1',
    exerciseCode: 'DUMBBELL_BENCH_PRESS',
    name: '哑铃卧推',
    targetWorkSets: 3,
    targetReps: 10,
    restSeconds: 90,
  }],
})

const request = {
  clientSessionKey: 'recovery-session-0001',
  planId: '00000000-0000-4000-8000-000000000001',
  planVersionNo: 1,
  planDayId: 'DAY_1',
  warmupDurationSeconds: 180 as const,
}

const warning = {
  decision: 'CONFIRMATION_REQUIRED' as const,
  policyVersion: 'rules-v1',
  checkedAt: '2026-08-11T08:00:00Z',
  minimumRecoveryHours: 48,
  affectedMuscles: [{
    muscleGroup: 'CHEST',
    elapsedHours: 18,
    minimumRecoveryHours: 48,
    lastCompletedAt: '2026-08-10T14:00:00Z',
  }],
}

describe('workout start recovery coordinator', () => {
  it('returns a typed server challenge from the first direct start and retries with its token', async () => {
    const workouts = {
      loadStatus: vi.fn().mockResolvedValue({ kind: 'NONE' }),
      startOrResume: vi.fn()
        .mockRejectedValueOnce(new WorkoutRecoveryConfirmationRequiredError(
          warning,
          'recovery-confirmation-token',
          '2026-08-11T08:05:00Z',
        ))
        .mockResolvedValueOnce({ kind: 'STARTED', state }),
    }
    const coordinator = new WorkoutStartCoordinator(workouts)

    await expect(coordinator.start(request)).resolves.toEqual({
      kind: 'RECOVERY_CONFIRMATION_REQUIRED',
      assessment: warning,
      confirmationToken: 'recovery-confirmation-token',
      confirmationExpiresAt: '2026-08-11T08:05:00Z',
    })
    expect(workouts.startOrResume).toHaveBeenCalledWith(request)

    await expect(coordinator.start({
      ...request,
      recoveryConfirmationToken: 'recovery-confirmation-token',
    })).resolves.toMatchObject({ kind: 'STARTED' })
    expect(workouts.startOrResume).toHaveBeenNthCalledWith(2, {
      ...request,
      recoveryConfirmationToken: 'recovery-confirmation-token',
    })
  })

  it('coalesces repeated first-start taps before the server challenge returns', async () => {
    const workouts = {
      loadStatus: vi.fn().mockResolvedValue({ kind: 'NONE' }),
      startOrResume: vi.fn().mockRejectedValue(new WorkoutRecoveryConfirmationRequiredError(
        warning,
        'recovery-confirmation-token',
        '2026-08-11T08:05:00Z',
      )),
    }
    const coordinator = new WorkoutStartCoordinator(workouts)

    const [first, repeated] = await Promise.all([
      coordinator.start(request),
      coordinator.start(request),
    ])

    expect(first).toEqual(repeated)
    expect(workouts.startOrResume).toHaveBeenCalledOnce()
  })

  it('coalesces repeated explicit-confirmation taps for the same server token', async () => {
    const workouts = {
      loadStatus: vi.fn().mockResolvedValue({ kind: 'NONE' }),
      startOrResume: vi.fn().mockResolvedValue({ kind: 'STARTED', state }),
    }
    const coordinator = new WorkoutStartCoordinator(workouts)
    const confirmed = {
      ...request,
      recoveryConfirmationToken: 'recovery-confirmation-token',
    }

    const [first, repeated] = await Promise.all([
      coordinator.start(confirmed),
      coordinator.start(confirmed),
    ])

    expect(first).toEqual(repeated)
    expect(workouts.startOrResume).toHaveBeenCalledOnce()
    expect(workouts.startOrResume).toHaveBeenCalledWith(confirmed)
  })

  it('coalesces repeated active-workout replacement taps into one authoritative command', async () => {
    const workouts = {
      loadStatus: vi.fn(),
      startOrResume: vi.fn(),
      replaceActiveAndStart: vi.fn().mockResolvedValue({ kind: 'STARTED', state }),
    }
    const coordinator = new WorkoutStartCoordinator(workouts)

    const [first, repeated] = await Promise.all([
      coordinator.replaceActive(state, request),
      coordinator.replaceActive(state, request),
    ])

    expect(first).toEqual(repeated)
    expect(workouts.replaceActiveAndStart).toHaveBeenCalledOnce()
    expect(workouts.loadStatus).not.toHaveBeenCalled()
    expect(workouts.startOrResume).not.toHaveBeenCalled()
  })

  it('returns a recovery challenge from replacement without running the legacy start path', async () => {
    const workouts = {
      loadStatus: vi.fn(),
      startOrResume: vi.fn(),
      replaceActiveAndStart: vi.fn().mockRejectedValue(
        new WorkoutRecoveryConfirmationRequiredError(
          warning,
          'replacement-confirmation-token',
          '2026-08-11T08:05:00Z',
        ),
      ),
    }
    const coordinator = new WorkoutStartCoordinator(workouts)

    await expect(coordinator.replaceActive(state, request)).resolves.toMatchObject({
      kind: 'RECOVERY_CONFIRMATION_REQUIRED',
      confirmationToken: 'replacement-confirmation-token',
    })
    expect(workouts.replaceActiveAndStart).toHaveBeenCalledWith(state, request)
    expect(workouts.startOrResume).not.toHaveBeenCalled()
  })

  it('resumes an existing draft without applying a new-day recovery gate', async () => {
    const workouts = {
      loadStatus: vi.fn().mockResolvedValue({ kind: 'ACTIVE', state }),
      startOrResume: vi.fn().mockResolvedValue({ kind: 'RESUME_REQUIRED', state }),
    }
    const coordinator = new WorkoutStartCoordinator(workouts)

    await expect(coordinator.start(request)).resolves.toMatchObject({ kind: 'RESUME_REQUIRED' })
    expect(workouts.startOrResume).toHaveBeenCalledOnce()
  })

  it('reuses the durable client key after the page is rebuilt following a lost response', async () => {
    let intent: WorkoutStartIntent | null = null
    const intents = {
      claim: vi.fn(async (candidate: WorkoutStartIntent) => {
        intent ??= candidate
        return intent
      }),
      clear: vi.fn(async () => undefined),
    }
    const firstRuntime = {
      loadStatus: vi.fn().mockResolvedValue({ kind: 'NONE' }),
      startOrResume: vi.fn().mockRejectedValue(new Error('network response lost')),
    }
    const firstPage = new WorkoutStartCoordinator(firstRuntime, intents)
    await expect(firstPage.start(request)).rejects.toThrow('network response lost')

    const rebuiltRuntime = {
      loadStatus: vi.fn().mockResolvedValue({ kind: 'NONE' }),
      startOrResume: vi.fn().mockResolvedValue({ kind: 'STARTED', state }),
    }
    const rebuiltPage = new WorkoutStartCoordinator(rebuiltRuntime, intents)
    await expect(rebuiltPage.start({
      ...request,
      clientSessionKey: 'new-page-session-key',
    })).resolves.toMatchObject({ kind: 'STARTED' })

    expect(rebuiltRuntime.startOrResume).toHaveBeenCalledWith(expect.objectContaining({
      clientSessionKey: request.clientSessionKey,
    }))
    expect(intents.clear).toHaveBeenCalledWith(request.clientSessionKey)
  })

  it('clears a durable start intent when that exact server session has already ended', async () => {
    const clear = vi.fn(async () => undefined)
    const coordinator = new WorkoutStartCoordinator({
      loadStatus: vi.fn().mockResolvedValue({ kind: 'NONE' }),
      startOrResume: vi.fn().mockRejectedValue(new WorkoutStartTerminalReplayError({
        id: 'terminal-session-id',
        clientSessionKey: request.clientSessionKey,
        status: 'COMPLETED',
        version: 4,
      })),
    }, {
      claim: vi.fn().mockResolvedValue({
        schemaVersion: workoutStartIntentSchemaVersion,
        clientSessionKey: request.clientSessionKey,
        planId: request.planId,
        planVersionNo: request.planVersionNo,
        planDayId: request.planDayId,
      }),
      clear,
    })

    await expect(coordinator.start(request)).resolves.toEqual({ kind: 'TERMINAL_REPLAY' })
    expect(clear).toHaveBeenCalledWith(request.clientSessionKey)
  })

  it('does not reuse a pending key for a different training day', async () => {
    const intent: WorkoutStartIntent = {
      schemaVersion: workoutStartIntentSchemaVersion,
      clientSessionKey: request.clientSessionKey,
      planId: request.planId,
      planVersionNo: request.planVersionNo,
      planDayId: request.planDayId,
    }
    const workouts = {
      loadStatus: vi.fn(),
      startOrResume: vi.fn(),
    }
    const coordinator = new WorkoutStartCoordinator(workouts, {
      claim: vi.fn().mockResolvedValue(intent),
      clear: vi.fn(),
    })

    const error = await coordinator.start({
      ...request,
      clientSessionKey: 'new-page-session-key',
      planDayId: 'DAY_2',
    }).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(PendingWorkoutStartError)
    expect(workouts.startOrResume).not.toHaveBeenCalled()
  })

  it('clears the persisted key, not a rebuilt page key, after an explicitly uncreated recovery challenge', async () => {
    const clear = vi.fn().mockResolvedValue(undefined)
    const persisted = {
      schemaVersion: workoutStartIntentSchemaVersion,
      clientSessionKey: request.clientSessionKey,
      planId: request.planId,
      planVersionNo: request.planVersionNo,
      planDayId: request.planDayId,
    }
    const coordinator = new WorkoutStartCoordinator({
      loadStatus: vi.fn(),
      startOrResume: vi.fn(),
    }, {
      claim: vi.fn().mockResolvedValue(persisted),
      clear,
    })

    await coordinator.cancelUncreatedStart({
      ...request,
      clientSessionKey: 'rebuilt-page-session-key',
    })

    expect(clear).toHaveBeenCalledWith(request.clientSessionKey)
  })
})
