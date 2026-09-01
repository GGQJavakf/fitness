import { describe, expect, it, vi } from 'vitest'
import type { Session } from '../src/application/onboarding'
import {
  FitnessApiClient,
  type SessionAccessPort,
  type TransportPort,
  type TransportRequest,
  type TransportResponse,
} from '../src/infrastructure/api/client'
import {
  MAX_SESSION_REFRESH_LINEAGE_HOPS,
  SessionRefreshCoordinator,
  SessionRefreshInvalidatedError,
} from '../src/infrastructure/api/sessionRefreshCoordinator'

const expiredSession: Session = {
  accessToken: 'expired-access-redacted',
  refreshToken: 'expired-refresh-redacted',
  expiresAt: '2026-08-28T00:00:00Z',
}

const rotatedSession: Session = {
  accessToken: 'rotated-access-redacted',
  refreshToken: 'rotated-refresh-redacted',
  expiresAt: '2026-08-28T01:00:00Z',
}

describe('SessionRefreshCoordinator', () => {
  it('runs one refresh for concurrent recoveries of the same refresh token', async () => {
    const coordinator = new SessionRefreshCoordinator()
    let releaseRefresh!: () => void
    const refreshReleased = new Promise<void>((resolve) => {
      releaseRefresh = resolve
    })
    const refresh = vi.fn(async () => {
      await refreshReleased
      return rotatedSession
    })

    const first = coordinator.recover(expiredSession, refresh)
    const second = coordinator.recover(expiredSession, refresh)
    releaseRefresh()

    await expect(Promise.all([first, second])).resolves.toEqual([
      rotatedSession,
      rotatedSession,
    ])
    expect(refresh).toHaveBeenCalledOnce()
  })

  it('reuses the latest rotated session for late failures in the same token lineage', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const twiceRotatedSession: Session = {
      accessToken: 'twice-rotated-access-redacted',
      refreshToken: 'twice-rotated-refresh-redacted',
      expiresAt: '2026-08-28T02:00:00Z',
    }

    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(rotatedSession))
    const staleRefresh = vi.fn().mockRejectedValue(new Error('must not refresh a stale token'))
    await expect(coordinator.recover(expiredSession, staleRefresh)).resolves.toBe(rotatedSession)
    expect(staleRefresh).not.toHaveBeenCalled()

    await coordinator.recover(rotatedSession, vi.fn().mockResolvedValue(twiceRotatedSession))
    await expect(coordinator.recover(expiredSession, staleRefresh)).resolves.toBe(twiceRotatedSession)
    expect(staleRefresh).not.toHaveBeenCalled()
  })

  it('joins a descendant refresh that is still in flight for a rotated token lineage', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const twiceRotatedSession: Session = {
      accessToken: 'twice-rotated-access-redacted',
      refreshToken: 'twice-rotated-refresh-redacted',
      expiresAt: '2026-08-28T02:00:00Z',
    }
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(rotatedSession))

    let releaseDescendantRefresh!: () => void
    const descendantRefreshReleased = new Promise<void>((resolve) => {
      releaseDescendantRefresh = resolve
    })
    const descendantRefresh = coordinator.recover(rotatedSession, async () => {
      await descendantRefreshReleased
      return twiceRotatedSession
    })
    const staleRefresh = vi.fn().mockRejectedValue(new Error('must not refresh a stale token'))
    let lateRecoverySettled = false
    const lateRecovery = coordinator.recover(expiredSession, staleRefresh).finally(() => {
      lateRecoverySettled = true
    })

    await Promise.resolve()
    expect(lateRecoverySettled).toBe(false)
    releaseDescendantRefresh()

    await expect(Promise.all([descendantRefresh, lateRecovery])).resolves.toEqual([
      twiceRotatedSession,
      twiceRotatedSession,
    ])
    expect(staleRefresh).not.toHaveBeenCalled()
  })

  it('resolves only an already-known descendant refresh without starting a new one', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const twiceRotatedSession: Session = {
      accessToken: 'twice-rotated-access-redacted',
      refreshToken: 'twice-rotated-refresh-redacted',
      expiresAt: '2026-08-28T02:00:00Z',
    }
    await expect(coordinator.resolveKnownLatest(expiredSession)).resolves.toBeNull()
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(rotatedSession))

    let releaseDescendantRefresh!: () => void
    const descendantRefreshReleased = new Promise<void>((resolve) => {
      releaseDescendantRefresh = resolve
    })
    const descendantRefresh = coordinator.recover(rotatedSession, async () => {
      await descendantRefreshReleased
      return twiceRotatedSession
    })
    const knownLatest = coordinator.resolveKnownLatest(rotatedSession)
    expect(coordinator.hasKnownRecovery(rotatedSession)).toBe(true)
    releaseDescendantRefresh()

    await expect(Promise.all([descendantRefresh, knownLatest])).resolves.toEqual([
      twiceRotatedSession,
      twiceRotatedSession,
    ])
    await expect(coordinator.resolveKnownLatest(rotatedSession))
      .resolves.toBe(twiceRotatedSession)
  })

  it('rejects a stale observed recovery after its source lineage entry was evicted', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const staleObservation = coordinator.captureRotationRevision()
    let currentSession = expiredSession
    for (let index = 0; index <= MAX_SESSION_REFRESH_LINEAGE_HOPS; index += 1) {
      const descendant: Session = {
        accessToken: `coordinator-descendant-access-${index + 1}-redacted`,
        refreshToken: `coordinator-descendant-refresh-${index + 1}-redacted`,
        expiresAt: `2026-08-28T${String(index + 1).padStart(2, '0')}:00:00Z`,
      }
      await coordinator.recover(
        currentSession,
        vi.fn().mockResolvedValue(descendant),
        coordinator.captureRotationRevision(),
      )
      currentSession = descendant
    }
    const staleRefresh = vi.fn().mockRejectedValue(
      new Error('evicted stale source must not start another refresh'),
    )

    await expect(
      coordinator.recover(expiredSession, staleRefresh, staleObservation),
    ).rejects.toBeInstanceOf(SessionRefreshInvalidatedError)
    expect(staleRefresh).not.toHaveBeenCalled()
  })

  it('stops resolving a malformed cyclic rotation lineage', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const sameTokenSession: Session = {
      ...rotatedSession,
      accessToken: 'same-token-access-redacted',
      refreshToken: expiredSession.refreshToken,
    }
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(sameTokenSession))

    const staleRefresh = vi.fn().mockRejectedValue(new Error('must not refresh a stale token'))
    await expect(coordinator.recover(expiredSession, staleRefresh)).resolves.toBe(sameTokenSession)
    expect(staleRefresh).not.toHaveBeenCalled()
  })

  it('invalidates a cache hit when clear runs before its asynchronous delivery', async () => {
    const coordinator = new SessionRefreshCoordinator()
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(rotatedSession))
    const staleRefresh = vi.fn().mockRejectedValue(new Error('cache hit must not refresh'))

    const cachedRecovery = coordinator.recover(expiredSession, staleRefresh)
    coordinator.clear()

    await expect(cachedRecovery).rejects.toBeInstanceOf(SessionRefreshInvalidatedError)
    expect(staleRefresh).not.toHaveBeenCalled()

    const newGenerationSession: Session = {
      accessToken: 'new-generation-access-redacted',
      refreshToken: 'new-generation-refresh-redacted',
      expiresAt: '2026-08-28T03:00:00Z',
    }
    const newRefresh = vi.fn().mockResolvedValue(newGenerationSession)
    await expect(coordinator.recover(expiredSession, newRefresh))
      .resolves.toBe(newGenerationSession)
    expect(newRefresh).toHaveBeenCalledOnce()
  })

  it('invalidates an older in-flight refresh that resolves after clear', async () => {
    const coordinator = new SessionRefreshCoordinator()
    let releaseRefresh!: () => void
    const refreshReleased = new Promise<void>((resolve) => {
      releaseRefresh = resolve
    })
    const firstRefresh = vi.fn(async () => {
      await refreshReleased
      return rotatedSession
    })

    const pending = coordinator.recover(expiredSession, firstRefresh)
    coordinator.clear()
    releaseRefresh()
    await expect(pending).rejects.toMatchObject({
      name: 'SessionRefreshInvalidatedError',
      code: 'SESSION_REFRESH_INVALIDATED',
    })

    const replacement = vi.fn().mockResolvedValue(rotatedSession)
    await expect(coordinator.recover(expiredSession, replacement)).resolves.toBe(rotatedSession)
    expect(replacement).toHaveBeenCalledOnce()
  })

  it('replaces an older refresh rejection with the explicit invalidation control error', async () => {
    const coordinator = new SessionRefreshCoordinator()
    let rejectRefresh!: (error: Error) => void
    const refreshResult = new Promise<Session>((_resolve, reject) => {
      rejectRefresh = reject
    })
    const pending = coordinator.recover(expiredSession, () => refreshResult)

    coordinator.clear()
    rejectRefresh(new Error('stale transport failure'))

    const error = await pending.catch((reason: unknown) => reason)
    expect(error).toBeInstanceOf(SessionRefreshInvalidatedError)
    expect(error).toMatchObject({ code: 'SESSION_REFRESH_INVALIDATED' })
    expect(String((error as Error).message)).not.toContain(expiredSession.refreshToken)
  })

  it('keeps a new-generation single-flight independent from an older invalidated refresh', async () => {
    const coordinator = new SessionRefreshCoordinator()
    let releaseOldRefresh!: () => void
    const oldRefreshReleased = new Promise<void>((resolve) => {
      releaseOldRefresh = resolve
    })
    const oldRefresh = vi.fn(async () => {
      await oldRefreshReleased
      return rotatedSession
    })
    const oldRecovery = coordinator.recover(expiredSession, oldRefresh)
    const oldRecoveryResult = expect(oldRecovery).rejects.toBeInstanceOf(
      SessionRefreshInvalidatedError,
    )

    coordinator.clear()

    let releaseNewRefresh!: () => void
    const newRefreshReleased = new Promise<void>((resolve) => {
      releaseNewRefresh = resolve
    })
    const newGenerationSession: Session = {
      accessToken: 'new-generation-access-redacted',
      refreshToken: 'new-generation-refresh-redacted',
      expiresAt: '2026-08-28T03:00:00Z',
    }
    const newRefresh = vi.fn(async () => {
      await newRefreshReleased
      return newGenerationSession
    })
    const newRecovery = coordinator.recover(expiredSession, newRefresh)
    const newFollower = coordinator.recover(expiredSession, newRefresh)

    releaseOldRefresh()
    await oldRecoveryResult
    releaseNewRefresh()

    await expect(Promise.all([newRecovery, newFollower])).resolves.toEqual([
      newGenerationSession,
      newGenerationSession,
    ])
    expect(oldRefresh).toHaveBeenCalledOnce()
    expect(newRefresh).toHaveBeenCalledOnce()
  })
})

