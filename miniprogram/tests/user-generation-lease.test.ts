import { describe, expect, it, vi } from 'vitest'

const taro = vi.hoisted(() => ({
  getStorageInfo: vi.fn(),
  removeStorage: vi.fn(),
}))

vi.mock('@tarojs/taro', () => ({ default: taro }))

import type { Session } from '../src/application/startup'
import type { WorkoutDraft, WorkoutDraftStore } from '../src/application/ports/WorkoutDraftStore'
import { WorkoutFlowService } from '../src/application/use-cases/WorkoutFlowService'
import { createStartupUseCases } from '../src/application/startup'
import { createAccountLifecycleUseCases } from '../src/application/localPrivacyLifecycle'
import {
  FitnessApiClient,
  type SessionAccessPort,
  type TransportPort,
  type TransportRequest,
  type TransportResponse,
} from '../src/infrastructure/api/client'
import {
  UserGenerationInvalidatedError,
  UserScopedStateBlockedError,
  UserScopedStateClearError,
  createGenerationBoundUserScopedDataLifecycle,
  createUserGenerationLease,
  createUserScopedStateRegistry,
} from '../src/platform/weapp/sharedPlatformKernel'
import {
  UserScopedStorageBlockedError,
  createWeappUserScopedDataLifecycle,
  type WeappUserScopedDataLifecycle,
} from '../src/platform/weapp/WechatUserScopedDataLifecycle'
import { createGenerationFencedAiTextProvider } from '../src/platform/weapp/featureRoots/aiRuntime'
import { SessionRefreshCoordinator } from '../src/infrastructure/api/sessionRefreshCoordinator'
import { createVerifiedPrivacyUseCases } from '../src/application/privacy'
import { createProgressGenerationOperations } from '../src/platform/weapp/featureRoots/progressGenerationOperations'
import { createGenerationBoundReauthenticationProof } from '../src/platform/weapp/featureRoots/accountCompositionRoot'

const firstSession: Session = {
  accessToken: 'first-access-redacted',
  refreshToken: 'first-refresh-redacted',
  expiresAt: '2026-08-28T09:00:00Z',
}

const secondSession: Session = {
  accessToken: 'second-access-redacted',
  refreshToken: 'second-refresh-redacted',
  expiresAt: '2026-08-28T10:00:00Z',
}

