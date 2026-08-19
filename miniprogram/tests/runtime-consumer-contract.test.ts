import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

import { FitnessApiClient, type TransportRequest } from '../src/infrastructure/api/client'

interface ConsumerFixture {
  readonly request: { readonly method: 'GET'; readonly path: string }
  readonly expectedStatus: number
  readonly consumerResponse: unknown
}

const fixture = JSON.parse(readFileSync(
  resolve(import.meta.dirname, '../../contract/consumer-samples/exercise-list.json'),
  'utf8',
)) as ConsumerFixture

describe('runtime consumer contract', () => {
  it('consumes the shared backend exercise-list sample through the real API client', async () => {
    let captured: TransportRequest | undefined
    const client = new FitnessApiClient('http://127.0.0.1:8080', {
      async request<T>(request: TransportRequest) {
        captured = request
        return { statusCode: fixture.expectedStatus, data: fixture.consumerResponse as T }
      },
    }, {
      async load() {
        return {
          accessToken: 'consumer-contract-token',
          refreshToken: 'consumer-contract-refresh',
          expiresAt: '2026-08-11T00:15:00Z',
        }
      },
      async save() {},
      async clear() {},
    })

    const exercises = await client.listExercises()

    expect(captured).toMatchObject({
      method: fixture.request.method,
      url: `http://127.0.0.1:8080${fixture.request.path}`,
      headers: { Authorization: 'Bearer consumer-contract-token' },
    })
    expect(exercises).toEqual([
      expect.objectContaining({
        code: 'BODYWEIGHT_SQUAT',
        difficulty: 'BEGINNER',
        image: expect.objectContaining({ fallbackRef: 'asset://exercise-placeholder' }),
      }),
    ])
  })
})
