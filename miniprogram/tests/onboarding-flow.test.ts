import { describe, expect, it, vi } from 'vitest'

import * as onboardingModule from '../src/application/onboarding'
import { ApplicationError } from '../src/application/errors'
import type {
  OnboardingPersistencePort,
  Session,
  StartupPorts,
} from '../src/application/onboarding'
import { FitnessApiClient } from '../src/infrastructure/api/client'
import {
  ONBOARDING_STEPS,
  advanceOnboarding,
  buildCandidateViewModel,
  createOnboardingState,
  createStartupUseCases,
  saveProfileAndGenerateCandidate,
  updateOnboardingDraft,
} from '../src/application/onboarding'

const validDraft = {
  adultConfirmed: true,
  safetyAccepted: true,
  goal: 'GENERAL_FITNESS' as const,
  experience: 'BEGINNER' as const,
  weeklyFrequency: 3,
  sessionMinutes: 45 as const,
  location: 'GYM' as const,
  equipment: [
    {
      clientEquipmentKey: '00000000-0000-4000-8000-000000000001',
      equipmentType: 'DUMBBELL',
      minIncrement: { value: 2.5, unit: 'KG' as const },
      availableLevels: [{ value: 5, unit: 'KG' as const }],
    },
  ],
  preferences: [],
}

describe('P0 onboarding flow', () => {
  it('provides backend-valid non-empty KG levels for every default gym equipment item', () => {
    expect(onboardingModule).toHaveProperty('DEFAULT_GYM_EQUIPMENT')
    const equipment = (onboardingModule as unknown as {
      DEFAULT_GYM_EQUIPMENT: typeof validDraft.equipment
    }).DEFAULT_GYM_EQUIPMENT
    expect(equipment.map((item) => item.equipmentType)).toEqual([
      'DUMBBELL',
      'BENCH',
      'CABLE',
      'MACHINE',
    ])
    for (const item of equipment) {
      expect(item.minIncrement.value).toBeGreaterThan(0)
      expect(item.minIncrement.unit).toBe('KG')
      expect(item.availableLevels.length).toBeGreaterThan(0)
      expect(item.availableLevels.every((level) => level.unit === 'KG' && level.value > 0)).toBe(true)
    }
  })

  it('keeps the three-minute path explicit and advances only when required fields are valid', () => {
    expect(ONBOARDING_STEPS).toEqual([
      'SAFETY',
      'GOAL_AND_EXPERIENCE',
      'SCHEDULE',
      'EQUIPMENT',
      'PREFERENCES',
      'REVIEW',
    ])

    const initial = createOnboardingState()
    const blocked = advanceOnboarding(initial)
    expect(blocked.step).toBe('SAFETY')
    expect(blocked.errors).toEqual([
      '仅支持已满 18 周岁的成年用户',
      '请确认本产品不提供医疗诊断或康复处方',
    ])

    let state = updateOnboardingDraft(initial, validDraft)
    for (let index = 1; index < ONBOARDING_STEPS.length; index += 1) {
      state = advanceOnboarding(state)
      expect(state.step).toBe(ONBOARDING_STEPS[index])
      expect(state.errors).toEqual([])
    }
  })

  it('blocks minors and rejects unsupported frequency, duration, and non-KG equipment', () => {
    let state = createOnboardingState()
    state = updateOnboardingDraft(state, {
      ...validDraft,
      adultConfirmed: false,
      weeklyFrequency: 1,
      sessionMinutes: 40 as never,
      equipment: [{
        ...validDraft.equipment[0],
        minIncrement: { value: 5, unit: 'LB' as never },
      }],
    })

    expect(advanceOnboarding(state).errors).toContain('仅支持已满 18 周岁的成年用户')

    state = { ...state, stepIndex: 2, step: 'SCHEDULE' }
    expect(advanceOnboarding(state).errors).toEqual([
      '每周训练频率必须为 2～6 天',
      '单次训练时长只能选择 30/45/60/75/90 分钟',
    ])

    state = { ...state, stepIndex: 3, step: 'EQUIPMENT' }
    expect(advanceOnboarding(state).errors).toContain('P0 仅支持 KG，不支持 LB 或隐式换算')
  })

  it('creates missing profile resources from expectedVersion 0 before generating a candidate', async () => {
    const calls: Array<[string, unknown]> = []
    const port: OnboardingPersistencePort = {
      getProfileVersion: vi.fn().mockResolvedValue(null),
      getEquipmentVersion: vi.fn().mockResolvedValue(null),
      getPreferencesVersion: vi.fn().mockResolvedValue(null),
      saveProfile: vi.fn(async (request) => {
        calls.push(['profile', request])
        return { version: 1 }
      }),
      saveEquipment: vi.fn(async (request) => {
        calls.push(['equipment', request])
        return { version: 1 }
      }),
      savePreferences: vi.fn(async (request) => {
        calls.push(['preferences', request])
        return { version: 1 }
      }),
      generateCandidate: vi.fn(async (request) => {
        calls.push(['candidate', request])
        return {
          status: 'NO_CANDIDATE' as const,
          validationIssues: [],
          lockedFieldOutcomes: {},
        }
      }),
    }

    await saveProfileAndGenerateCandidate(port, validDraft)

    expect(calls.map(([name]) => name)).toEqual([
      'profile',
      'equipment',
      'preferences',
      'candidate',
    ])
    expect(calls[0][1]).toMatchObject({ expectedVersion: 0, weeklyFrequency: 3 })
    expect(calls[1][1]).toMatchObject({ expectedVersion: 0 })
    expect(calls[2][1]).toMatchObject({ expectedVersion: 0 })
    expect(calls[3][1]).toEqual({ profileVersion: 1 })
  })

  it('keeps degraded AI candidates usable and exposes calibration or no-candidate actions', () => {
    const degraded = buildCandidateViewModel({
      status: 'CANDIDATE_READY',
      candidate: {
        candidateId: 'candidate-1',
        plan: {
          templateCode: 'full-body',
          name: '全身训练',
          days: [{
            code: 'day-a',
            name: '训练日 A',
            exercises: [{
              exerciseCode: 'goblet-squat',
              workSets: 3,
              repMin: 8,
              repMax: 12,
              restSeconds: 90,
              weightStatus: 'NEEDS_CALIBRATION',
            }],
          }],
          locks: {},
        },
        validationIssues: [],
        ruleReference: {
          ruleVersion: 'r1',
          templateVersion: 't1',
          contentVersion: 'c1',
        },
        lockedFieldOutcomes: {},
        explanationStatus: 'DEGRADED',
        explanation: '',
        expiresAt: '2026-07-25T00:00:00Z',
      },
      validationIssues: [],
      lockedFieldOutcomes: {},
    })

    expect(degraded.canContinue).toBe(true)
    expect(degraded.explanationMessage).toContain('解释暂不可用')
    expect(degraded.days[0].exercises[0].weightLabel).toContain('首次训练中校准')

    const noCandidate = buildCandidateViewModel({
      status: 'NO_CANDIDATE',
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'EQUIPMENT_UNAVAILABLE',
        fieldPath: 'equipment',
      }],
      lockedFieldOutcomes: {},
    })
    expect(noCandidate.canContinue).toBe(false)
    expect(noCandidate.action).toEqual({
      label: '返回调整器械与频率',
      route: 'ONBOARDING_EQUIPMENT',
    })
    expect(noCandidate.reason).toContain('EQUIPMENT_UNAVAILABLE')
  })
})

