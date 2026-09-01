import { describe, expect, it, vi } from 'vitest'

import * as onboardingModule from '../src/application/onboarding'
import { ApplicationError } from '../src/application/errors'
import type {
  OnboardingPersistencePort,
  Session,
  StartupPorts,
} from '../src/application/onboarding'
import {
  FitnessApiClient,
  type TransportPort,
  type TransportRequest,
  type TransportResponse,
} from '../src/infrastructure/api/client'
import { createFitnessApplication } from '../src/application/useCases'
import {
  ONBOARDING_STEPS,
  advanceOnboarding,
  buildCandidateViewModel,
  createOnboardingState,
  createStartupUseCases,
  goToOnboardingStep,
  saveProfileAndGenerateCandidate,
  updateOnboardingDraft,
} from '../src/application/onboarding'

const validDraft = {
  adultConfirmed: true,
  safetyAccepted: true,
  goal: 'GENERAL_FITNESS' as const,
  experience: 'BEGINNER' as const,
  trainingSplit: 'PUSH_PULL_LEGS' as const,
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

  it('keeps the onboarding path to four focused steps and advances only when required fields are valid', () => {
    expect(ONBOARDING_STEPS).toEqual([
      'SAFETY',
      'GOAL_AND_EXPERIENCE',
      'SCHEDULE',
      'LOCATION_AND_EQUIPMENT',
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

    state = { ...state, stepIndex: 3, step: 'LOCATION_AND_EQUIPMENT' }
    expect(advanceOnboarding(state).errors).toContain('P0 仅支持 KG，不支持 LB 或隐式换算')
  })

  it('returns from the final summary to the requested field without losing selections', () => {
    const final = {
      ...updateOnboardingDraft(createOnboardingState(), validDraft),
      stepIndex: 3,
      step: 'LOCATION_AND_EQUIPMENT' as const,
    }

    const schedule = goToOnboardingStep(final, 'SCHEDULE')

    expect(schedule).toMatchObject({
      stepIndex: 2,
      step: 'SCHEDULE',
      draft: validDraft,
      errors: [],
    })
    expect(schedule.draft).toBe(final.draft)
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
      listPlanPresets: vi.fn().mockResolvedValue([]),
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
    expect(calls[3][1]).toEqual({
      profileVersion: 1,
      trainingSplit: 'PUSH_PULL_LEGS',
      additionalRequirements: '',
      fallbackAllowed: true,
    })
  })

  it('keeps rule candidates usable, preserves their explanation, and exposes calibration or no-candidate actions', () => {
    const degraded = buildCandidateViewModel({
      status: 'CANDIDATE_READY',
      candidate: {
        candidateId: 'candidate-1',
        plan: {
          templateCode: 'full-body',
          trainingSplit: 'PUSH_PULL_LEGS',
          name: '全身训练',
          executionRules: ['动作按计划顺序完成。'],
          progressionRules: ['达到目标次数后再增加重量。'],
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
        validationIssues: [{
          severity: 'WARNING',
          reasonCode: 'RECOVERY_WINDOW_TOO_SHORT',
          fieldPath: '/days/day-a/primaryMuscles/CHEST',
        }],
        ruleReference: {
          ruleVersion: 'r1',
          templateVersion: 't1',
          contentVersion: 'c1',
        },
        lockedFieldOutcomes: {},
        explanationStatus: 'DEGRADED',
        explanation: '已按你的资料、训练目标和可用器械生成规则计划。',
        expiresAt: '2026-07-25T00:00:00Z',
      },
      validationIssues: [],
      lockedFieldOutcomes: {},
    })

    expect(degraded.canContinue).toBe(true)
    expect(degraded.generationLabel).toBe('规则生成计划 · 已通过安全校验')
    expect(degraded.name).toBe('全身训练')
    expect(degraded.trainingSplit).toBe('PUSH_PULL_LEGS')
    expect(degraded.executionRules).toEqual(['动作按计划顺序完成。'])
    expect(degraded.progressionRules).toEqual(['达到目标次数后再增加重量。'])
    expect(degraded.explanationMessage).toBe('已按你的资料、训练目标和可用器械生成规则计划。')
    expect(degraded.notices).toEqual([
      expect.stringContaining('充分恢复'),
    ])
    expect(degraded.days[0].exercises[0].weightLabel).toContain('自动设置起始重量')

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
      label: '返回调整训练条件',
      route: 'ONBOARDING_EQUIPMENT',
    })
    expect(noCandidate.reason).toBe('当前场地、器械和训练频率暂无可执行的安全计划，请调整训练条件后重试。')

    const insufficientRecovery = buildCandidateViewModel({
      status: 'NO_CANDIDATE',
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'RECOVERY_WINDOW_TOO_SHORT',
        fieldPath: 'weeklyFrequency',
      }],
      lockedFieldOutcomes: {},
    })
    expect(insufficientRecovery.action).toEqual({
      label: '返回调整训练频率',
      route: 'ONBOARDING_SCHEDULE',
    })
    expect(insufficientRecovery.reason).toContain('相同主肌群恢复不足')

    const insufficientExercises = buildCandidateViewModel({
      status: 'NO_CANDIDATE',
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'INSUFFICIENT_ELIGIBLE_EXERCISES',
        fieldPath: 'exercises',
      }],
      lockedFieldOutcomes: {},
    })
    expect(insufficientExercises.reason).toBe('当前可用器械对应的安全动作不足以组成完整训练，请补充器械或调整训练条件后重试。')

    const presetProfileMismatch = buildCandidateViewModel({
      status: 'NO_CANDIDATE',
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'PRESET_PROFILE_MISMATCH',
        fieldPath: '/presetCode',
      }],
      lockedFieldOutcomes: {},
    })
    expect(presetProfileMismatch.reason).toBe(
      '该系统预设与当前档案不匹配，请按预设标注调整训练经验、目标、每周训练天数、场地和单次时长后重试。',
    )
  })

  it('resumes a recovery NO_CANDIDATE at schedule without dropping the submitted draft', async () => {
    const port: OnboardingPersistencePort = {
      getProfileVersion: vi.fn().mockResolvedValue(0),
      getEquipmentVersion: vi.fn().mockResolvedValue(0),
      getPreferencesVersion: vi.fn().mockResolvedValue(0),
      saveProfile: vi.fn().mockResolvedValue({ version: 1 }),
      saveEquipment: vi.fn().mockResolvedValue({ version: 1 }),
      savePreferences: vi.fn().mockResolvedValue({ version: 1 }),
      listPlanPresets: vi.fn().mockResolvedValue([]),
      generateCandidate: vi.fn().mockResolvedValue({
        status: 'NO_CANDIDATE',
        validationIssues: [{
          severity: 'ERROR',
          reasonCode: 'RECOVERY_WINDOW_TOO_SHORT',
          fieldPath: 'weeklyFrequency',
        }],
        lockedFieldOutcomes: {},
      }),
    }
    const application = createFitnessApplication(port, {
      validatePlan: vi.fn(),
      createInitialPlan: vi.fn(),
      getActivePlan: vi.fn(),
      commitCandidate: vi.fn(),
      createPlanVersion: vi.fn(),
      previewRebalance: vi.fn(),
    })

    const candidate = await application.completeOnboarding(validDraft)
    const resumed = application.resumeOnboarding(candidate.action!.route)
    const remountedPage = application.resumeOnboarding()

    expect(resumed).toMatchObject({
      stepIndex: 2,
      step: 'SCHEDULE',
      draft: validDraft,
      errors: [],
    })
    expect(resumed.draft).not.toBe(validDraft)
    expect(remountedPage).toMatchObject({
      stepIndex: 2,
      step: 'SCHEDULE',
      draft: validDraft,
    })
    expect(application.resumeOnboarding()).toMatchObject({
      stepIndex: 3,
      step: 'LOCATION_AND_EQUIPMENT',
    })

    application.clearUserState()

    expect(application.getCandidate()).toBeNull()
    expect(application.getActivePlan()).toBeNull()
    expect(application.resumeOnboarding()).toMatchObject({
      stepIndex: 0,
      step: 'SAFETY',
      draft: { adultConfirmed: false, safetyAccepted: false },
    })
  })
})

