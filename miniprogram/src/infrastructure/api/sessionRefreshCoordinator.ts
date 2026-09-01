interface RefreshableSession {
  readonly refreshToken: string
}

type RefreshSession<Session extends RefreshableSession> = (
  failedSession: Session,
) => Promise<Session>

type PersistSession<Session extends RefreshableSession> = (
  refreshedSession: Session,
) => Promise<void>

export interface SessionRefreshFailureLease {
  assertCurrent(): void
}

type HandleRefreshFailure = (
  error: unknown,
) => Promise<SessionRefreshFailureLease | null>

export const MAX_SESSION_REFRESH_LINEAGE_HOPS = 8

export class SessionRefreshInvalidatedError extends Error {
  readonly code = 'SESSION_REFRESH_INVALIDATED'
  readonly retryable = false

  constructor() {
    super('Session refresh was invalidated by a session state change')
    this.name = 'SessionRefreshInvalidatedError'
  }
}

export class HandledSessionRefreshFailureError extends Error {
  constructor(
    readonly failure: unknown,
    private readonly lease: SessionRefreshFailureLease,
  ) {
    super('Session refresh failure was handled by its owner')
    this.name = 'HandledSessionRefreshFailureError'
  }

  assertCurrent(): void {
    this.lease.assertCurrent()
  }
}

/**
 * Coordinates access-token recovery for clients that share the same session store.
 * The coordinator is intentionally transport-agnostic so feature-specific API
 * clients can share refresh state without depending on one another.
 */
export class SessionRefreshCoordinator {
  private readonly refreshesInFlight = new Map<string, Promise<RefreshableSession>>()
  private readonly rotatedSessions = new Map<string, RefreshableSession>()
  private generation = 0
  private rotationRevision = 0

  captureRotationRevision(): number {
    return this.rotationRevision
  }

  hasRotationAdvancedSince(observedRevision: number): boolean {
    return this.rotationRevision > observedRevision
  }

  captureFailureDeliveryLease(): SessionRefreshFailureLease {
    const expectedGeneration = this.generation
    return {
      assertCurrent: () => this.assertActiveGeneration(expectedGeneration),
    }
  }

  recover<Session extends RefreshableSession>(
    failedSession: Session,
    refresh: RefreshSession<Session>,
    observedRevision = this.rotationRevision,
    persist?: PersistSession<Session>,
    handleFailure?: HandleRefreshFailure,
  ): Promise<Session> {
    const recoveryGeneration = this.generation
    const sourceRefreshToken = failedSession.refreshToken
    const rotatedSession = this.rotatedSessions.get(sourceRefreshToken) as Session | undefined
    if (rotatedSession) {
      return this.resolveLatestRotation(
        sourceRefreshToken,
        rotatedSession,
        recoveryGeneration,
      )
    }

    const existingRefresh = this.refreshesInFlight.get(sourceRefreshToken) as
      | Promise<Session>
      | undefined
    if (existingRefresh) return existingRefresh

    if (this.hasRotationAdvancedSince(observedRevision)) {
      return Promise.reject(new SessionRefreshInvalidatedError())
    }

    const refreshGeneration = recoveryGeneration
    let refreshInFlight!: Promise<Session>
    refreshInFlight = Promise.resolve()
      .then(() => refresh(failedSession))
      .then(async (refreshedSession) => {
        this.assertActiveGeneration(refreshGeneration)
        await persist?.(refreshedSession)
        this.assertActiveGeneration(refreshGeneration)
        this.rememberRotation(sourceRefreshToken, refreshedSession)
        return refreshedSession
      })
      .catch(async (error: unknown) => {
        this.assertActiveGeneration(refreshGeneration)
        // The single owner may intentionally clear this coordinator while
        // handling a terminal auth failure. Mark that expected transition so
        // every joiner receives the owner-handled cause instead of a replacement
        // invalidation error.
        const deliveryLease = await handleFailure?.(error)
        if (deliveryLease) {
          deliveryLease.assertCurrent()
          throw new HandledSessionRefreshFailureError(error, deliveryLease)
        }
        this.assertActiveGeneration(refreshGeneration)
        throw error
      })
      .finally(() => {
        if (this.refreshesInFlight.get(sourceRefreshToken) === refreshInFlight) {
          this.refreshesInFlight.delete(sourceRefreshToken)
        }
      })
      .then(
        (refreshedSession) => {
          this.assertActiveGeneration(refreshGeneration)
          return refreshedSession
        },
        (error: unknown) => {
          if (error instanceof HandledSessionRefreshFailureError) {
            error.assertCurrent()
            throw error
          }
          this.assertActiveGeneration(refreshGeneration)
          throw error
        },
      )
    this.refreshesInFlight.set(sourceRefreshToken, refreshInFlight)
    return refreshInFlight
  }

