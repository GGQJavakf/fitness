import type { AppDestination } from './onboarding'
import type { DeletionStatus } from './privacy'

export type UserScopedLocalDataPurgeReason =
  | 'LOGOUT'
  | 'ACCOUNT_SWITCH'
  | 'ACCESS_REVOKED'
  | 'AUTHENTICATION_EXPIRED'
  | 'ACCOUNT_DELETED'
  | 'LOGIN_ROLLBACK'

export interface UserScopedLocalDataPort {
  purge(reason: UserScopedLocalDataPurgeReason): Promise<void>
}

export interface AccountLifecycleOptions {
  remote: {
    logout(): Promise<void>
    prepareLogout?(): () => Promise<void>
  }
  localData: UserScopedLocalDataPort
  navigation: { replaceLogin(): Promise<void> | void }
  login(): Promise<AppDestination>
  clearMemory?(): void
  completeMemoryClear?(): void
  userGeneration?: {
    capture(): number
    assertCurrent(generation: number): void
  }
}

export interface AccountLogoutResult {
  remoteLogoutSucceeded: boolean
}

export interface AccountSwitchResult extends AccountLogoutResult {
  destination: AppDestination
}

export type TerminalAuthenticationFailure = 'ACCESS_REVOKED'

export class LocalUserDataCleanupError extends Error {
  constructor() {
    super('本机用户数据未能完全清理')
    this.name = 'LocalUserDataCleanupError'
  }
}

export function createAccountLifecycleUseCases(options: AccountLifecycleOptions) {
  function assertCurrent(generation: number | undefined): void {
    if (generation !== undefined) options.userGeneration?.assertCurrent(generation)
  }

  async function awaitCurrent<T>(
    generation: number | undefined,
    operation: () => Promise<T>,
  ): Promise<T> {
    assertCurrent(generation)
    try {
      const result = await operation()
      assertCurrent(generation)
      return result
    } catch (error) {
      assertCurrent(generation)
      throw error
    }
  }

  async function returnToLogin(generation: number | undefined): Promise<void> {
    await awaitCurrent(generation, async () => options.navigation.replaceLogin())
  }

  function startLocalCleanup(reason: UserScopedLocalDataPurgeReason): {
    readonly purge: Promise<void>
    readonly generation: number | undefined
    readonly memoryClearError: unknown
  } {
    let memoryClearError: unknown
    try {
      options.clearMemory?.()
    } catch (error) {
      memoryClearError = error
    }
    // purge() blocks storage and invalidates synchronously before any remote await.
    const purge = options.localData.purge(reason)
    return {
      purge,
      generation: options.userGeneration?.capture(),
      memoryClearError,
    }
  }

  async function purgeAndReturnToLogin(
    reason: UserScopedLocalDataPurgeReason,
    notifyRemote: boolean,
  ): Promise<AccountLogoutResult> {
    const sourceGeneration = options.userGeneration?.capture()
    let remoteLogoutSucceeded = true
    let preparedRemoteLogout: (() => Promise<void>) | undefined
    if (notifyRemote) {
      try {
        preparedRemoteLogout = options.remote.prepareLogout
          ? options.remote.prepareLogout()
          : () => options.remote.logout()
        assertCurrent(sourceGeneration)
      } catch {
        assertCurrent(sourceGeneration)
        remoteLogoutSucceeded = false
      }
    }

    const cleanup = startLocalCleanup(reason)
    let localCleanupError: unknown
    try {
      await awaitCurrent(cleanup.generation, () => cleanup.purge)
    } catch (error) {
      assertCurrent(cleanup.generation)
      localCleanupError = error
    }

    if (preparedRemoteLogout) {
      try {
        await awaitCurrent(cleanup.generation, preparedRemoteLogout)
      } catch {
        assertCurrent(cleanup.generation)
        remoteLogoutSucceeded = false
      }
    }

    await returnToLogin(cleanup.generation)
    if (cleanup.memoryClearError !== undefined) throw cleanup.memoryClearError
    if (localCleanupError !== undefined) throw new LocalUserDataCleanupError()
    options.completeMemoryClear?.()
    return { remoteLogoutSucceeded }
  }

  async function purgeTerminalAndReturnToLogin(
    reason: Extract<UserScopedLocalDataPurgeReason, 'ACCESS_REVOKED' | 'ACCOUNT_DELETED'>,
  ): Promise<AccountLogoutResult> {
    const cleanup = startLocalCleanup(reason)
    let localCleanupError: unknown
    try {
      await awaitCurrent(cleanup.generation, () => cleanup.purge)
    } catch (error) {
      assertCurrent(cleanup.generation)
      localCleanupError = error
    }
    if (cleanup.memoryClearError !== undefined) throw cleanup.memoryClearError
    if (localCleanupError !== undefined) throw new LocalUserDataCleanupError()
    await returnToLogin(cleanup.generation)
    options.completeMemoryClear?.()
    return { remoteLogoutSucceeded: true }
  }

  return {
    logout(): Promise<AccountLogoutResult> {
      return purgeAndReturnToLogin('LOGOUT', true)
    },

    async switchAccount(): Promise<AccountSwitchResult> {
      const result = await purgeAndReturnToLogin('ACCOUNT_SWITCH', true)
      // login() owns the new account transition and intentionally mints its own
      // generation. Wrapping it in the cleanup generation would reject every
      // successful generation-aware login after it advances the lease.
      return {
        ...result,
        destination: await options.login(),
      }
    },

    handleAccessRevoked(status: DeletionStatus): Promise<AccountLogoutResult> {
      return purgeTerminalAndReturnToLogin(
        status === 'COMPLETED' ? 'ACCOUNT_DELETED' : 'ACCESS_REVOKED',
      )
    },

    handleTerminalAuthenticationFailure(
      _failure: TerminalAuthenticationFailure,
    ): Promise<AccountLogoutResult> {
      return purgeTerminalAndReturnToLogin('ACCESS_REVOKED')
    },
  }
}