describe('FitnessApiClient shared session refresh', () => {
  it('shares one refresh and its rotation across client instances', async () => {
    const coordinator = new SessionRefreshCoordinator()
    let storedSession = expiredSession
    let expiredRequestCount = 0
    let resolveBothExpired!: () => void
    const bothExpired = new Promise<void>((resolve) => {
      resolveBothExpired = resolve
    })
    let releaseRefresh!: () => void
    const refreshReleased = new Promise<void>((resolve) => {
      releaseRefresh = resolve
    })
    const refreshRequests: TransportRequest[] = []
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          refreshRequests.push(request)
          await refreshReleased
          return { statusCode: 200, data: { data: rotatedSession } as T }
        }
        if (request.headers.Authorization === `Bearer ${rotatedSession.accessToken}`) {
          return { statusCode: 200, data: { data: { version: 7 } } as T }
        }
        expiredRequestCount += 1
        if (expiredRequestCount === 2) resolveBothExpired()
        return {
          statusCode: 401,
          data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
        }
      },
    }
    const sessions: SessionAccessPort = {
      load: async () => storedSession,
      save: async (session) => { storedSession = session },
      clear: vi.fn(),
    }
    const firstClient = new FitnessApiClient(
      'http://127.0.0.1:8080', transport, sessions, undefined, coordinator,
    )
    const secondClient = new FitnessApiClient(
      'http://127.0.0.1:8080', transport, sessions, undefined, coordinator,
    )

    const profileVersion = firstClient.getProfileVersion()
    const equipmentVersion = secondClient.getEquipmentVersion()
    await bothExpired
    releaseRefresh()

    await expect(Promise.all([profileVersion, equipmentVersion])).resolves.toEqual([7, 7])
    expect(refreshRequests).toHaveLength(1)
    expect(refreshRequests[0]?.body).toEqual({ refreshToken: expiredSession.refreshToken })
    expect(storedSession).toBe(rotatedSession)
  })

  it('waits for a descendant refresh instead of retrying with an expired intermediate token', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const twiceRotatedSession: Session = {
      accessToken: 'twice-rotated-access-redacted',
      refreshToken: 'twice-rotated-refresh-redacted',
      expiresAt: '2026-08-28T02:00:00Z',
    }
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(rotatedSession))

    let markDescendantRefreshStarted!: () => void
    const descendantRefreshStarted = new Promise<void>((resolve) => {
      markDescendantRefreshStarted = resolve
    })
    let releaseDescendantRefresh!: () => void
    const descendantRefreshReleased = new Promise<void>((resolve) => {
      releaseDescendantRefresh = resolve
    })
    const authenticationFailure = vi.fn()
    const retriedAuthorizations: string[] = []
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          expect(request.body).toEqual({ refreshToken: rotatedSession.refreshToken })
          markDescendantRefreshStarted()
          await descendantRefreshReleased
          return { statusCode: 200, data: { data: twiceRotatedSession } as T }
        }
        const authorization = request.headers.Authorization ?? ''
        if (authorization === `Bearer ${twiceRotatedSession.accessToken}`) {
          retriedAuthorizations.push(authorization)
          return { statusCode: 200, data: { data: { version: 7 } } as T }
        }
        return {
          statusCode: 401,
          data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
        }
      },
    }
    const createClient = (session: Session) => new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      {
        load: vi.fn().mockResolvedValue(session),
        save: vi.fn(),
        clear: vi.fn(),
      },
      authenticationFailure,
      coordinator,
    )
    const currentClient = createClient(rotatedSession)
    const staleClient = createClient(expiredSession)

    const currentRequest = currentClient.getProfileVersion()
    await descendantRefreshStarted
    const staleRequest = staleClient.getEquipmentVersion()
    await Promise.resolve()
    expect(authenticationFailure).not.toHaveBeenCalled()
    releaseDescendantRefresh()

    await expect(Promise.all([currentRequest, staleRequest])).resolves.toEqual([7, 7])
    expect(retriedAuthorizations).toEqual([
      `Bearer ${twiceRotatedSession.accessToken}`,
      `Bearer ${twiceRotatedSession.accessToken}`,
    ])
    expect(authenticationFailure).not.toHaveBeenCalled()
  })

  it('follows repeated rotations that complete while earlier-token retries are in flight', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const twiceRotatedSession: Session = {
      accessToken: 'twice-rotated-access-redacted',
      refreshToken: 'twice-rotated-refresh-redacted',
      expiresAt: '2026-08-28T02:00:00Z',
    }
    const thirdRotatedSession: Session = {
      accessToken: 'third-rotated-access-redacted',
      refreshToken: 'third-rotated-refresh-redacted',
      expiresAt: '2026-08-28T03:00:00Z',
    }
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(rotatedSession))

    let markStaleBRetryStarted!: () => void
    const staleBRetryStarted = new Promise<void>((resolve) => {
      markStaleBRetryStarted = resolve
    })
    let releaseStaleBRetry!: () => void
    const staleBRetryReleased = new Promise<void>((resolve) => {
      releaseStaleBRetry = resolve
    })
    let markStaleCRetryStarted!: () => void
    const staleCRetryStarted = new Promise<void>((resolve) => {
      markStaleCRetryStarted = resolve
    })
    let releaseStaleCRetry!: () => void
    const staleCRetryReleased = new Promise<void>((resolve) => {
      releaseStaleCRetry = resolve
    })
    const authenticationFailure = vi.fn()
    const refreshTokens: string[] = []
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        const authorization = request.headers.Authorization
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          const refreshToken = (request.body as { refreshToken: string }).refreshToken
          refreshTokens.push(refreshToken)
          if (refreshToken === rotatedSession.refreshToken) {
            return { statusCode: 200, data: { data: twiceRotatedSession } as T }
          }
          if (refreshToken === twiceRotatedSession.refreshToken) {
            return { statusCode: 200, data: { data: thirdRotatedSession } as T }
          }
          throw new Error('unexpected refresh token')
        }
        if (request.url.endsWith('/api/v1/profile')) {
          if (authorization === `Bearer ${expiredSession.accessToken}`) {
            return { statusCode: 401, data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T }
          }
          if (authorization === `Bearer ${rotatedSession.accessToken}`) {
            markStaleBRetryStarted()
            await staleBRetryReleased
            return { statusCode: 401, data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T }
          }
          if (authorization === `Bearer ${twiceRotatedSession.accessToken}`) {
            markStaleCRetryStarted()
            await staleCRetryReleased
            return { statusCode: 401, data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T }
          }
          if (authorization === `Bearer ${thirdRotatedSession.accessToken}`) {
            return { statusCode: 200, data: { data: { version: 7 } } as T }
          }
        }
        if (request.url.endsWith('/api/v1/profile/equipment')) {
          if (authorization === `Bearer ${rotatedSession.accessToken}`) {
            return { statusCode: 401, data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T }
          }
          if (authorization === `Bearer ${twiceRotatedSession.accessToken}`) {
            return { statusCode: 200, data: { data: { version: 8 } } as T }
          }
        }
        if (request.url.endsWith('/api/v1/profile/preferences')) {
          if (authorization === `Bearer ${twiceRotatedSession.accessToken}`) {
            return { statusCode: 401, data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T }
          }
          if (authorization === `Bearer ${thirdRotatedSession.accessToken}`) {
            return { statusCode: 200, data: { data: { version: 9 } } as T }
          }
        }
        throw new Error('unexpected request')
      },
    }
    const createClient = (session: Session) => new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      {
        load: vi.fn().mockResolvedValue(session),
        save: vi.fn(),
        clear: vi.fn(),
      },
      authenticationFailure,
      coordinator,
    )

    const staleRequest = createClient(expiredSession).getProfileVersion()
    await staleBRetryStarted

    await expect(createClient(rotatedSession).getEquipmentVersion()).resolves.toBe(8)
    releaseStaleBRetry()
    await staleCRetryStarted

    await expect(createClient(twiceRotatedSession).getPreferencesVersion()).resolves.toBe(9)
    releaseStaleCRetry()

    await expect(staleRequest).resolves.toBe(7)
    expect(refreshTokens).toEqual([
      rotatedSession.refreshToken,
      twiceRotatedSession.refreshToken,
    ])
    expect(authenticationFailure).not.toHaveBeenCalled()
  })

  it('does not refresh or purge an initial request after its source rotation was evicted', async () => {
    const descendants = Array.from(
      { length: MAX_SESSION_REFRESH_LINEAGE_HOPS + 1 },
      (_, index): Session => ({
        accessToken: `initial-descendant-access-${index + 1}-redacted`,
        refreshToken: `initial-descendant-refresh-${index + 1}-redacted`,
        expiresAt: `2026-08-28T${String(index + 1).padStart(2, '0')}:00:00Z`,
      }),
    )
    let storedSession = expiredSession
    let markInitialRequestStarted!: () => void
    const initialRequestStarted = new Promise<void>((resolve) => {
      markInitialRequestStarted = resolve
    })
    let releaseInitialRequest!: () => void
    const initialRequestReleased = new Promise<void>((resolve) => {
      releaseInitialRequest = resolve
    })
    const equipmentRetrySuccess = new Set<string>()
    const refreshTokens: string[] = []
    const authenticationFailure = vi.fn()
    const lineage = [expiredSession, ...descendants]
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        const authorization = request.headers.Authorization
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          const refreshToken = (request.body as { refreshToken: string }).refreshToken
          refreshTokens.push(refreshToken)
          const sourceIndex = lineage.findIndex(
            (session) => session.refreshToken === refreshToken,
          )
          const nextSession = lineage[sourceIndex + 1]
          if (sourceIndex < 0 || !nextSession) throw new Error('unexpected refresh token')
          equipmentRetrySuccess.add(nextSession.accessToken)
          return { statusCode: 200, data: { data: nextSession } as T }
        }
        if (request.url.endsWith('/api/v1/profile/equipment')) {
          const accessToken = authorization?.replace('Bearer ', '') ?? ''
          if (equipmentRetrySuccess.delete(accessToken)) {
            return { statusCode: 200, data: { data: { version: 8 } } as T }
          }
          return {
            statusCode: 401,
            data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
          }
        }
        if (request.url.endsWith('/api/v1/profile')) {
          markInitialRequestStarted()
          await initialRequestReleased
          return {
            statusCode: 401,
            data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
          }
        }
        throw new Error('unexpected request')
      },
    }
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      {
        load: async () => storedSession,
        save: async (session) => { storedSession = session },
        clear: vi.fn(),
      },
      authenticationFailure,
    )

    const staleInitialRequest = client.getProfileVersion()
    await initialRequestStarted
    for (const _descendant of descendants) {
      await expect(client.getEquipmentVersion()).resolves.toBe(8)
    }
    expect(storedSession).toBe(descendants.at(-1))
    releaseInitialRequest()

    await expect(staleInitialRequest).rejects.toBeInstanceOf(
      SessionRefreshInvalidatedError,
    )
    expect(refreshTokens).toHaveLength(descendants.length)
    expect(authenticationFailure).not.toHaveBeenCalled()
  })

  it('observes rotation before a deferred session load can return an evicted snapshot', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const descendants = Array.from(
      { length: MAX_SESSION_REFRESH_LINEAGE_HOPS + 1 },
      (_, index): Session => ({
        accessToken: `load-descendant-access-${index + 1}-redacted`,
        refreshToken: `load-descendant-refresh-${index + 1}-redacted`,
        expiresAt: `2026-08-28T${String(index + 1).padStart(2, '0')}:00:00Z`,
      }),
    )
    let markLoadStarted!: () => void
    const loadStarted = new Promise<void>((resolve) => {
      markLoadStarted = resolve
    })
    let releaseLoad!: () => void
    const loadReleased = new Promise<void>((resolve) => {
      releaseLoad = resolve
    })
    const refreshTokens: string[] = []
    const authenticationFailure = vi.fn()
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          refreshTokens.push((request.body as { refreshToken: string }).refreshToken)
          throw new Error('evicted load snapshot must not start a refresh')
        }
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
        load: async () => {
          const snapshot = expiredSession
          markLoadStarted()
          await loadReleased
          return snapshot
        },
        save: vi.fn(),
        clear: vi.fn(),
      },
      authenticationFailure,
      coordinator,
    )

    const staleRequest = client.getProfileVersion()
    await loadStarted
    let currentSession = expiredSession
    for (const descendant of descendants) {
      await coordinator.recover(
        currentSession,
        vi.fn().mockResolvedValue(descendant),
        coordinator.captureRotationRevision(),
      )
      currentSession = descendant
    }
    releaseLoad()

    await expect(staleRequest).rejects.toBeInstanceOf(SessionRefreshInvalidatedError)
    expect(refreshTokens).toEqual([])
    expect(authenticationFailure).not.toHaveBeenCalled()
  })

  it('does not purge after a retry source is evicted by more than the lineage limit', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const descendants = Array.from(
      { length: MAX_SESSION_REFRESH_LINEAGE_HOPS + 1 },
      (_, index): Session => ({
        accessToken: `retry-descendant-access-${index + 1}-redacted`,
        refreshToken: `retry-descendant-refresh-${index + 1}-redacted`,
        expiresAt: `2026-08-28T${String(index + 2).padStart(2, '0')}:00:00Z`,
      }),
    )
    let markRetryStarted!: () => void
    const retryStarted = new Promise<void>((resolve) => {
      markRetryStarted = resolve
    })
    let releaseRetry!: () => void
    const retryReleased = new Promise<void>((resolve) => {
      releaseRetry = resolve
    })
    const authenticationFailure = vi.fn()
    const refreshTokens: string[] = []
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        const authorization = request.headers.Authorization
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          const refreshToken = (request.body as { refreshToken: string }).refreshToken
          refreshTokens.push(refreshToken)
          if (refreshToken !== expiredSession.refreshToken) {
            throw new Error('stale retry must not start another refresh')
          }
          return { statusCode: 200, data: { data: rotatedSession } as T }
        }
        if (authorization === `Bearer ${expiredSession.accessToken}`) {
          return {
            statusCode: 401,
            data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
          }
        }
        if (authorization === `Bearer ${rotatedSession.accessToken}`) {
          markRetryStarted()
          await retryReleased
          return {
            statusCode: 401,
            data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
          }
        }
        throw new Error('unexpected request')
      },
    }
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      {
        load: vi.fn().mockResolvedValue(expiredSession),
        save: vi.fn(),
        clear: vi.fn(),
      },
      authenticationFailure,
      coordinator,
    )

    const staleRetry = client.getProfileVersion()
    await retryStarted
    let currentSession = rotatedSession
    for (const descendant of descendants) {
      const observation = coordinator.captureRotationRevision()
      await coordinator.recover(
        currentSession,
        vi.fn().mockResolvedValue(descendant),
        observation,
      )
      currentSession = descendant
    }
    releaseRetry()

    await expect(staleRetry).rejects.toBeInstanceOf(SessionRefreshInvalidatedError)
    expect(refreshTokens).toEqual([expiredSession.refreshToken])
    expect(authenticationFailure).not.toHaveBeenCalled()
  })

  it('lets only the shared refresh owner save before publishing a rotation', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const twiceRotatedSession: Session = {
      accessToken: 'owner-twice-rotated-access-redacted',
      refreshToken: 'owner-twice-rotated-refresh-redacted',
      expiresAt: '2026-08-28T02:00:00Z',
    }
    let storedSession = expiredSession
    let bSaveCalls = 0
    let releaseLateBSave!: () => void
    const lateBSaveReleased = new Promise<void>((resolve) => {
      releaseLateBSave = resolve
    })
    let expiredRequests = 0
    let markBothExpired!: () => void
    const bothExpired = new Promise<void>((resolve) => {
      markBothExpired = resolve
    })
    const refreshTokens: string[] = []
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        const authorization = request.headers.Authorization
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          const refreshToken = (request.body as { refreshToken: string }).refreshToken
          refreshTokens.push(refreshToken)
          if (refreshToken === expiredSession.refreshToken) {
            return { statusCode: 200, data: { data: rotatedSession } as T }
          }
          if (refreshToken === rotatedSession.refreshToken) {
            return { statusCode: 200, data: { data: twiceRotatedSession } as T }
          }
          throw new Error('unexpected refresh token')
        }
        if (authorization === `Bearer ${expiredSession.accessToken}`) {
          expiredRequests += 1
          if (expiredRequests === 2) markBothExpired()
          await bothExpired
          return {
            statusCode: 401,
            data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
          }
        }
        if (
          request.url.endsWith('/api/v1/profile/preferences')
          && authorization === `Bearer ${rotatedSession.accessToken}`
        ) {
          return {
            statusCode: 401,
            data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
          }
        }
        if (authorization === `Bearer ${rotatedSession.accessToken}`) {
          return { statusCode: 200, data: { data: { version: 7 } } as T }
        }
        if (authorization === `Bearer ${twiceRotatedSession.accessToken}`) {
          return { statusCode: 200, data: { data: { version: 9 } } as T }
        }
        throw new Error('unexpected request')
      },
    }
    const sessions: SessionAccessPort = {
      load: async () => storedSession,
      save: async (session) => {
        if (session.refreshToken === rotatedSession.refreshToken) {
          bSaveCalls += 1
          if (bSaveCalls > 1) await lateBSaveReleased
        }
        storedSession = session
      },
      clear: vi.fn(),
    }
    const createClient = () => new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      sessions,
      vi.fn(),
      coordinator,
    )
    const firstFollower = createClient().getProfileVersion()
    const secondFollower = createClient().getEquipmentVersion()

    await expect(Promise.race([firstFollower, secondFollower])).resolves.toBe(7)
    await expect(createClient().getPreferencesVersion()).resolves.toBe(9)
    expect(storedSession).toBe(twiceRotatedSession)
    releaseLateBSave()

    await expect(Promise.all([firstFollower, secondFollower])).resolves.toEqual([7, 7])
    expect(storedSession).toBe(twiceRotatedSession)
    expect(bSaveCalls).toBe(1)
    expect(refreshTokens).toEqual([
      expiredSession.refreshToken,
      rotatedSession.refreshToken,
    ])
  })

  it('preserves a terminal refresh failure for every follower and handles it once', async () => {
    const coordinator = new SessionRefreshCoordinator()
    let expiredRequests = 0
    let markBothExpired!: () => void
    const bothExpired = new Promise<void>((resolve) => {
      markBothExpired = resolve
    })
    let markRefreshStarted!: () => void
    const refreshStarted = new Promise<void>((resolve) => {
      markRefreshStarted = resolve
    })
    let releaseRefresh!: () => void
    const refreshReleased = new Promise<void>((resolve) => {
      releaseRefresh = resolve
    })
    const authenticationFailure = vi.fn()
    const save = vi.fn()
    let refreshRequests = 0
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          refreshRequests += 1
          markRefreshStarted()
          await refreshReleased
          return {
            statusCode: 401,
            data: { error: { code: 'ACCESS_REVOKED', retryable: false } } as T,
          }
        }
        expiredRequests += 1
        if (expiredRequests === 2) markBothExpired()
        await bothExpired
        return {
          statusCode: 401,
          data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
        }
      },
    }
    const sessions: SessionAccessPort = {
      load: vi.fn().mockResolvedValue(expiredSession),
      save,
      clear: vi.fn(),
    }
    const createClient = () => new FitnessApiClient(
      'http://127.0.0.1:8080',
      transport,
      sessions,
      authenticationFailure,
      coordinator,
    )
    const ownerRequest = createClient().getProfileVersion()
    const followerRequest = createClient().getEquipmentVersion()
    await refreshStarted
    await Promise.resolve()
    releaseRefresh()

    const [ownerResult, followerResult] = await Promise.allSettled([
      ownerRequest,
      followerRequest,
    ])
    expect(ownerResult.status).toBe('rejected')
    expect(followerResult.status).toBe('rejected')
    if (ownerResult.status !== 'rejected' || followerResult.status !== 'rejected') {
      throw new Error('terminal refresh requests must reject')
    }
    expect(ownerResult.reason).toMatchObject({ code: 'ACCESS_REVOKED' })
    expect(followerResult.reason).toBe(ownerResult.reason)
    expect(authenticationFailure).toHaveBeenCalledOnce()
    expect(authenticationFailure).toHaveBeenCalledWith('ACCESS_REVOKED')
    expect(refreshRequests).toBe(1)
    expect(save).not.toHaveBeenCalled()
  })

  it('invalidates a handled terminal failure when another clear occurs during its handler', async () => {
    const coordinator = new SessionRefreshCoordinator()
    let markHandlerStarted!: () => void
    const handlerStarted = new Promise<void>((resolve) => {
      markHandlerStarted = resolve
    })
    let releaseHandler!: () => void
    const handlerReleased = new Promise<void>((resolve) => {
      releaseHandler = resolve
    })
    const authenticationFailure = vi.fn(async () => {
      markHandlerStarted()
      await handlerReleased
    })
    const save = vi.fn()
    const transport: TransportPort = {
      async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
        if (request.url.endsWith('/api/v1/auth/refresh')) {
          return {
            statusCode: 401,
            data: { error: { code: 'ACCESS_REVOKED', retryable: false } } as T,
          }
        }
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
        load: vi.fn().mockResolvedValue(expiredSession),
        save,
        clear: vi.fn(),
      },
      authenticationFailure,
      coordinator,
    )

    const request = client.getProfileVersion()
    await handlerStarted
    coordinator.clear()
    releaseHandler()

    await expect(request).rejects.toBeInstanceOf(SessionRefreshInvalidatedError)
    expect(authenticationFailure).toHaveBeenCalledOnce()
    expect(authenticationFailure).toHaveBeenCalledWith('ACCESS_REVOKED')
    expect(save).not.toHaveBeenCalled()
  })

  it('stops a cyclic known lineage without purging the current account', async () => {
    const coordinator = new SessionRefreshCoordinator()
    const sameTokenSession: Session = {
      accessToken: 'same-token-access-redacted',
      refreshToken: expiredSession.refreshToken,
      expiresAt: '2026-08-28T02:00:00Z',
    }
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(sameTokenSession))
    const authenticationFailure = vi.fn()
    const transport: TransportPort = {
      async request<T>(): Promise<TransportResponse<T>> {
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
        load: vi.fn().mockResolvedValue(expiredSession),
        save: vi.fn(),
        clear: vi.fn(),
      },
      authenticationFailure,
      coordinator,
    )

    await expect(client.getProfileVersion()).rejects.toBeInstanceOf(
      SessionRefreshInvalidatedError,
    )
    expect(authenticationFailure).not.toHaveBeenCalled()
  })

  it.each(['resolve', 'reject'] as const)(
    'does not save or retry an old-generation refresh that later %s',
    async (outcome) => {
      const coordinator = new SessionRefreshCoordinator()
      let releaseRefresh!: () => void
      const refreshReleased = new Promise<void>((resolve) => {
        releaseRefresh = resolve
      })
      let markRefreshStarted!: () => void
      const refreshStarted = new Promise<void>((resolve) => {
        markRefreshStarted = resolve
      })
      let originalRequests = 0
      let retriedRequests = 0
      const transport: TransportPort = {
        async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
          if (request.url.endsWith('/api/v1/auth/refresh')) {
            markRefreshStarted()
            await refreshReleased
            if (outcome === 'reject') throw new Error('stale transport failure')
            return { statusCode: 200, data: { data: rotatedSession } as T }
          }
          if (request.headers.Authorization === `Bearer ${rotatedSession.accessToken}`) {
            retriedRequests += 1
            return { statusCode: 200, data: { data: { version: 7 } } as T }
          }
          originalRequests += 1
          return {
            statusCode: 401,
            data: { error: { code: 'AUTHENTICATION_REQUIRED' } } as T,
          }
        },
      }
      const save = vi.fn()
      const client = new FitnessApiClient(
        'http://127.0.0.1:8080',
        transport,
        {
          load: vi.fn().mockResolvedValue(expiredSession),
          save,
          clear: vi.fn(),
        },
        undefined,
        coordinator,
      )

      const request = client.getProfileVersion()
      const requestResult = expect(request).rejects.toBeInstanceOf(
        SessionRefreshInvalidatedError,
      )
      await refreshStarted
      coordinator.clear()
      releaseRefresh()

      await requestResult
      expect(save).not.toHaveBeenCalled()
      expect(originalRequests).toBe(1)
      expect(retriedRequests).toBe(0)
    },
  )

  it('clears shared rotations after logout', async () => {
    const coordinator = new SessionRefreshCoordinator()
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(rotatedSession))
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      { request: vi.fn().mockResolvedValue({ statusCode: 200, data: { data: {} } }) },
      {
        load: vi.fn().mockResolvedValue(rotatedSession),
        save: vi.fn(),
        clear: vi.fn(),
      },
      undefined,
      coordinator,
    )

    await client.logout()

    const refreshAfterLogout = vi.fn().mockResolvedValue(rotatedSession)
    await coordinator.recover(expiredSession, refreshAfterLogout)
    expect(refreshAfterLogout).toHaveBeenCalledOnce()
  })

  it('clears shared rotations when remote logout fails', async () => {
    const coordinator = new SessionRefreshCoordinator()
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(rotatedSession))
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      { request: vi.fn().mockResolvedValue({ statusCode: 503, data: {} }) },
      {
        load: vi.fn().mockResolvedValue(rotatedSession),
        save: vi.fn(),
        clear: vi.fn(),
      },
      undefined,
      coordinator,
    )

    await expect(client.logout()).rejects.toMatchObject({ code: 'INTERNAL_ERROR' })

    const refreshAfterLogout = vi.fn().mockResolvedValue(rotatedSession)
    await coordinator.recover(expiredSession, refreshAfterLogout)
    expect(refreshAfterLogout).toHaveBeenCalledOnce()
  })

  it('clears shared rotations on a terminal authentication response', async () => {
    const coordinator = new SessionRefreshCoordinator()
    await coordinator.recover(expiredSession, vi.fn().mockResolvedValue(rotatedSession))
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      {
        request: vi.fn().mockResolvedValue({
          statusCode: 401,
          data: { error: { code: 'ACCESS_REVOKED', retryable: false } },
        }),
      },
      {
        load: vi.fn().mockResolvedValue(rotatedSession),
        save: vi.fn(),
        clear: vi.fn(),
      },
      vi.fn(),
      coordinator,
    )

    await expect(client.getProfileVersion()).rejects.toMatchObject({ code: 'ACCESS_REVOKED' })

    const refreshAfterRevocation = vi.fn().mockResolvedValue(rotatedSession)
    await coordinator.recover(expiredSession, refreshAfterRevocation)
    expect(refreshAfterRevocation).toHaveBeenCalledOnce()
  })
})