  resolveKnownLatest<Session extends RefreshableSession>(
    failedSession: Session,
  ): Promise<Session | null> {
    const recoveryGeneration = this.generation
    const sourceRefreshToken = failedSession.refreshToken
    const rotatedSession = this.rotatedSessions.get(sourceRefreshToken) as Session | undefined
    if (rotatedSession) {
      return this.resolveLatestRotation(
        sourceRefreshToken,
        rotatedSession,
        recoveryGeneration,
      )
    }

    const refreshInFlight = this.refreshesInFlight.get(sourceRefreshToken) as
      | Promise<Session>
      | undefined
    if (refreshInFlight) {
      return this.resolveInFlightRotation(
        sourceRefreshToken,
        refreshInFlight,
        recoveryGeneration,
      )
    }

    return Promise.resolve().then(() => {
      this.assertActiveGeneration(recoveryGeneration)
      return null
    })
  }

  hasKnownRecovery(failedSession: RefreshableSession): boolean {
    return this.rotatedSessions.has(failedSession.refreshToken)
      || this.refreshesInFlight.has(failedSession.refreshToken)
  }

  clear(): void {
    this.generation += 1
    this.rotationRevision += 1
    this.refreshesInFlight.clear()
    this.rotatedSessions.clear()
  }

  private assertActiveGeneration(refreshGeneration: number): void {
    if (this.generation !== refreshGeneration) {
      throw new SessionRefreshInvalidatedError()
    }
  }

  private async resolveLatestRotation<Session extends RefreshableSession>(
    sourceRefreshToken: string,
    initialSession: Session,
    recoveryGeneration: number,
  ): Promise<Session> {
    let latestSession = initialSession
    let refreshToken = initialSession.refreshToken
    const visitedRefreshTokens = new Set([sourceRefreshToken])

    while (!visitedRefreshTokens.has(refreshToken)) {
      visitedRefreshTokens.add(refreshToken)
      this.assertActiveGeneration(recoveryGeneration)

      const descendantRefresh = this.refreshesInFlight.get(refreshToken) as
        | Promise<Session>
        | undefined
      if (descendantRefresh) {
        latestSession = await descendantRefresh
        this.assertActiveGeneration(recoveryGeneration)
        refreshToken = latestSession.refreshToken
        continue
      }

      const rotatedSession = this.rotatedSessions.get(refreshToken) as Session | undefined
      if (!rotatedSession) break
      latestSession = rotatedSession
      refreshToken = rotatedSession.refreshToken
    }

    // Preserve asynchronous delivery so clear() can invalidate a cache hit
    // before an already-rotated session is handed back to its caller.
    await Promise.resolve()
    this.assertActiveGeneration(recoveryGeneration)
    return latestSession
  }

  private async resolveInFlightRotation<Session extends RefreshableSession>(
    sourceRefreshToken: string,
    refreshInFlight: Promise<Session>,
    recoveryGeneration: number,
  ): Promise<Session> {
    try {
      const refreshedSession = await refreshInFlight
      this.assertActiveGeneration(recoveryGeneration)
      return this.resolveLatestRotation(
        sourceRefreshToken,
        refreshedSession,
        recoveryGeneration,
      )
    } catch (error) {
      if (error instanceof HandledSessionRefreshFailureError) {
        error.assertCurrent()
        throw error
      }
      this.assertActiveGeneration(recoveryGeneration)
      throw error
    }
  }

  private rememberRotation(
    sourceRefreshToken: string,
    refreshedSession: RefreshableSession,
  ): void {
    this.rotationRevision += 1
    for (const [previousRefreshToken, currentSession] of this.rotatedSessions) {
      if (currentSession.refreshToken === sourceRefreshToken) {
        this.rotatedSessions.set(previousRefreshToken, refreshedSession)
      }
    }
    this.rotatedSessions.set(sourceRefreshToken, refreshedSession)

    while (this.rotatedSessions.size > MAX_SESSION_REFRESH_LINEAGE_HOPS) {
      const oldestRefreshToken = this.rotatedSessions.keys().next().value
      if (typeof oldestRefreshToken !== 'string') return
      this.rotatedSessions.delete(oldestRefreshToken)
    }
  }
}
