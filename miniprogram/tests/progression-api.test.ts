import { describe, expect, it, vi } from 'vitest'

import { FitnessApiClient, type TransportPort, type TransportRequest } from '../src/infrastructure/api/client'

const meta = { requestId: 'request-1', serverTime: '2026-07-24T12:00:00Z' }

function client(request: (value: TransportRequest) => unknown): FitnessApiClient {
  const transport: TransportPort = {
    async request<T>(value: TransportRequest) {
      return { statusCode: 200, data: request(value) as T }
    },
  }
  return new FitnessApiClient(
    'http://127.0.0.1:8080',
    transport,
    {
      load: vi.fn().mockResolvedValue({
        accessToken: 'access-redacted', refreshToken: 'refresh-redacted', expiresAt: '2026-07-25T00:00:00Z',
      }),
      save: vi.fn(),
      clear: vi.fn(),
    },
  )
}

describe('progression API adapter', () => {
  it('sends the accepted KG value, plan version and idempotency key to apply', async () => {
    const api = client((request) => {
      expect(request).toMatchObject({
        method: 'POST',
        url: 'http://127.0.0.1:8080/api/v1/progression-recommendations/recommendation-1/apply',
        headers: { 'Idempotency-Key': 'apply-once-1234' },
        body: { expectedVersion: 2, acceptedWeight: { value: 45, unit: 'KG' } },
      })
      return {
        data: {
          id: 'recommendation-1', exerciseCode: 'GOBLET_SQUAT', status: 'MODIFIED', decision: 'INCREASE',
          reasonCode: 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR', currentWeightKg: 40,
          recommendedWeightKg: 42.5, acceptedWeightKg: 45, algorithmVersion: 'double-progression-v1',
          createdAt: '2026-07-24T10:00:00Z',
        },
        meta,
      }
    })

    const result = await api.applyRecommendation('recommendation-1', 2, 45, 'apply-once-1234')

    expect(result.status).toBe('MODIFIED')
    expect(result.acceptedWeightKg).toBe(45)
  })

  it('reads trend points without calculating or altering their numbers', async () => {
    const api = client((request) => {
      expect(request.url).toBe('http://127.0.0.1:8080/api/v1/progress/exercises/GOBLET_SQUAT')
      return {
        data: {
          exerciseCode: 'GOBLET_SQUAT', unit: 'KG',
          points: [{ sessionId: 'session-1', completedAt: '2026-07-24T09:00:00Z', topWeightKg: 42.5, totalReps: 24, workSetCount: 3 }],
        },
        meta,
      }
    })

    const trend = await api.getExerciseTrend('GOBLET_SQUAT')

    expect(trend.points[0]).toMatchObject({ topWeightKg: 42.5, totalReps: 24, workSetCount: 3 })
  })
})
