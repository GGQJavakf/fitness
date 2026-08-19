import { describe, expect, it } from 'vitest'

import {
  FitnessApiClient,
  type TransportPort,
  type TransportRequest,
  type TransportResponse,
} from '../src/infrastructure/api/client'
import {
  ActiveWorkoutExistsError,
  WorkoutStartTerminalReplayError,
  WorkoutRecoveryConfirmationRequiredError,
} from '../src/application/errors'

describe('workout session API lifecycle', () => {
  it('turns the start recovery 409 into a typed, server-issued confirmation challenge', async () => {
    const assessment = {
      decision: 'CONFIRMATION_REQUIRED',
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
    const transport: TransportPort = {
      async request<T>(): Promise<TransportResponse<T>> {
        return {
          statusCode: 409,
          data: {
            error: {
              code: 'RECOVERY_CONFIRMATION_REQUIRED',
              message: '需要明确确认恢复窗口提醒',
              fieldErrors: [],
              details: {
                assessment,
                confirmationToken: 'recovery-confirmation-token',
                confirmationExpiresAt: '2026-08-11T08:05:00Z',
              },
              retryable: false,
            },
            meta: { requestId: 'request-1' },
          } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, testSessions())

    const error = await api.startWorkoutSession({
      clientSessionKey: 'client-session-0001',
      planId: '00000000-0000-4000-8000-000000000020',
      planVersionNo: 1,
      planDayId: 'DAY_1',
    }).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(WorkoutRecoveryConfirmationRequiredError)
    expect(error).toMatchObject({
      code: 'RECOVERY_CONFIRMATION_REQUIRED',
      assessment,
      confirmationToken: 'recovery-confirmation-token',
      confirmationExpiresAt: '2026-08-11T08:05:00Z',
    })
  })

  it('sends the confirmation token only on the explicit retry while retaining the idempotency key', async () => {
    const requests: TransportRequest[] = []
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        requests.push(request)
        return {
          statusCode: 201,
          data: {
            data: {
              id: '00000000-0000-4000-8000-000000000010',
              planId: '00000000-0000-4000-8000-000000000020',
              planVersionId: '00000000-0000-4000-8000-000000000030',
              planVersionNo: 1,
              planDayId: 'DAY_1',
              trainingDayCode: 'DAY_1',
              status: 'IN_PROGRESS',
              startedAt: '2026-08-11T08:00:00Z',
              version: 1,
              exercises: [],
            },
          } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, testSessions())

    await api.startWorkoutSession({
      clientSessionKey: 'client-session-0001',
      planId: '00000000-0000-4000-8000-000000000020',
      planVersionNo: 1,
      planDayId: 'DAY_1',
      recoveryConfirmationToken: 'recovery-confirmation-token',
    })

    expect(requests).toEqual([expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'Idempotency-Key': 'client-session-0001' }),
      body: expect.objectContaining({
        clientSessionKey: 'client-session-0001',
        recoveryConfirmationToken: 'recovery-confirmation-token',
      }),
    })])
  })

  it('turns a competing start into a typed recoverable active workout snapshot', async () => {
    const activeSession = {
      id: '00000000-0000-4000-8000-000000000010',
      planId: '00000000-0000-4000-8000-000000000020',
      planVersionId: '00000000-0000-4000-8000-000000000030',
      planVersionNo: 1,
      clientSessionKey: 'existing-client-session-0001',
      planDayId: 'DAY_1',
      trainingDayCode: 'DAY_1',
      status: 'IN_PROGRESS',
      startedAt: '2026-08-11T08:00:00Z',
      version: 2,
      exercises: [{
        id: '00000000-0000-4000-8000-000000000040', order: 1,
        exerciseCode: 'ROW', exerciseName: '划船', contentVersion: 'content-v1',
        equipment: ['CABLE'], status: 'ACTIVE',
        prescription: {
          workSets: 2, repMin: 8, repMax: 10, restSeconds: 60,
          weightStatus: 'KNOWN', targetWeightKg: 25, unit: 'KG',
        },
      }],
    }
    const transport: TransportPort = {
      async request<T>(): Promise<TransportResponse<T>> {
        return {
          statusCode: 409,
          data: {
            error: {
              code: 'ACTIVE_WORKOUT_EXISTS', message: '已有训练', fieldErrors: [], retryable: false,
              details: { activeSession, sets: [] },
            },
            meta: { requestId: 'request-active-1' },
          } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, testSessions())

    const error = await api.startWorkoutSession({
      clientSessionKey: 'different-client-session-0002',
      planId: activeSession.planId,
      planVersionNo: 1,
      planDayId: 'DAY_2',
    }).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ActiveWorkoutExistsError)
    expect(error).toMatchObject({ code: 'ACTIVE_WORKOUT_EXISTS', activeWorkout: { session: activeSession, sets: [] } })
  })

  it('accepts legacy null optionals in active-set JSON and normalizes them before recovery', async () => {
    const exerciseId = '00000000-0000-4000-8000-000000000040'
    const activeSession = {
      id: '00000000-0000-4000-8000-000000000010',
      planId: '00000000-0000-4000-8000-000000000020',
      planVersionId: '00000000-0000-4000-8000-000000000030',
      planVersionNo: 1,
      clientSessionKey: 'existing-client-session-0001',
      planDayId: 'DAY_1', trainingDayCode: 'DAY_1', status: 'IN_PROGRESS',
      startedAt: '2026-08-11T08:00:00Z', version: 2,
      exercises: [{
        id: exerciseId, order: 1, exerciseCode: 'ROW', exerciseName: '划船',
        contentVersion: 'content-v1', equipment: ['CABLE'], status: 'ACTIVE',
        prescription: {
          workSets: 2, repMin: 8, repMax: 10, restSeconds: 60,
          weightStatus: 'NEEDS_CALIBRATION', targetWeightKg: null, unit: 'KG',
        },
      }],
    }
    const legacySet = {
      setId: '00000000-0000-4000-8000-000000000050', sessionExerciseId: exerciseId,
      clientSetKey: 'existing-set-0001', clientOperationSeq: 1, setType: 'WORK', setOrder: 1,
      target: { weight: { value: 25, unit: 'KG' }, reps: 10 },
      actual: { weight: { value: 25, unit: 'KG' }, reps: 7 },
      remainingReps: null, completionStatus: 'FAILED', completedAt: null,
      serverRevision: 0, sessionVersion: 2, safetyFlag: null, anomalyStatus: null,
      syncStatus: 'APPLIED',
    }
    const transport: TransportPort = {
      async request<T>(): Promise<TransportResponse<T>> {
        return {
          statusCode: 409,
          data: {
            error: {
              code: 'ACTIVE_WORKOUT_EXISTS', message: '已有训练', fieldErrors: [], retryable: false,
              details: { activeSession, sets: [legacySet] },
            },
            meta: { requestId: 'request-active-null-optionals' },
          } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, testSessions())

    const error = await api.startWorkoutSession({
      clientSessionKey: 'different-client-session-0002', planId: activeSession.planId,
      planVersionNo: 1, planDayId: 'DAY_2',
    }).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ActiveWorkoutExistsError)
    expect((error as ActiveWorkoutExistsError).activeWorkout.session.exercises[0].prescription)
      .toMatchObject({ weightStatus: 'NEEDS_CALIBRATION', targetWeightKg: undefined })
    expect((error as ActiveWorkoutExistsError).activeWorkout.sets[0]).toMatchObject({
      completionStatus: 'FAILED', remainingReps: undefined, completedAt: undefined,
      safetyFlag: undefined, anomalyStatus: undefined,
    })
  })

  it('rejects an invalid active-workout snapshot instead of persisting corrupt recovery data', async () => {
    const transport: TransportPort = {
      async request<T>(): Promise<TransportResponse<T>> {
        return {
          statusCode: 409,
          data: {
            error: {
              code: 'ACTIVE_WORKOUT_EXISTS', message: '已有训练', fieldErrors: [], retryable: false,
              details: {
                activeSession: {
                  id: 'session-id', planId: 'plan-id', planVersionId: 'version-id', planVersionNo: 1,
                  clientSessionKey: 'existing-client-key', planDayId: 'DAY_1', trainingDayCode: 'DAY_1',
                  status: 'IN_PROGRESS', startedAt: 'not-a-date', version: 1,
                  exercises: [{
                    id: 'exercise-id', order: 1, exerciseCode: 'ROW', exerciseName: '划船',
                    contentVersion: 'v1', equipment: [], status: 'ACTIVE',
                    prescription: {
                      workSets: 2, repMin: 8, repMax: 10, restSeconds: 60,
                      weightStatus: 'KNOWN', targetWeightKg: 25, unit: 'KG',
                    },
                  }],
                },
                sets: [],
              },
            },
            meta: { requestId: 'request-invalid-active' },
          } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, testSessions())

    const error = await api.startWorkoutSession({
      clientSessionKey: 'different-client-session-0002', planId: 'plan-id',
      planVersionNo: 1, planDayId: 'DAY_2',
    }).catch((reason: unknown) => reason)

    expect(error).toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(error).not.toBeInstanceOf(ActiveWorkoutExistsError)
  })

  it('turns a terminal durable-start replay into a typed non-retry result', async () => {
    const terminalSession = {
      id: '00000000-0000-4000-8000-000000000010',
      clientSessionKey: 'finished-client-session-0001', status: 'COMPLETED', version: 4,
    }
    const transport: TransportPort = {
      async request<T>(): Promise<TransportResponse<T>> {
        return {
          statusCode: 409,
          data: {
            error: {
              code: 'WORKOUT_START_ALREADY_TERMINAL', message: '训练已结束', fieldErrors: [], retryable: false,
              details: { terminalSession },
            },
            meta: { requestId: 'request-terminal-1' },
          } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, testSessions())

    const error = await api.startWorkoutSession({
      clientSessionKey: terminalSession.clientSessionKey,
      planId: '00000000-0000-4000-8000-000000000020', planVersionNo: 1, planDayId: 'DAY_1',
    }).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(WorkoutStartTerminalReplayError)
    expect(error).toMatchObject({ code: 'WORKOUT_START_ALREADY_TERMINAL', terminalSession })
  })

  it('preserves the typed non-terminal summary error instead of reporting a version conflict', async () => {
    const transport: TransportPort = {
      async request<T>(): Promise<TransportResponse<T>> {
        return {
          statusCode: 409,
          data: {
            error: {
              code: 'WORKOUT_NOT_TERMINAL',
              message: '训练尚未结束',
              fieldErrors: [],
              retryable: false,
            },
            meta: { requestId: 'request-summary-active' },
          } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, testSessions())

    const error = await api.getWorkoutSessionSummary(
      '00000000-0000-4000-8000-000000000010',
    ).catch((reason: unknown) => reason)

    expect(error).toMatchObject({
      code: 'WORKOUT_NOT_TERMINAL',
      message: '训练尚未结束，暂时无法查看训练总结',
      retryable: false,
    })
  })

  it('binds replacement candidates to the owned session snapshot', async () => {
    const requests: TransportRequest[] = []
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        requests.push(request)
        return {
          statusCode: 200,
          data: { data: { sourceCode: 'ROW/CABLE', items: [] } } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, testSessions())

    await api.listExerciseReplacements(
      '00000000-0000-4000-8000-000000000010',
      '00000000-0000-4000-8000-000000000040',
      'ROW/CABLE',
    )

    expect(requests).toEqual([expect.objectContaining({
      method: 'GET',
      url: 'http://127.0.0.1:8080/api/v1/workout-sessions/'
        + '00000000-0000-4000-8000-000000000010/exercises/'
        + '00000000-0000-4000-8000-000000000040/replacements',
    })])
  })

  it('normalizes a legacy null replacement weight and rejects corrupt replacement responses', async () => {
    const replacementSession = {
      id: '00000000-0000-4000-8000-000000000010',
      planId: '00000000-0000-4000-8000-000000000020',
      planVersionId: '00000000-0000-4000-8000-000000000030',
      planVersionNo: 1,
      clientSessionKey: 'replacement-session-0001',
      planDayId: 'DAY_1', trainingDayCode: 'DAY_1', status: 'IN_PROGRESS',
      startedAt: '2026-08-11T08:00:00Z', version: 2,
      exercises: [{
        id: '00000000-0000-4000-8000-000000000040', order: 1,
        exerciseCode: 'PUSH_UP', exerciseName: '俯卧撑', contentVersion: 'content-v2',
        equipment: ['BODYWEIGHT'], status: 'REPLACED',
        prescription: {
          workSets: 3, repMin: 8, repMax: 12, restSeconds: 90,
          weightStatus: 'BODYWEIGHT', targetWeightKg: null, unit: 'KG',
        },
      }],
    }
    let corrupt = false
    const transport: TransportPort = {
      async request<T>(): Promise<TransportResponse<T>> {
        return {
          statusCode: 200,
          data: { data: corrupt
            ? { ...replacementSession, exercises: [{ ...replacementSession.exercises[0], order: 0 }] }
            : replacementSession } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, testSessions())

    const normalized = await api.replaceWorkoutExercise(
      replacementSession.id, replacementSession.exercises[0].id, 'PUSH_UP', 1,
    )
    expect(normalized.exercises[0].prescription.targetWeightKg).toBeUndefined()

    corrupt = true
    await expect(api.replaceWorkoutExercise(
      replacementSession.id, replacementSession.exercises[0].id, 'PUSH_UP', 1,
    )).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('moves a newly created server session into progress before returning it to the workout UI', async () => {
    const requests: TransportRequest[] = []
    const created = {
      id: '00000000-0000-4000-8000-000000000010',
      planId: '00000000-0000-4000-8000-000000000020',
      planVersionId: '00000000-0000-4000-8000-000000000030',
      planVersionNo: 1,
      planDayId: 'DAY_1',
      status: 'CREATED',
      startedAt: '2026-08-02T08:00:00Z',
      version: 0,
      exercises: [],
    }
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        requests.push(request)
        const data = request.url.endsWith('/status')
          ? { ...created, status: 'IN_PROGRESS', version: 1 }
          : created
        return { statusCode: request.url.endsWith('/status') ? 200 : 201, data: { data } as T }
      },
    }
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      testSessions(),
    )

    const session = await api.startWorkoutSession({
      clientSessionKey: 'client-session-0001',
      planId: created.planId,
      planVersionNo: 1,
      planDayId: 'DAY_1',
    })

    expect(session).toMatchObject({ status: 'IN_PROGRESS', version: 1 })
    expect(requests).toHaveLength(2)
    expect(requests[0]).toMatchObject({
      method: 'POST',
      headers: { 'Idempotency-Key': 'client-session-0001' },
    })
    expect(requests[1]).toMatchObject({
      url: `http://127.0.0.1:8080/api/v1/workout-sessions/${created.id}/status`,
      method: 'PUT',
      body: { status: 'IN_PROGRESS', expectedVersion: 0 },
    })
  })
})

function testSessions() {
  return {
    load: async () => ({
      accessToken: 'access-redacted',
      refreshToken: 'refresh-redacted',
      expiresAt: '2026-08-02T09:00:00Z',
    }),
    save: async () => undefined,
    clear: async () => undefined,
  }
}
