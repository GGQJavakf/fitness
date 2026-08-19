import { describe, expect, it } from 'vitest'

import {
  FitnessApiClient,
  type TransportPort,
  type TransportRequest,
  type TransportResponse,
} from '../src/infrastructure/api/client'

describe('workout recovery API', () => {
  it('queries the selected plan day without sending user identity from the client', async () => {
    const requests: TransportRequest[] = []
    const assessment = {
      decision: 'READY',
      policyVersion: 'rules-v1',
      checkedAt: '2026-08-11T08:00:00Z',
      minimumRecoveryHours: 48,
      affectedMuscles: [],
    }
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        requests.push(request)
        return { statusCode: 200, data: { data: assessment } as T }
      },
    }
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      {
        load: async () => ({
          accessToken: 'access-redacted',
          refreshToken: 'refresh-redacted',
          expiresAt: '2026-08-11T09:00:00Z',
        }),
        save: async () => undefined,
        clear: async () => undefined,
      },
    )

    await expect(api.checkWorkoutRecovery({
      planId: '00000000-0000-4000-8000-000000000001',
      planVersionNo: 1,
      trainingDayCode: 'DAY A/1',
    })).resolves.toEqual(assessment)

    expect(requests).toEqual([expect.objectContaining({
      method: 'GET',
      url: 'http://127.0.0.1:8080/api/v1/workout-recovery-checks?planId=00000000-0000-4000-8000-000000000001&planVersionNo=1&trainingDayCode=DAY%20A%2F1',
    })])
    expect(requests[0].body).toBeUndefined()
    expect(requests[0].url).not.toContain('userId')
  })
})
