import { describe, expect, it, vi } from 'vitest'

import { FitnessApiClient, type TransportPort, type TransportRequest } from '../src/infrastructure/api/client'

const meta = { requestId: 'request-1', serverTime: '2026-07-24T12:00:00Z' }

function client(
  request: (value: TransportRequest) => unknown,
  headers: Readonly<Record<string, string>> = {},
): FitnessApiClient {
  const transport: TransportPort = {
    async request<T>(value: TransportRequest) {
      return { statusCode: 200, data: request(value) as T, headers }
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
  it('preserves the stable recommendation cursor and bounded page metadata', async () => {
    const api = client((request) => {
      expect(request.url).toBe(
        'http://127.0.0.1:8080/api/v1/progression-recommendations?status=PENDING&cursor=cursor-page-2&limit=10',
      )
      return {
        data: [{
          id: 'recommendation-2', exerciseCode: 'GOBLET_SQUAT', status: 'PENDING', decision: 'INCREASE',
          reasonCode: 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR', currentWeightKg: 40,
          recommendedWeightKg: 42.5, algorithmVersion: 'double-progression-v1',
          createdAt: '2026-07-24T10:00:00Z',
        }],
        meta,
      }
    }, { 'x-has-more': 'true', 'x-next-cursor': 'cursor-page-3' })

    await expect(api.listRecommendations('PENDING', 'cursor-page-2', 10)).resolves.toMatchObject({
      items: [{ id: 'recommendation-2' }],
      hasMore: true,
      nextCursor: 'cursor-page-3',
    })
  })

  it('rejects recommendation pages whose cursor headers contradict each other', async () => {
    const api = client(() => ({ data: [], meta }), { 'x-has-more': 'true' })

    await expect(api.listRecommendations('PENDING')).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    })
  })

  it('preserves pagination metadata from the retried response after refreshing a 401 session', async () => {
    const expired = {
      accessToken: 'expired-access-redacted', refreshToken: 'refresh-redacted', expiresAt: '2026-07-25T00:00:00Z',
    }
    const refreshed = {
      accessToken: 'renewed-access-redacted', refreshToken: 'renewed-refresh-redacted', expiresAt: '2026-07-25T01:00:00Z',
    }
    let stored = expired
    const transport: TransportPort = {
      async request<T>(request: TransportRequest) {
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          return { statusCode: 200, data: { data: refreshed } as T }
        }
        if (request.headers.Authorization === `Bearer ${refreshed.accessToken}`) {
          return {
            statusCode: 200,
            data: { data: [], meta } as T,
            headers: { 'x-has-more': 'true', 'x-next-cursor': 'cursor-after-refresh' },
          }
        }
        return {
          statusCode: 401,
          data: { error: { code: 'AUTHENTICATION_REQUIRED', fieldErrors: [], retryable: false } } as T,
        }
      },
    }
    const api = new FitnessApiClient('http://127.0.0.1:8080', transport, {
      load: async () => stored,
      save: async (session) => { stored = session },
      clear: vi.fn(),
    })

    await expect(api.listRecommendations('PENDING')).resolves.toEqual({
      items: [], hasMore: true, nextCursor: 'cursor-after-refresh',
    })
  })

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