describe('P0 startup and WeChat session', () => {
  it('allows HTTP only for loopback API hosts', () => {
    const transport = { request: vi.fn() }
    const sessionStore = { load: vi.fn(), save: vi.fn(), clear: vi.fn() }

    expect(() => new FitnessApiClient(
      'http://api.example.com',
      transport,
      sessionStore,
      vi.fn(),
    )).toThrow('HTTPS')
    expect(() => new FitnessApiClient(
      'http://localhost:8080',
      transport,
      sessionStore,
      vi.fn(),
    )).not.toThrow()
    expect(() => new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      sessionStore,
      vi.fn(),
    )).not.toThrow()
    expect(() => new FitnessApiClient(
      'http://[::1]:8080',
      transport,
      sessionStore,
      vi.fn(),
    )).not.toThrow()
    expect(() => new FitnessApiClient(
      'https://api.example.com',
      transport,
      sessionStore,
      vi.fn(),
    )).not.toThrow()
    expect(() => new FitnessApiClient(
      'ftp://api.example.com',
      transport,
      sessionStore,
      vi.fn(),
    )).toThrow('http or https')
    expect(() => new FitnessApiClient(
      'https://user:secret@api.example.com',
      transport,
      sessionStore,
      vi.fn(),
    )).toThrow('http or https')
    expect(() => new FitnessApiClient(
      'https://api.example.com:70000',
      transport,
      sessionStore,
      vi.fn(),
    )).toThrow('http or https')
  })

  it('constructs the API client when the WeChat runtime has no browser URL global', () => {
    const transport = { request: vi.fn() }
    const sessionStore = { load: vi.fn(), save: vi.fn(), clear: vi.fn() }
    const urlDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'URL')
    Object.defineProperty(globalThis, 'URL', {
      configurable: true,
      writable: true,
      value: undefined,
    })

    try {
      expect(() => new FitnessApiClient(
        'https://api.example.com',
        transport,
        sessionStore,
        vi.fn(),
      )).not.toThrow()
    } finally {
      if (urlDescriptor) Object.defineProperty(globalThis, 'URL', urlDescriptor)
      else Reflect.deleteProperty(globalThis, 'URL')
    }
  })

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
      workout: { hasActive: vi.fn().mockResolvedValue(false) },
      profile: { exists: vi.fn().mockResolvedValue(false) },
      plan: { hasActivePlan: vi.fn() },
      navigation: { replace: navigate },
    }

    const destination = await createStartupUseCases(ports).start()

    expect(destination).toBe('ONBOARDING')
    expect(navigate).toHaveBeenCalledWith('ONBOARDING')
  })

  it('never leaves an authenticated profile on the obsolete home surface', async () => {
    const navigation = { replace: vi.fn() }
    const ports: StartupPorts = {
      sessionStore: {
        load: vi.fn().mockResolvedValue({
          accessToken: 'access-redacted',
          refreshToken: 'refresh-redacted',
          expiresAt: '2026-07-25T00:00:00Z',
        }),
        save: vi.fn(),
        clear: vi.fn(),
      },
      wechatLogin: { getCode: vi.fn() },
      auth: { login: vi.fn() },
      workout: { hasActive: vi.fn().mockResolvedValue(false) },
      profile: { exists: vi.fn().mockResolvedValue(true) },
      plan: { hasActivePlan: vi.fn().mockResolvedValue(false) },
      navigation,
    }

    await expect(createStartupUseCases(ports).start()).resolves.toBe('ONBOARDING')
    expect(navigation.replace).toHaveBeenCalledWith('ONBOARDING')

    vi.mocked(ports.plan.hasActivePlan).mockResolvedValue(true)
    await expect(createStartupUseCases(ports).start()).resolves.toBe('PLAN')
    expect(navigation.replace).toHaveBeenLastCalledWith('PLAN')
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
      workout: { hasActive: vi.fn().mockResolvedValue(false) },
      profile: { exists: vi.fn().mockResolvedValue(true) },
      plan: { hasActivePlan: vi.fn().mockResolvedValue(false) },
      navigation: { replace: vi.fn() },
    }

    await createStartupUseCases(ports).login()

    expect(login).toHaveBeenCalledWith('temporary-wechat-code')
    expect(save).toHaveBeenCalledWith(session)
    expect(save).not.toHaveBeenCalledWith(expect.stringContaining('temporary-wechat-code'))
  })

  it('keeps local data locked until remote login succeeds and rolls back failed login', async () => {
    const activate = vi.fn()
    const purge = vi.fn().mockResolvedValue(undefined)
    const login = vi.fn().mockRejectedValue(new Error('remote login failed'))
    const ports: StartupPorts = {
      sessionStore: { load: vi.fn(), save: vi.fn(), clear: vi.fn() },
      wechatLogin: { getCode: vi.fn().mockResolvedValue('temporary-wechat-code') },
      auth: { login },
      workout: { hasActive: vi.fn() },
      profile: { exists: vi.fn() },
      plan: { hasActivePlan: vi.fn() },
      navigation: { replace: vi.fn() },
      localUserData: { activate, purge },
    }

    await expect(createStartupUseCases(ports).login()).rejects.toThrow('remote login failed')

    expect(login).toHaveBeenCalledOnce()
    expect(activate).not.toHaveBeenCalled()
    expect(purge).toHaveBeenCalledWith('LOGIN_ROLLBACK')
    expect(ports.sessionStore.save).not.toHaveBeenCalled()
  })

  it('does not issue a remote session while local user storage remains blocked', async () => {
    const login = vi.fn()
    const blocked = new Error('local storage blocked')
    const ports: StartupPorts = {
      sessionStore: {
        load: vi.fn().mockRejectedValue(blocked),
        save: vi.fn(),
        clear: vi.fn(),
      },
      wechatLogin: { getCode: vi.fn().mockResolvedValue('temporary-wechat-code') },
      auth: { login },
      workout: { hasActive: vi.fn() },
      profile: { exists: vi.fn() },
      plan: { hasActivePlan: vi.fn() },
      navigation: { replace: vi.fn() },
      localUserData: {
        activate: vi.fn(),
        purge: vi.fn().mockRejectedValue(blocked),
      },
    }

    await expect(createStartupUseCases(ports).login()).rejects.toBe(blocked)
    expect(login).not.toHaveBeenCalled()
    expect(ports.localUserData?.activate).not.toHaveBeenCalled()
  })

  it('resumes an active local workout before requesting remote profile or plan state', async () => {
    const profileExists = vi.fn()
    const hasActivePlan = vi.fn()
    const navigate = vi.fn()
    const ports: StartupPorts = {
      sessionStore: {
        load: vi.fn().mockResolvedValue({
          accessToken: 'access-redacted',
          refreshToken: 'refresh-redacted',
          expiresAt: '2026-07-25T00:00:00Z',
        }),
        save: vi.fn(),
        clear: vi.fn(),
      },
      wechatLogin: { getCode: vi.fn() },
      auth: { login: vi.fn() },
      workout: { hasActive: vi.fn().mockResolvedValue(true) },
      profile: { exists: profileExists },
      plan: { hasActivePlan },
      navigation: { replace: navigate },
    }

    await expect(createStartupUseCases(ports).start()).resolves.toBe('WORKOUT_SESSION')
    expect(navigate).toHaveBeenCalledWith('WORKOUT_SESSION')
    expect(profileExists).not.toHaveBeenCalled()
    expect(hasActivePlan).not.toHaveBeenCalled()
  })

  it('clears expired sessions, returns to login, and never exposes the server message', async () => {
    const clear = vi.fn()
    const authenticationExpired = vi.fn(async () => clear())
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

  it('refreshes an expired access token once and retries the original request', async () => {
    const currentSession: Session = {
      accessToken: 'expired-access-redacted',
      refreshToken: 'refresh-redacted',
      expiresAt: '2026-07-25T00:00:00Z',
    }
    const refreshedSession: Session = {
      accessToken: 'renewed-access-redacted',
      refreshToken: 'renewed-refresh-redacted',
      expiresAt: '2026-07-25T01:00:00Z',
    }
    const save = vi.fn(async (session: Session) => {
      Object.assign(currentSession, session)
    })
    const clear = vi.fn()
    const requests: Array<{ url: string; authorization?: string; body?: unknown }> = []
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        requests.push({
          url: request.url,
          authorization: request.headers.Authorization,
          body: request.body,
        })
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          return { statusCode: 200, data: { data: refreshedSession } as T }
        }
        if (request.headers.Authorization === `Bearer ${refreshedSession.accessToken}`) {
          return { statusCode: 200, data: { data: { version: 4 } } as T }
        }
        return {
          statusCode: 401,
          data: { error: { code: 'AUTHENTICATION_REQUIRED', fieldErrors: [], retryable: false } } as T,
        }
      },
    }
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      { load: async () => currentSession, save, clear },
      vi.fn(),
    )

    await expect(client.getProfileVersion()).resolves.toBe(4)
    expect(requests).toHaveLength(3)
    expect(requests[1]).toMatchObject({
      url: 'http://127.0.0.1:8080/api/v1/auth/refresh',
      authorization: undefined,
      body: { refreshToken: 'refresh-redacted' },
    })
    expect(requests[2].authorization).toBe('Bearer renewed-access-redacted')
    expect(save).toHaveBeenCalledWith(refreshedSession)
    expect(clear).not.toHaveBeenCalled()
  })

  it('reuses a session refreshed by another request when a late 401 arrives', async () => {
    const expiredSession: Session = {
      accessToken: 'expired-access-redacted',
      refreshToken: 'expired-refresh-redacted',
      expiresAt: '2026-07-25T00:00:00Z',
    }
    const refreshedSession: Session = {
      accessToken: 'renewed-access-redacted',
      refreshToken: 'renewed-refresh-redacted',
      expiresAt: '2026-07-25T01:00:00Z',
    }
    let storedSession = expiredSession
    let expiredRequests = 0
    let refreshRequests = 0
    let releaseLateUnauthorized!: () => void
    const lateUnauthorized = new Promise<void>((resolve) => {
      releaseLateUnauthorized = resolve
    })
    const clear = vi.fn()
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          refreshRequests += 1
          return refreshRequests === 1
            ? { statusCode: 200, data: { data: refreshedSession } as T }
            : {
                statusCode: 401,
                data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
              }
        }
        if (request.headers.Authorization === `Bearer ${refreshedSession.accessToken}`) {
          return { statusCode: 200, data: { data: { version: 4 } } as T }
        }
        expiredRequests += 1
        if (expiredRequests === 2) await lateUnauthorized
        return {
          statusCode: 401,
          data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
        }
      },
    }
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      {
        load: async () => storedSession,
        save: async (session) => { storedSession = session },
        clear,
      },
      vi.fn(),
    )

    const first = client.getProfileVersion()
    const late = client.getEquipmentVersion()
    await expect(first).resolves.toBe(4)
    releaseLateUnauthorized()
    await expect(late).resolves.toBe(4)

    expect(refreshRequests).toBe(1)
    expect(storedSession).toEqual(refreshedSession)
    expect(clear).not.toHaveBeenCalled()
  })

  it('never replays an old request with an unrelated session loaded from storage', async () => {
    const expiredSession: Session = {
      accessToken: 'expired-access-redacted',
      refreshToken: 'expired-refresh-redacted',
      expiresAt: '2026-07-25T00:00:00Z',
    }
    const unrelatedSession: Session = {
      accessToken: 'unrelated-access-redacted',
      refreshToken: 'unrelated-refresh-redacted',
      expiresAt: '2026-07-25T01:00:00Z',
    }
    let storedSession = expiredSession
    const authorizations: Array<string | undefined> = []
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      {
        async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
          authorizations.push(request.headers.Authorization)
          if (request.url.endsWith('/api/v1/auth/refresh')) {
            return {
              statusCode: 401,
              data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
            }
          }
          storedSession = unrelatedSession
          return request.headers.Authorization === `Bearer ${unrelatedSession.accessToken}`
            ? { statusCode: 200, data: { data: { version: 9 } } as T }
            : {
                statusCode: 401,
                data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
              }
        },
      },
      {
        load: async () => storedSession,
        save: vi.fn(),
        clear: vi.fn(),
      },
      vi.fn(),
    )

    await expect(client.getProfileVersion()).rejects.toMatchObject({
      code: 'AUTHENTICATION_REQUIRED',
    })
    expect(authorizations).not.toContain('Bearer unrelated-access-redacted')
  })
})
