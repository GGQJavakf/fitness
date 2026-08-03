import { describe, expect, it } from 'vitest'

import {
  FitnessApiClient,
  type TransportPort,
  type TransportRequest,
  type TransportResponse,
} from '../src/infrastructure/api/client'

describe('workout session API lifecycle', () => {
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
      {
        load: async () => ({
          accessToken: 'access-redacted',
          refreshToken: 'refresh-redacted',
          expiresAt: '2026-08-02T09:00:00Z',
        }),
        save: async () => undefined,
        clear: async () => undefined,
      },
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