describe('P0 startup and WeChat session', () => {
  it('restores a session and navigates incomplete profiles through the application use case', async () => {
    const session: Session = {
      accessToken: 'access-redacted',
      refreshToken: 'refresh-redacted',
      expiresAt: '2026-07-25T00:00:00Z',
    }
    const navigate = vi.fn()
    const ports: StartupPorts = {
      sessionStore: {
        load: vi.fn().mockResolvedValue(session),
        save: vi.fn(),
        clear: vi.fn(),
      },
      wechatLogin: { getCode: vi.fn() },
      auth: { login: vi.fn() },
      profile: { exists: vi.fn().mockResolvedValue(false) },
      plan: { hasActivePlan: vi.fn() },
      navigation: { replace: navigate },
    }

    const destination = await createStartupUseCases(ports).start()

    expect(destination).toBe('ONBOARDING')
    expect(navigate).toHaveBeenCalledWith('ONBOARDING')
  })

  it('logs in with a platform code without persisting that temporary code', async () => {
    const session: Session = {
      accessToken: 'access-redacted',
      refreshToken: 'refresh-redacted',
      expiresAt: '2026-07-25T00:00:00Z',
    }
    const save = vi.fn()
    const getCode = vi.fn().mockResolvedValue('temporary-wechat-code')
    const login = vi.fn().mockResolvedValue(session)
    const ports: StartupPorts = {
      sessionStore: { load: vi.fn(), save, clear: vi.fn() },
      wechatLogin: { getCode },
      auth: { login },
      profile: { exists: vi.fn().mockResolvedValue(true) },
      plan: { hasActivePlan: vi.fn().mockResolvedValue(false) },
      navigation: { replace: vi.fn() },
    }

    await createStartupUseCases(ports).login()

    expect(login).toHaveBeenCalledWith('temporary-wechat-code')
    expect(save).toHaveBeenCalledWith(session)
    expect(save).not.toHaveBeenCalledWith(expect.stringContaining('temporary-wechat-code'))
  })

  it('clears expired sessions, returns to login, and never exposes the server message', async () => {
    const clear = vi.fn()
    const authenticationExpired = vi.fn()
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      {
        request: vi.fn().mockResolvedValue({
          statusCode: 401,
          data: {
            error: {
              code: 'AUTHENTICATION_REQUIRED',
              message: 'Bearer secret-token leaked by upstream',
              fieldErrors: [],
              details: {},
              retryable: false,
            },
            meta: { requestId: 'request-1' },
          },
        }),
      },
      {
        load: vi.fn().mockResolvedValue({
          accessToken: 'access-redacted',
          refreshToken: 'refresh-redacted',
          expiresAt: '2026-07-25T00:00:00Z',
        }),
        save: vi.fn(),
        clear,
      },
      authenticationExpired,
    )

    const error = await client.getProfileVersion().catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApplicationError)
    if (!(error instanceof ApplicationError)) throw new Error('expected ApplicationError')
    expect(error).toMatchObject({
      code: 'AUTHENTICATION_REQUIRED',
      message: '登录状态已失效，请重新登录',
    })
    expect(error.message).not.toContain('secret-token')
    expect(clear).toHaveBeenCalledOnce()
    expect(authenticationExpired).toHaveBeenCalledOnce()
  })
})