describe('user generation lease', () => {
  it('invalidates the old generation before clearing observers with a sanitized non-retryable error', () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const oldGeneration = lease.capture()
    let observerError: unknown
    registry.register(() => {
      try {
        lease.assertCurrent(oldGeneration)
      } catch (error) {
        observerError = error
      }
    })

    registry.clearAll()

    expect(observerError).toBeInstanceOf(UserGenerationInvalidatedError)
    expect(observerError).toMatchObject({
      code: 'AUTHENTICATION_REQUIRED',
      retryable: false,
    })
    expect(String((observerError as Error).message)).not.toMatch(/access|refresh|token|secret/i)
    expect(() => lease.assertCurrent(lease.capture())).not.toThrow()
  })

  it('requires both memory and persisted-data cleanup before reporting a verified clear', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const localData = createGenerationBoundUserScopedDataLifecycle(
      createSerialUserDataLifecycle(),
      lease,
      registry,
    )

    expect(localData.isClearVerified()).toBe(false)
    registry.clearAll()
    await localData.purge('AUTHENTICATION_EXPIRED')
    expect(localData.isClearVerified()).toBe(false)

    registry.completeClear()
    expect(localData.isClearVerified()).toBe(true)
    localData.activate()
    expect(localData.isClearVerified()).toBe(false)
  })

  it('rejects an old pending authenticated workout response without writing a draft, then allows the new generation', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const firstResponse = deferred<TransportResponse<unknown>>()
    let currentSession = firstSession
    let workoutRequestCount = 0
    const request: TransportPort['request'] = async <T>(
      request: TransportRequest,
    ): Promise<TransportResponse<T>> => {
        if (!request.url.endsWith('/api/v1/workout-sessions')) {
          throw new Error('unexpected request')
        }
        workoutRequestCount += 1
        if (workoutRequestCount === 1) {
          return firstResponse.promise as Promise<TransportResponse<T>>
        }
        return {
          statusCode: 201,
          data: { data: serverSession('new-server-session') } as T,
        }
    }
    const transport: TransportPort = {
      request,
    }
    const sessions: SessionAccessPort = {
      load: vi.fn(async () => currentSession),
      save: vi.fn(async (session) => { currentSession = session }),
      clear: vi.fn(),
    }
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      sessions,
      undefined,
      undefined,
      lease,
    )
    const drafts = emptyDraftStore()
    const workouts = new WorkoutFlowService(
      drafts,
      fixedClock(),
      api,
      api,
      api,
      api,
      lease,
    )

    const oldStart = workouts.startOrResume(startRequest('old-client-session'))
    await vi.waitFor(() => expect(workoutRequestCount).toBe(1))

    registry.clearAll()
    currentSession = secondSession
    firstResponse.resolve({
      statusCode: 201,
      data: { data: serverSession('old-server-session') },
    })

    await expect(oldStart).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(drafts.save).not.toHaveBeenCalled()

    await expect(workouts.startOrResume(startRequest('new-client-session')))
      .resolves.toMatchObject({ kind: 'STARTED' })
    expect(drafts.save).toHaveBeenCalledOnce()
  })

  it('prioritizes invalidation over a stale refresh failure and never saves or retries it', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const refreshResponse = deferred<TransportResponse<unknown>>()
    let refreshStarted = false
    let retried = false
    const save = vi.fn()
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          refreshStarted = true
          return refreshResponse.promise as Promise<TransportResponse<T>>
        }
        if (request.headers.Authorization === `Bearer ${firstSession.accessToken}`) {
          return {
            statusCode: 401,
            data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
          }
        }
        retried = true
        return { statusCode: 200, data: { data: { version: 7 } } as T }
      },
    }
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      { load: async () => firstSession, save, clear: vi.fn() },
      undefined,
      undefined,
      lease,
    )

    const pending = api.getProfileVersion()
    await vi.waitFor(() => expect(refreshStarted).toBe(true))
    registry.clearAll()
    refreshResponse.reject(new Error('secret stale refresh transport detail'))

    const error = await pending.catch((reason: unknown) => reason)
    expect(error).toBeInstanceOf(UserGenerationInvalidatedError)
    expect(error).toMatchObject({ retryable: false })
    expect(String((error as Error).message)).not.toContain('secret stale refresh')
    expect(save).not.toHaveBeenCalled()
    expect(retried).toBe(false)
  })

  it('does not let a stale null-session read clear a newer account generation', async () => {
    const lease = createUserGenerationLease()
    const session = deferred<Session | null>()
    const authenticationFailure = vi.fn()
    const transport = { request: vi.fn() }
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      { load: () => session.promise, save: vi.fn(), clear: vi.fn() },
      authenticationFailure,
      undefined,
      lease,
    )

    const staleRequest = api.getProfileVersion()
    lease.begin()
    session.resolve(null)

    await expect(staleRequest).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(authenticationFailure).not.toHaveBeenCalled()
    expect(transport.request).not.toHaveBeenCalled()
  })

  it('checks a queued local write again at its actual execution point before a purge and new activation', async () => {
    const lease = createUserGenerationLease()
    const localData = createGenerationBoundUserScopedDataLifecycle(
      createSerialUserDataLifecycle(),
      lease,
    )
    const blockerGate = deferred<void>()
    const blockerEntered = deferred<void>()
    const blocker = localData.runUserOperation(async () => {
      blockerEntered.resolve()
      await blockerGate.promise
    })
    const blockerResult = expect(blocker).rejects.toBeInstanceOf(
      UserGenerationInvalidatedError,
    )
    await blockerEntered.promise

    const storageWrite = vi.fn(async () => undefined)
    const oldWrite = localData.runUserOperation(storageWrite)
    const oldWriteResult = expect(oldWrite).rejects.toBeInstanceOf(
      UserGenerationInvalidatedError,
    )
    const purge = localData.purge('ACCOUNT_SWITCH')
    blockerGate.resolve()

    await blockerResult
    await oldWriteResult
    await purge
    expect(storageWrite).not.toHaveBeenCalled()

    localData.activate()
    await expect(localData.runUserOperation(storageWrite)).resolves.toBeUndefined()
    expect(storageWrite).toHaveBeenCalledOnce()
  })

  it.each([
    ['ACCESS_REVOKED', { error: { code: 'ACCESS_REVOKED', retryable: false } }],
    ['ordinary 401', { error: { code: 'AUTHENTICATION_REQUIRED', retryable: false } }],
  ])('does not let a stale %s response clear or purge the new account', async (_name, payload) => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const response = deferred<TransportResponse<unknown>>()
    let requestStarted = false
    let currentSession = firstSession
    const clear = vi.fn(async () => undefined)
    const purge = vi.fn(async () => undefined)
    const navigate = vi.fn(async () => undefined)
    const onAuthenticationFailure = vi.fn(async () => {
      await purge()
      await navigate()
    })
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      {
        async request<T>(): Promise<TransportResponse<T>> {
          requestStarted = true
          return response.promise as Promise<TransportResponse<T>>
        },
      },
      {
        load: async () => currentSession,
        save: vi.fn(),
        clear,
      },
      onAuthenticationFailure,
      undefined,
      lease,
    )

    const oldRequest = api.getProfileVersion()
    await vi.waitFor(() => expect(requestStarted).toBe(true))
    registry.clearAll()
    lease.begin()
    currentSession = secondSession
    response.resolve({ statusCode: 401, data: payload })

    await expect(oldRequest).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(clear).not.toHaveBeenCalled()
    expect(onAuthenticationFailure).not.toHaveBeenCalled()
    expect(purge).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
    expect(currentSession).toBe(secondSession)
  })

  it('does not let an old logout completion invalidate a new-account refresh', async () => {
    const lease = createUserGenerationLease()
    const logoutResponse = deferred<TransportResponse<unknown>>()
    const refreshResponse = deferred<TransportResponse<unknown>>()
    const coordinator = new SessionRefreshCoordinator()
    const refreshedSession: Session = {
      accessToken: 'new-refreshed-access-redacted',
      refreshToken: 'new-refreshed-refresh-redacted',
      expiresAt: '2026-08-28T11:00:00Z',
    }
    let currentSession = firstSession
    let refreshStarted = false
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        if (request.url.endsWith('/api/v1/auth/logout')) {
          return logoutResponse.promise as Promise<TransportResponse<T>>
        }
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          refreshStarted = true
          return refreshResponse.promise as Promise<TransportResponse<T>>
        }
        if (request.headers.Authorization === `Bearer ${refreshedSession.accessToken}`) {
          return { statusCode: 200, data: { data: { version: 9 } } as T }
        }
        return {
          statusCode: 401,
          data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
        }
      },
    }
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      {
        load: async () => currentSession,
        save: async (session) => { currentSession = session },
        clear: vi.fn(),
      },
      undefined,
      coordinator,
      lease,
    )

    const oldLogout = api.logout()
    await vi.waitFor(() => expect(currentSession).toBe(firstSession))
    lease.invalidate()
    lease.begin()
    currentSession = secondSession
    const newRequest = api.getProfileVersion()
    await vi.waitFor(() => expect(refreshStarted).toBe(true))

    logoutResponse.resolve({ statusCode: 200, data: { data: {} } })
    await expect(oldLogout).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    refreshResponse.resolve({ statusCode: 200, data: { data: refreshedSession } })

    await expect(newRequest).resolves.toBe(9)
    expect(currentSession).toBe(refreshedSession)
  })

  it('rejects an old login response and prevents its session save while a new login succeeds', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const firstLogin = deferred<Session>()
    const save = vi.fn(async () => undefined)
    const purge = vi.fn(async () => undefined)
    const codes = ['first-code', 'second-code']
    const login = createStartupUseCases({
      sessionStore: { load: vi.fn(), save, clear: vi.fn() },
      wechatLogin: { getCode: vi.fn(async () => codes.shift() ?? '') },
      auth: {
        login: vi.fn((code: string) => code === 'first-code'
          ? firstLogin.promise
          : Promise.resolve(secondSession)),
      },
      workout: { hasActive: vi.fn(async () => false) },
      profile: { exists: vi.fn(async () => true) },
      plan: { hasActivePlan: vi.fn(async () => true) },
      navigation: { replace: vi.fn() },
      localUserData: { activate: vi.fn(), purge, isClearVerified: () => true },
      userGeneration: lease,
    }).login

    const oldLogin = login()
    await vi.waitFor(() => expect(codes).toEqual(['second-code']))
    registry.clearAll()

    await expect(login()).resolves.toBe('PLAN')
    firstLogin.resolve(firstSession)

    await expect(oldLogin).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(save).toHaveBeenCalledTimes(1)
    expect(save).toHaveBeenCalledWith(secondSession)
    expect(purge).not.toHaveBeenCalled()
  })

  it('clears unverified null-session account state before acquiring and saving a new session', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const order: string[] = []
    let orphanedMemory = true
    let orphanedStorage = true
    registry.register(() => {
      orphanedMemory = false
      order.push('clear-memory')
    })
    const localData = createGenerationBoundUserScopedDataLifecycle(
      createSerialUserDataLifecycle(() => {
        orphanedStorage = false
        order.push('purge-storage')
      }),
      lease,
      registry,
    )
    let storedSession: Session | null = null
    const startup = createStartupUseCases({
      sessionStore: {
        load: () => localData.runClearedSessionRead(async () => storedSession),
        save: (session) => localData.runUserOperation(async () => { storedSession = session }),
        clear: () => localData.runUserOperation(async () => { storedSession = null }),
      },
      wechatLogin: {
        getCode: vi.fn(async () => {
          expect(orphanedMemory).toBe(false)
          expect(orphanedStorage).toBe(false)
          order.push('get-code')
          return 'new-code-redacted'
        }),
      },
      auth: {
        login: vi.fn(async () => {
          order.push('remote-login')
          return secondSession
        }),
      },
      workout: { hasActive: vi.fn(async () => false) },
      profile: { exists: vi.fn(async () => true) },
      plan: { hasActivePlan: vi.fn(async () => true) },
      navigation: { replace: vi.fn(async () => undefined) },
      localUserData: localData,
      userGeneration: lease,
      clearUserState: () => registry.clearAll(),
      completeUserStateClear: () => registry.completeClear(),
    })

    await expect(startup.login()).resolves.toBe('PLAN')

    expect(storedSession).toBe(secondSession)
    expect(order).toEqual(['clear-memory', 'purge-storage', 'get-code', 'remote-login'])
  })

  it('does not let an old failed login roll back a newer pending login generation', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const firstLogin = deferred<Session>()
    const secondLogin = deferred<Session>()
    const save = vi.fn(async () => undefined)
    const purge = vi.fn(async () => undefined)
    const activate = vi.fn()
    const authLogin = vi.fn((code: string) => code === 'first-code'
      ? firstLogin.promise
      : secondLogin.promise)
    const startup = createStartupUseCases({
      sessionStore: { load: vi.fn(async () => null), save, clear: vi.fn() },
      wechatLogin: {
        getCode: vi.fn()
          .mockResolvedValueOnce('first-code')
          .mockResolvedValueOnce('second-code'),
      },
      auth: { login: authLogin },
      workout: { hasActive: vi.fn(async () => false) },
      profile: { exists: vi.fn(async () => true) },
      plan: { hasActivePlan: vi.fn(async () => true) },
      navigation: { replace: vi.fn(async () => undefined) },
      localUserData: { activate, purge, isClearVerified: () => true },
      userGeneration: lease,
      clearUserState: () => registry.clearAll(),
    })

    const oldLogin = startup.login()
    await vi.waitFor(() => expect(authLogin).toHaveBeenCalledWith('first-code'))
    const newLogin = startup.login()
    await vi.waitFor(() => expect(authLogin).toHaveBeenCalledWith('second-code'))
    const newGeneration = lease.capture()

    firstLogin.reject(new Error('old login failed'))
    await expect(oldLogin).rejects.toBeInstanceOf(UserGenerationInvalidatedError)

    expect(lease.capture()).toBe(newGeneration)
    expect(purge).not.toHaveBeenCalled()
    expect(activate).not.toHaveBeenCalled()
    expect(save).not.toHaveBeenCalled()

    secondLogin.resolve(secondSession)
    await expect(newLogin).resolves.toBe('PLAN')
    expect(activate).toHaveBeenCalledOnce()
    expect(save).toHaveBeenCalledExactlyOnceWith(secondSession)
    expect(purge).not.toHaveBeenCalled()
  })

  it('keeps local storage locked and completes rollback when login itself returns 401', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const stored = new Map<string, unknown>([['old-session', firstSession]])
    const clear = vi.fn(async () => { stored.delete('old-session') })
    const authNavigation = vi.fn(async () => undefined)
    const startupNavigation = vi.fn(async () => undefined)
    const purge = vi.fn(async () => { stored.clear() })
    const activate = vi.fn()
    const sessions: SessionAccessPort = {
      load: vi.fn(async () => firstSession),
      save: vi.fn(async (session) => { stored.set('session', session) }),
      clear,
    }
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      {
        async request<T>(): Promise<TransportResponse<T>> {
          return {
          statusCode: 401,
            data: {
              error: { code: 'AUTHENTICATION_REQUIRED', retryable: false },
            } as T,
          }
        },
      },
      sessions,
      async () => {
        registry.clearAll()
        await authNavigation()
      },
      undefined,
      lease,
    )
    const startup = createStartupUseCases({
      sessionStore: sessions,
      wechatLogin: { getCode: vi.fn(async () => 'expired-login-code') },
      auth: { login: (code) => api.login(code) },
      workout: { hasActive: vi.fn(async () => false) },
      profile: { exists: vi.fn(async () => true) },
      plan: { hasActivePlan: vi.fn(async () => true) },
      navigation: { replace: startupNavigation },
      localUserData: { activate, purge },
      userGeneration: lease,
      clearUserState: () => registry.clearAll(),
    })

    await expect(startup.login()).rejects.toMatchObject({
      code: 'AUTHENTICATION_REQUIRED',
    })

    expect(activate).not.toHaveBeenCalled()
    expect(sessions.save).not.toHaveBeenCalled()
    expect(clear).not.toHaveBeenCalled()
    expect(purge).toHaveBeenCalledExactlyOnceWith('LOGIN_ROLLBACK')
    expect(stored.size).toBe(0)
    expect(authNavigation).not.toHaveBeenCalled()
    expect(startupNavigation).not.toHaveBeenCalled()
  })

  it('invalidates authentication-expired work before clearing, then permits only a new login generation to save', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const expiredGeneration = lease.capture()
    const clear = vi.fn(async () => {
      expect(() => lease.assertCurrent(expiredGeneration)).toThrow(
        UserGenerationInvalidatedError,
      )
    })
    const save = vi.fn(async () => undefined)
    const purge = vi.fn(async () => undefined)
    const navigation = { replace: vi.fn(async () => undefined) }
    const startup = createStartupUseCases({
      sessionStore: { load: vi.fn(), save, clear },
      wechatLogin: { getCode: vi.fn(async () => 'new-code') },
      auth: { login: vi.fn(async () => secondSession) },
      workout: { hasActive: vi.fn(async () => false) },
      profile: { exists: vi.fn(async () => true) },
      plan: { hasActivePlan: vi.fn(async () => true) },
      navigation,
      localUserData: { activate: vi.fn(), purge, isClearVerified: () => true },
      userGeneration: lease,
      clearUserState: () => registry.clearAll(),
    })

    await expect(startup.authenticationExpired()).resolves.toBe('LOGIN')
    await expect(startup.login()).resolves.toBe('PLAN')

    expect(clear).not.toHaveBeenCalled()
    expect(purge).toHaveBeenCalledExactlyOnceWith('AUTHENTICATION_EXPIRED')
    expect(save).toHaveBeenCalledExactlyOnceWith(secondSession)
    expect(navigation.replace).toHaveBeenNthCalledWith(1, 'LOGIN')
    expect(navigation.replace).toHaveBeenNthCalledWith(2, 'PLAN')
  })

  it('purges account A data on authentication expiry before account B can activate storage', async () => {
    const values = new Map<string, unknown>([
      ['fitness.session.v1', firstSession],
      ['fitness.workout.draft.record.account-a.1', { exercise: 'ROW' }],
      ['fitness.workout.draft.next-training-day.v1', { dayCode: 'DAY_A' }],
      ['fitness.workout.queue.account-a.v1', [{ operation: 'record-set' }]],
      ['fitness.privacy.export.account-a.v1', { id: 'old-export' }],
    ])
    taro.getStorageInfo.mockReset().mockImplementation(async () => ({
      keys: [...values.keys()],
    }))
    taro.removeStorage.mockReset().mockImplementation(
      async ({ key }: { key: string }) => {
        if (!values.has(key)) {
          throw { errMsg: 'removeStorage:fail data not found' }
        }
        values.delete(key)
      },
    )
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const localData = createGenerationBoundUserScopedDataLifecycle(
      createWeappUserScopedDataLifecycle(),
      lease,
    )
    const sessionStore: SessionAccessPort = {
      load: () => localData.runClearedSessionRead(async () => (
        values.get('fitness.session.v1') as Session | undefined
      ) ?? null),
      save: (session) => localData.runUserOperation(async () => {
        values.set('fitness.session.v1', session)
      }),
      clear: () => localData.runUserOperation(async () => {
        values.delete('fitness.session.v1')
      }),
    }
    const navigateExpired = vi.fn(async () => undefined)
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      {
        async request<T>(): Promise<TransportResponse<T>> {
          return {
            statusCode: 401,
            data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
          }
        },
      },
      sessionStore,
      async () => {
        registry.clearAll()
        await localData.purge('AUTHENTICATION_EXPIRED')
        await navigateExpired()
      },
      undefined,
      lease,
    )

    await expect(api.getProfileVersion()).rejects.toBeInstanceOf(
      UserGenerationInvalidatedError,
    )
    expect(values.size).toBe(0)
    expect(navigateExpired).toHaveBeenCalledOnce()
    await expect(localData.runUserOperation(async () => undefined))
      .rejects.toBeInstanceOf(UserScopedStorageBlockedError)

    const startup = createStartupUseCases({
      sessionStore,
      wechatLogin: { getCode: vi.fn(async () => 'account-b-code') },
      auth: { login: vi.fn(async () => secondSession) },
      workout: { hasActive: vi.fn(async () => false) },
      profile: { exists: vi.fn(async () => true) },
      plan: { hasActivePlan: vi.fn(async () => true) },
      navigation: { replace: vi.fn(async () => undefined) },
      localUserData: localData,
      userGeneration: lease,
      clearUserState: () => registry.clearAll(),
    })

    await expect(startup.login()).resolves.toBe('PLAN')
    expect(values.get('fitness.session.v1')).toEqual(secondSession)
    expect([...values.keys()]).toEqual(['fitness.session.v1'])
  })

  it('keeps sync intent, API resolution, and local convergence on one generation', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const oldResolution = deferred<ReturnType<typeof conflictResolution>>()
    const convergeConflict = vi.fn(async () => undefined)
    const listSyncConflicts = vi.fn(async () => [])
    const resolveSyncConflict = vi.fn()
      .mockImplementationOnce(() => oldResolution.promise)
      .mockResolvedValueOnce(conflictResolution())
    const operations = createProgressGenerationOperations({
      userGeneration: lease,
      workouts: {
        pendingConflictResolutions: vi.fn(async () => [conflictIntent()]),
        rememberConflictResolution: vi.fn(async () => true),
        convergeConflict,
      },
      api: {
        listSyncConflicts,
        resolveSyncConflict,
        getActivePlan: vi.fn(),
        applyRecommendation: vi.fn(),
      },
    })
    registry.register(operations.clearUserState)

    const oldReconcile = operations.reconcileSyncConflicts()
    await vi.waitFor(() => expect(resolveSyncConflict).toHaveBeenCalledOnce())
    registry.clearAll()
    lease.begin()
    oldResolution.resolve(conflictResolution())

    await expect(oldReconcile).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(convergeConflict).not.toHaveBeenCalled()
    expect(listSyncConflicts).not.toHaveBeenCalled()

    await expect(operations.reconcileSyncConflicts()).resolves.toEqual([])
    expect(convergeConflict).toHaveBeenCalledOnce()
    expect(listSyncConflicts).toHaveBeenCalledOnce()
  })

  it('does not apply a recommendation after active-plan loading crosses an account generation', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const oldPlan = deferred<ReturnType<typeof activePlan>>()
    const applyRecommendation = vi.fn(async () => progressionRecommendation())
    const getActivePlan = vi.fn()
      .mockImplementationOnce(() => oldPlan.promise)
      .mockResolvedValueOnce(activePlan())
    const operations = createProgressGenerationOperations({
      userGeneration: lease,
      workouts: {
        pendingConflictResolutions: vi.fn(async () => []),
        rememberConflictResolution: vi.fn(async () => true),
        convergeConflict: vi.fn(async () => undefined),
      },
      api: {
        listSyncConflicts: vi.fn(async () => []),
        resolveSyncConflict: vi.fn(),
        getActivePlan,
        applyRecommendation,
      },
    })
    registry.register(operations.clearUserState)

    const oldApply = operations.applyProgressionRecommendationForActivePlan(
      'recommendation-1',
      42.5,
    )
    await vi.waitFor(() => expect(getActivePlan).toHaveBeenCalledOnce())
    registry.clearAll()
    lease.begin()
    oldPlan.resolve(activePlan())

    await expect(oldApply).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(applyRecommendation).not.toHaveBeenCalled()

    await expect(operations.applyProgressionRecommendationForActivePlan(
      'recommendation-1',
      42.5,
    )).resolves.toEqual(progressionRecommendation())
    expect(applyRecommendation).toHaveBeenCalledExactlyOnceWith(
      'recommendation-1',
      3,
      42.5,
      'progression-recommendation-1-42.5-v3',
    )
  })

  it('fences privacy proof, deletion, and revoked-account lifecycle with one operation generation', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const proof = deferred<string>()
    const exportData = vi.fn()
    const privacyFromProof = createVerifiedPrivacyUseCases(
      {
        exportData,
        requestDeletion: vi.fn(),
        getDeletionRequest: vi.fn(),
      },
      { getProof: () => proof.promise },
      undefined,
      lease,
    )
    const oldExport = privacyFromProof.exportData()
    registry.clearAll()
    lease.begin()
    proof.resolve('old-proof-redacted')

    await expect(oldExport).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(exportData).not.toHaveBeenCalled()

    const deletion = deferred<ReturnType<typeof deletionRequest>>()
    const onAccessRevoked = vi.fn(async () => undefined)
    const requestDeletion = vi.fn(() => deletion.promise)
    const privacyFromDeletion = createVerifiedPrivacyUseCases(
      {
        exportData: vi.fn(),
        requestDeletion,
        getDeletionRequest: vi.fn(),
      },
      { getProof: vi.fn(async () => 'new-proof-redacted') },
      { onAccessRevoked },
      lease,
    )
    const oldDeletion = privacyFromDeletion.requestDeletion('DELETE')
    await vi.waitFor(() => expect(requestDeletion).toHaveBeenCalledOnce())
    registry.clearAll()
    lease.begin()
    deletion.resolve(deletionRequest())

    await expect(oldDeletion).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(onAccessRevoked).not.toHaveBeenCalled()
  })

  it('keeps getCode and proof issuance on one account generation', async () => {
    const lease = createUserGenerationLease()
    const getCode = deferred<string>()
    const issueProof = vi.fn(async () => 'proof-redacted')
    const proof = createGenerationBoundReauthenticationProof({
      userGeneration: lease,
      getCode: () => getCode.promise,
      issueProof,
    })

    const oldProof = proof.getProof()
    lease.begin()
    getCode.resolve('old-code-redacted')

    await expect(oldProof).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(issueProof).not.toHaveBeenCalled()
  })

  it('does not continue startup destination resolution after an account switch', async () => {
    const lease = createUserGenerationLease()
    const workoutState = deferred<'NONE'>()
    const profileExists = vi.fn(async () => true)
    const hasActivePlan = vi.fn(async () => true)
    const navigation = vi.fn(async () => undefined)
    const getStartupState = vi.fn(() => workoutState.promise)
    const startup = createStartupUseCases({
      sessionStore: { load: vi.fn(async () => firstSession), save: vi.fn(), clear: vi.fn() },
      wechatLogin: { getCode: vi.fn() },
      auth: { login: vi.fn() },
      workout: {
        hasActive: vi.fn(async () => false),
        getStartupState,
      },
      profile: { exists: profileExists },
      plan: { hasActivePlan },
      navigation: { replace: navigation },
      userGeneration: lease,
    })

    const oldStart = startup.start()
    await vi.waitFor(() => expect(getStartupState).toHaveBeenCalledOnce())
    lease.begin()
    workoutState.resolve('NONE')

    await expect(oldStart).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    expect(profileExists).not.toHaveBeenCalled()
    expect(hasActivePlan).not.toHaveBeenCalled()
    expect(navigation).not.toHaveBeenCalled()
  })

  it('purges after every observer is attempted and keeps activation fail-closed on observer failure', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const localData = createGenerationBoundUserScopedDataLifecycle(
      createSerialUserDataLifecycle(),
      lease,
      registry,
    )
    const laterObserver = vi.fn()
    registry.register(() => { throw new Error('secret observer detail') })
    registry.register(laterObserver)
    const purge = vi.spyOn(localData, 'purge')
    const navigate = vi.fn(async () => undefined)
    const remoteLogout = vi.fn(async () => undefined)
    const account = createAccountLifecycleUseCases({
      remote: {
        logout: remoteLogout,
        prepareLogout: () => remoteLogout,
      },
      localData,
      navigation: { replaceLogin: navigate },
      login: vi.fn(),
      clearMemory: () => registry.clearAll(),
      completeMemoryClear: () => registry.completeClear(),
      userGeneration: lease,
    })

    const error = await account.logout().catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(UserScopedStateClearError)
    expect(error).toMatchObject({ failureCount: 1, retryable: false })
    expect(String((error as Error).message)).not.toContain('secret observer detail')
    expect(laterObserver).toHaveBeenCalledOnce()
    expect(purge).toHaveBeenCalledExactlyOnceWith('LOGOUT')
    expect(remoteLogout).toHaveBeenCalledOnce()
    expect(navigate).toHaveBeenCalledOnce()
    await expect(localData.runUserOperation(async () => undefined))
      .rejects.toBeInstanceOf(UserScopedStateClearError)
    expect(() => localData.activate()).toThrow(UserScopedStateClearError)
  })

  it('prepares A logout synchronously and keeps B admission blocked until it completes', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const localData = createGenerationBoundUserScopedDataLifecycle(
      createSerialUserDataLifecycle(),
      lease,
      registry,
    )
    let currentSession: Session | null = firstSession
    const sessions: SessionAccessPort = {
      load: () => localData.runClearedSessionRead(async () => currentSession),
      loadImmediately: () => currentSession,
      save: (session) => localData.runUserOperation(async () => { currentSession = session }),
      clear: () => localData.runUserOperation(async () => { currentSession = null }),
    }
    const response = deferred<TransportResponse<unknown>>()
    const request = vi.fn()
    const transport: TransportPort = {
      async request<T>(transportRequest: TransportRequest): Promise<TransportResponse<T>> {
        request(transportRequest)
        return response.promise as Promise<TransportResponse<T>>
      },
    }
    const api = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      sessions,
      undefined,
      undefined,
      lease,
    )
    const navigate = vi.fn(async () => undefined)
    const account = createAccountLifecycleUseCases({
      remote: {
        logout: () => api.logout(),
        prepareLogout: () => api.prepareLogout(),
      },
      localData,
      navigation: { replaceLogin: navigate },
      login: vi.fn(),
      clearMemory: () => registry.clearAll(),
      completeMemoryClear: () => registry.completeClear(),
      userGeneration: lease,
    })

    const oldLogout = account.logout()
    await vi.waitFor(() => expect(request).toHaveBeenCalledOnce())
    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://127.0.0.1:8080/api/v1/auth/logout',
      headers: expect.objectContaining({ Authorization: `Bearer ${firstSession.accessToken}` }),
    }))

    const getCode = vi.fn(async () => 'new-code-redacted')
    const login = createStartupUseCases({
      sessionStore: sessions,
      wechatLogin: { getCode },
      auth: { login: vi.fn(async () => secondSession) },
      workout: { hasActive: vi.fn(async () => false) },
      profile: { exists: vi.fn(async () => true) },
      plan: { hasActivePlan: vi.fn(async () => true) },
      navigation: { replace: vi.fn(async () => undefined) },
      localUserData: localData,
      userGeneration: lease,
      clearUserState: () => registry.clearAll(),
      completeUserStateClear: () => registry.completeClear(),
    })
    await expect(login.login()).rejects.toBeInstanceOf(UserScopedStateBlockedError)
    expect(getCode).not.toHaveBeenCalled()

    response.resolve({ statusCode: 200, data: { data: {} } })
    await expect(oldLogout).resolves.toEqual({ remoteLogoutSucceeded: true })
    expect(navigate).toHaveBeenCalledOnce()

    await expect(login.login()).resolves.toBe('PLAN')
    await expect(sessions.load()).resolves.toEqual(secondSession)
    expect(getCode).toHaveBeenCalledOnce()
  })

  it('lets a successful generation-aware login own the new lease during account switch', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    let currentSession: Session | null = firstSession
    const localData = createGenerationBoundUserScopedDataLifecycle(
      createSerialUserDataLifecycle(() => { currentSession = null }),
      lease,
      registry,
    )
    const sessions: SessionAccessPort = {
      load: () => localData.runClearedSessionRead(async () => currentSession),
      loadImmediately: () => currentSession,
      save: (session) => localData.runUserOperation(async () => { currentSession = session }),
      clear: () => localData.runUserOperation(async () => { currentSession = null }),
    }
    const destinations: string[] = []
    const startup = createStartupUseCases({
      sessionStore: sessions,
      wechatLogin: { getCode: vi.fn(async () => 'new-code-redacted') },
      auth: { login: vi.fn(async () => secondSession) },
      workout: { hasActive: vi.fn(async () => false) },
      profile: { exists: vi.fn(async () => true) },
      plan: { hasActivePlan: vi.fn(async () => true) },
      navigation: { replace: vi.fn(async (destination) => { destinations.push(destination) }) },
      localUserData: localData,
      userGeneration: lease,
      clearUserState: () => registry.clearAll(),
      completeUserStateClear: () => registry.completeClear(),
    })
    const account = createAccountLifecycleUseCases({
      remote: {
        logout: vi.fn(async () => undefined),
        prepareLogout: () => async () => undefined,
      },
      localData,
      navigation: { replaceLogin: vi.fn(async () => { destinations.push('LOGIN') }) },
      login: () => startup.login(),
      clearMemory: () => registry.clearAll(),
      completeMemoryClear: () => registry.completeClear(),
      userGeneration: lease,
    })

    await expect(account.switchAccount()).resolves.toEqual({
      remoteLogoutSucceeded: true,
      destination: 'PLAN',
    })
    await expect(sessions.load()).resolves.toEqual(secondSession)
    expect(destinations).toEqual(['LOGIN', 'PLAN'])
  })

  it('invalidates direct AI provider results before they can reach planning state', async () => {
    const lease = createUserGenerationLease()
    const registry = createUserScopedStateRegistry(lease)
    const firstGeneration = deferred<string>()
    const provider = createGenerationFencedAiTextProvider({
      generate: vi.fn()
        .mockImplementationOnce(() => firstGeneration.promise)
        .mockResolvedValueOnce('{"result":"new generation"}'),
    }, lease)
    const request = {
      purpose: 'PLAN_GENERATION' as const,
      systemPrompt: 'approved prompt',
      factsJson: '{}',
      explicitUserConsent: true,
    }

    const oldResult = provider.generate(request)
    registry.clearAll()
    firstGeneration.resolve('{"secret":"old generation"}')

    await expect(oldResult).rejects.toBeInstanceOf(UserGenerationInvalidatedError)
    await expect(provider.generate(request)).resolves.toBe('{"result":"new generation"}')
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function createSerialUserDataLifecycle(onPurge?: () => void): WeappUserScopedDataLifecycle {
  let tail: Promise<void> = Promise.resolve()
  let blocked = false
  let clearVerified = false

  function enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const task = tail.then(operation, operation)
    tail = task.then(() => undefined, () => undefined)
    return task
  }

  return {
    runUserOperation<T>(operation: () => Promise<T>): Promise<T> {
      if (blocked) return Promise.reject(new Error('blocked'))
      return enqueue(operation)
    },
    runClearedSessionRead<T>(operation: () => Promise<T | null>): Promise<T | null> {
      return blocked ? Promise.resolve(null) : enqueue(operation)
    },
    isClearVerified(): boolean {
      return blocked && clearVerified
    },
    async purge(): Promise<void> {
      blocked = true
      clearVerified = false
      await enqueue(async () => { onPurge?.() })
      clearVerified = true
    },
    activate(): void {
      blocked = false
      clearVerified = false
    },
  }
}

function conflictIntent() {
  return {
    conflictId: 'conflict-1',
    clientKey: 'client-key-1234',
    resolution: 'KEEP_SERVER' as const,
    expectedConflictVersion: 2,
  }
}

function conflictResolution() {
  return {
    conflictId: 'conflict-1',
    clientOperationSeq: 1,
    clientKey: 'client-key-1234',
    resolution: 'KEEP_SERVER' as const,
    outcome: 'ABANDONED' as const,
    authoritativeSessionVersion: 3,
    authoritativePayload: {},
  }
}

function activePlan() {
  return {
    planId: 'plan-1',
    activeVersion: {
      id: 'plan-version-1',
      planId: 'plan-1',
      versionNumber: 3,
      sourceType: 'PROGRESSION' as const,
      plan: { days: [] },
      ruleReference: { ruleVersion: '1.0.0', policyVersion: '1.0.0' },
      confirmedWarningCodes: [],
      createdAt: '2026-08-28T08:00:00Z',
    },
  }
}

function progressionRecommendation() {
  return {
    id: 'recommendation-1',
    exerciseCode: 'ROW',
    status: 'APPLIED' as const,
    decision: 'INCREASE' as const,
    reasonCode: 'TARGET_REACHED',
    currentWeightKg: 40,
    recommendedWeightKg: 42.5,
    acceptedWeightKg: 42.5,
    algorithmVersion: 'v1',
    appliedPlanId: 'plan-1',
    appliedPlanVersionId: 'plan-version-2',
    createdAt: '2026-08-28T08:00:00Z',
  }
}

function deletionRequest() {
  return {
    id: 'deletion-1',
    status: 'ACCESS_REVOKED' as const,
    requestedAt: '2026-08-28T08:00:00Z',
    updatedAt: '2026-08-28T08:01:00Z',
    deletionScope: ['PROFILE'] as Array<'PROFILE'>,
    retainedCategories: ['SECURITY_AUDIT'] as Array<'SECURITY_AUDIT'>,
  }
}

function emptyDraftStore(): WorkoutDraftStore & { save: ReturnType<typeof vi.fn> } {
  return {
    loadActive: vi.fn(async () => null),
    save: vi.fn(async (_draft: WorkoutDraft) => undefined),
    clearActive: vi.fn(async () => undefined),
    discardCorrupted: vi.fn(async () => undefined),
  }
}

function fixedClock() {
  return { nowUtc: () => '2026-08-28T08:00:00.000Z' }
}

function startRequest(clientSessionKey: string) {
  return {
    clientSessionKey,
    planId: 'plan-id',
    planVersionNo: 1,
    planDayId: 'DAY_A',
  }
}

function serverSession(id: string) {
  return {
    id,
    planId: 'plan-id',
    planVersionId: 'plan-version-id',
    planVersionNo: 1,
    planDayId: 'DAY_A',
    status: 'IN_PROGRESS' as const,
    startedAt: '2026-08-28T08:00:00.000Z',
    version: 1,
    warmupPrescription: {
      schemaVersion: 'workout-warmup-prescription-v1' as const,
      ruleVersion: '1.3.0',
      generalWarmup: { occurrences: 1 as const, durationSeconds: 180 },
      rampWarmup: {
        exerciseId: 'exercise-id',
        exerciseOrder: 1,
        status: 'READY' as const,
        sets: [{ weightKg: 10, reps: 10 }],
      },
      countsTowardTrainingVolume: false as const,
      countsTowardProgression: false as const,
    },
    exercises: [{
      id: 'exercise-id',
      order: 1,
      exerciseCode: 'ROW',
      exerciseName: '划船',
      contentVersion: 'v1',
      equipment: ['CABLE'],
      prescription: {
        workSets: 2,
        repMin: 8,
        repMax: 10,
        restSeconds: 60,
        weightStatus: 'KNOWN' as const,
        targetWeightKg: 25,
        unit: 'KG' as const,
      },
      status: 'ACTIVE' as const,
    }],
  }
}
