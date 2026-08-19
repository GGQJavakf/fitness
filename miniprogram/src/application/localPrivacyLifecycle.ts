import type { AppDestination } from './onboarding'
import type { DeletionStatus } from './privacy'

export type UserScopedLocalDataPurgeReason =
  | 'LOGOUT'
  | 'ACCOUNT_SWITCH'
  | 'ACCESS_REVOKED'
  | 'ACCOUNT_DELETED'
  | 'LOGIN_ROLLBACK'

export interface UserScopedLocalDataPort {
  purge(reason: UserScopedLocalDataPurgeReason): Promise<void>
}

export interface AccountLifecycleOptions {
  remote: { logout(): Promise<void> }
  localData: UserScopedLocalDataPort
  navigation: { replaceLogin(): Promise<void> | void }
  login(): Promise<AppDestination>
  clearMemory?(): void
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
  async function returnToLogin(): Promise<void> {
    await options.navigation.replaceLogin()
  }

  async function purgeAndReturnToLogin(
    reason: UserScopedLocalDataPurgeReason,
    notifyRemote: boolean,
  ): Promise<AccountLogoutResult> {
    options.clearMemory?.()
    let remoteLogoutSucceeded = true
    if (notifyRemote) {
      try {
        await options.remote.logout()
      } catch {
        remoteLogoutSucceeded = false
      }
    }

    try {
      await options.localData.purge(reason)
    } catch {
      throw new LocalUserDataCleanupError()
    } finally {
      await returnToLogin()
    }
    return { remoteLogoutSucceeded }
  }

  async function purgeTerminalAndReturnToLogin(
    reason: Extract<UserScopedLocalDataPurgeReason, 'ACCESS_REVOKED' | 'ACCOUNT_DELETED'>,
  ): Promise<AccountLogoutResult> {
    options.clearMemory?.()
    try {
      await options.localData.purge(reason)
    } catch {
      throw new LocalUserDataCleanupError()
    }
    await returnToLogin()
    return { remoteLogoutSucceeded: true }
  }

  return {
    logout(): Promise<AccountLogoutResult> {
      return purgeAndReturnToLogin('LOGOUT', true)
    },

    async switchAccount(): Promise<AccountSwitchResult> {
      const result = await purgeAndReturnToLogin('ACCOUNT_SWITCH', true)
      try {
        return {
          ...result,
          destination: await options.login(),
        }
      } catch (error) {
        await returnToLogin()
        throw error
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
