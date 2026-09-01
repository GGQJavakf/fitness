import type { UserScopedLocalDataPort } from './localPrivacyLifecycle'

export interface Session {
  accessToken: string
  refreshToken: string
  expiresAt: string
}

export type AppDestination = 'LOGIN' | 'ONBOARDING' | 'PLAN' | 'HOME' | 'WORKOUT_SESSION'

export interface StartupGenerationFence {
  capture(): number
  assertCurrent(generation: number): void
  begin?(): number
  invalidate?(): void
}

export interface StartupPorts {
  sessionStore: {
    load(): Promise<Session | null>
    save(session: Session): Promise<void>
    clear(): Promise<void>
  }
  wechatLogin: { getCode(): Promise<string> }
  auth: { login(code: string): Promise<Session> }
  workout: {
    hasActive(): Promise<boolean>
    getStartupState?(): Promise<'NONE' | 'ACTIVE' | 'RECOVERY_REQUIRED'>
  }
  profile: { exists(): Promise<boolean> }
  plan: { hasActivePlan(): Promise<boolean> }
  navigation: { replace(destination: AppDestination): Promise<void> | void }
  localUserData?: UserScopedLocalDataPort & {
    activate(): void
    isClearVerified?(): boolean
  }
  userGeneration?: StartupGenerationFence
  clearUserState?(): void
  completeUserStateClear?(): void
}

export function createStartupUseCases(ports: StartupPorts) {
  function assertCurrentGeneration(generation: number | undefined): void {
    if (generation !== undefined) ports.userGeneration?.assertCurrent(generation)
  }

  async function awaitCurrent<T>(
    generation: number | undefined,
    operation: () => Promise<T>,
  ): Promise<T> {
    assertCurrentGeneration(generation)
    try {
      const result = await operation()
      assertCurrentGeneration(generation)
      return result
    } catch (error) {
      assertCurrentGeneration(generation)
      throw error
    }
  }

  async function resolveDestination(
    generation: number | undefined,
  ): Promise<AppDestination> {
    const workoutState = ports.workout.getStartupState
      ? await awaitCurrent(generation, () => ports.workout.getStartupState!())
      : await awaitCurrent(generation, () => ports.workout.hasActive()) ? 'ACTIVE' : 'NONE'
    if (workoutState === 'ACTIVE' || workoutState === 'RECOVERY_REQUIRED') {
      return 'WORKOUT_SESSION'
    }
    if (!await awaitCurrent(generation, () => ports.profile.exists())) {
      return 'ONBOARDING'
    }
    return await awaitCurrent(generation, () => ports.plan.hasActivePlan())
      ? 'PLAN'
      : 'ONBOARDING'
  }

  async function navigate(
    destination: AppDestination,
    generation: number | undefined,
  ): Promise<AppDestination> {
    await awaitCurrent(generation, async () => ports.navigation.replace(destination))
    return destination
  }

  async function ensureNullSessionAdmissionIsCleared(): Promise<void> {
    if (ports.localUserData?.isClearVerified?.() === true) return

    let memoryClearError: unknown
    if (ports.clearUserState) {
      try {
        ports.clearUserState()
      } catch (error) {
        memoryClearError = error
      }
    } else {
      ports.userGeneration?.invalidate?.()
    }

    const cleanup = ports.localUserData
      ? ports.localUserData.purge('AUTHENTICATION_EXPIRED')
      : ports.sessionStore.clear()
    const cleanupGeneration = ports.userGeneration?.capture()
    let cleanupError: unknown
    try {
      await cleanup
      assertCurrentGeneration(cleanupGeneration)
    } catch (error) {
      assertCurrentGeneration(cleanupGeneration)
      cleanupError = error
    }

    if (memoryClearError !== undefined) throw memoryClearError
    if (cleanupError !== undefined) throw cleanupError
    ports.completeUserStateClear?.()
  }

  return {
    async start(): Promise<AppDestination> {
      const generation = ports.userGeneration?.capture()
      const session = await awaitCurrent(generation, () => ports.sessionStore.load())
      const destination = session ? await resolveDestination(generation) : 'LOGIN'
      return navigate(destination, generation)
    },

    async login(): Promise<AppDestination> {
      let generation: number | undefined
      try {
        // A cleared lifecycle permits this read as null while a failed/in-flight purge rejects.
        // Check admission before minting a login generation so a blocked account transition
        // retains ownership and can finish its purge/remote logout/navigation chain.
        const admissionGeneration = ports.userGeneration?.capture()
        const admissionSession = await awaitCurrent(
          admissionGeneration,
          () => ports.sessionStore.load(),
        )
        if (!admissionSession) await ensureNullSessionAdmissionIsCleared()
        generation = ports.userGeneration?.begin?.() ?? ports.userGeneration?.capture()
        const code = await awaitCurrent(generation, () => ports.wechatLogin.getCode())
        const session = await awaitCurrent(generation, () => ports.auth.login(code))
        assertCurrentGeneration(generation)
        ports.localUserData?.activate()
        assertCurrentGeneration(generation)
        await awaitCurrent(generation, () => ports.sessionStore.save(session))
        const destination = await resolveDestination(generation)
        return navigate(destination, generation)
      } catch (error) {
        const currentGeneration = ports.userGeneration?.capture()
        const rollbackStillOwned = !ports.userGeneration
          || (generation !== undefined && currentGeneration === generation)
        if (rollbackStillOwned) {
          let memoryClearError: unknown
          if (ports.clearUserState) {
            try {
              ports.clearUserState()
            } catch (clearError) {
              memoryClearError = clearError
            }
          } else {
            ports.userGeneration?.invalidate?.()
          }
          const purge = ports.localUserData?.purge('LOGIN_ROLLBACK')
          const rollbackGeneration = ports.userGeneration?.capture()
          let purgeSucceeded = true
          try {
            await purge
            assertCurrentGeneration(rollbackGeneration)
          } catch {
            // The lifecycle remains blocked after a failed purge; preserve the login error.
            assertCurrentGeneration(rollbackGeneration)
            purgeSucceeded = false
          }
          if (memoryClearError !== undefined) throw memoryClearError
          if (purgeSucceeded) ports.completeUserStateClear?.()
        }
        throw error
      }
    },

    async authenticationExpired(): Promise<AppDestination> {
      let memoryClearError: unknown
      if (ports.clearUserState) {
        try {
          ports.clearUserState()
        } catch (error) {
          memoryClearError = error
        }
      } else {
        ports.userGeneration?.invalidate?.()
      }
      const cleanup = ports.localUserData
        ? ports.localUserData.purge('AUTHENTICATION_EXPIRED')
        : ports.sessionStore.clear()
      const generation = ports.userGeneration?.capture()
      let cleanupError: unknown
      try {
        await cleanup
        assertCurrentGeneration(generation)
      } catch (error) {
        assertCurrentGeneration(generation)
        cleanupError = error
      }
      const destination = await navigate('LOGIN', generation)
      if (memoryClearError !== undefined) throw memoryClearError
      if (cleanupError !== undefined) throw cleanupError
      ports.completeUserStateClear?.()
      return destination
    },
  }
}
