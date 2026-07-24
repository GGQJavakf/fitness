import { describe, expect, it, vi } from 'vitest'

import { FitnessApiClient, type TransportPort, type TransportRequest } from '../src/infrastructure/api/client'

function client(assertRequest: (request: TransportRequest) => void): FitnessApiClient {
  const transport: TransportPort = {
    async request<T>(request: TransportRequest) {
      assertRequest(request)
      return {
        statusCode: 200,
        data: {
          data: { status: 'DEGRADED', content: '规则模板', validationStatus: 'AI_DISABLED' },
          meta: { requestId: 'request-1', serverTime: '2026-07-24T12:00:00Z' },
        } as T,
      }
    },
  }
  return new FitnessApiClient('http://127.0.0.1:8080', transport, {
    load: vi.fn().mockResolvedValue({
      accessToken: 'access-redacted', refreshToken: 'refresh-redacted', expiresAt: '2026-07-25T00:00:00Z',
    }),
    save: vi.fn(),
    clear: vi.fn(),
  })
}

describe('AI content API adapter', () => {
  it('requests plan explanation without sending candidate facts or user data from the client', async () => {
    const api = client((request) => expect(request).toMatchObject({
      method: 'POST',
      url: 'http://127.0.0.1:8080/api/v1/ai/plan-explanations',
      body: { candidateId: 'candidate-1' },
    }))

    await expect(api.requestPlanExplanation('candidate-1')).resolves.toMatchObject({
      status: 'DEGRADED', validationStatus: 'AI_DISABLED',
    })
  })

  it('requests a workout summary only by the owned session reference', async () => {
    const api = client((request) => expect(request.body).toEqual({ workoutSessionId: 'session-1' }))
    await expect(api.requestWorkoutSummary('session-1')).resolves.toMatchObject({ content: '规则模板' })
  })
})
