import { describe, expect, it, vi } from 'vitest'

import {
  FitnessApiClient,
  type TransportPort,
  type TransportRequest,
} from '../src/infrastructure/api/client'

const meta = { requestId: 'request-1', serverTime: '2026-08-04T12:00:00Z' }

function client(respond: (request: TransportRequest) => unknown): FitnessApiClient {
  const transport: TransportPort = {
    async request<T>(request: TransportRequest) {
      return { statusCode: 200, data: respond(request) as T }
    },
  }
  return new FitnessApiClient(
    'http://127.0.0.1:8080',
    transport,
    {
      load: vi.fn().mockResolvedValue({
        accessToken: 'access-redacted',
        refreshToken: 'refresh-redacted',
        expiresAt: '2026-08-05T00:00:00Z',
      }),
      save: vi.fn(),
      clear: vi.fn(),
    },
  )
}

describe('AI-primary plan API adapter', () => {
  it('fetches the server-owned generation context by saved profile version', async () => {
    const api = client((request) => {
      expect(request).toMatchObject({
        method: 'GET',
        url: 'http://127.0.0.1:8080/api/v1/plans/generation-context?profileVersion=3',
      })
      return {
        data: {
          profile: {
            experience: 'INTERMEDIATE',
            goal: 'HYPERTROPHY',
            weeklyFrequency: 2,
            sessionMinutes: 45,
            location: 'GYM',
            profileVersion: 3,
          },
          exercises: [],
          constraints: {
            minimumSessionsPerWeek: 2,
            maximumSessionsPerWeek: 6,
            maximumExercisesPerSession: 8,
            minimumWorkSets: 2,
            maximumWorkSets: 4,
            minimumReps: 5,
            maximumReps: 15,
            minimumRestSeconds: 45,
            maximumRestSeconds: 240,
            secondsPerWorkSet: 45,
            secondsPerExerciseTransition: 75,
            maximumMovementPatternOccurrencesPerSession: 2,
            maximumWorkSetsPerPrimaryMusclePerSession: 12,
            minimumRecoveryHoursBetweenPrimaryMuscleSessions: 48,
          },
          ruleReference: {
            ruleVersion: '1.2.0',
            templateVersion: '1.0.0',
            contentVersion: '1.0.0',
          },
        },
        meta,
      }
    })

    await expect(api.getPlanGenerationContext(3))
      .resolves.toMatchObject({ profile: { profileVersion: 3, sessionMinutes: 45 } })
  })

  it('submits the AI proposal with fallback disabled during validation', async () => {
    const proposal = {
      name: '个性化计划',
      days: [{
        code: 'DAY_1',
        name: '全身训练',
        exercises: [{
          exerciseCode: 'GOBLET_SQUAT',
          workSets: 3,
          repMin: 8,
          repMax: 12,
          restSeconds: 90,
        }],
      }, {
        code: 'DAY_2',
        name: '全身训练',
        exercises: [{
          exerciseCode: 'SEATED_CABLE_ROW',
          workSets: 3,
          repMin: 8,
          repMax: 12,
          restSeconds: 90,
        }],
      }],
    }
    const api = client((request) => {
      expect(request).toMatchObject({
        method: 'POST',
        url: 'http://127.0.0.1:8080/api/v1/plans/candidates',
        body: {
          profileVersion: 3,
          additionalRequirements: '胸背优先',
          aiProposal: proposal,
          fallbackAllowed: false,
        },
      })
      return {
        data: {
          status: 'NO_CANDIDATE',
          validationIssues: [],
          lockedFieldOutcomes: {},
        },
        meta,
      }
    })

    await api.generateCandidate({
      profileVersion: 3,
      additionalRequirements: '胸背优先',
      aiProposal: proposal,
      fallbackAllowed: false,
    })
  })
})
