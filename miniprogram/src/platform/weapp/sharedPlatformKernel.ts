import { ApplicationError } from '../../application/errors'
import { SessionRefreshCoordinator } from '../../infrastructure/api/sessionRefreshCoordinator'
import {
  createWeappUserScopedDataLifecycle,
  type WeappUserScopedDataLifecycle,
} from './WechatUserScopedDataLifecycle'

export type ClearUserScopedState = () => void

export interface UserGenerationLease {
  capture(): number
  assertCurrent(generation: number): void
  begin(): number
  invalidate(): void
}

export class UserGenerationInvalidatedError extends ApplicationError {
  constructor() {
    super('AUTHENTICATION_REQUIRED', '账号状态已更新，本次旧请求结果已失效')
    this.name = 'UserGenerationInvalidatedError'
  }
}

export class UserScopedStateClearError extends ApplicationError {
  readonly failureCount: number

  constructor(failureCount: number) {
    super('AUTHENTICATION_REQUIRED', '本机账号状态未能完全清理，请重新登录后重试')
    this.name = 'UserScopedStateClearError'
    this.failureCount = failureCount
  }
}

export class UserScopedStateBlockedError extends ApplicationError {
  constructor() {
    super('AUTHENTICATION_REQUIRED', '本机账号正在安全切换，请重新登录后重试')
    this.name = 'UserScopedStateBlockedError'
  }
}

export interface UserScopedStateRegistry {
  register(clear: ClearUserScopedState): () => void
  clearAll(): void
  completeClear(): void
  isClearVerified(): boolean
  assertCanUse(): void
  assertCanReadClearedSession(): void
  assertCanActivate(): void
  markActivated(): void
}

export interface SharedPlatformKernel {
  readonly localUserData: WeappUserScopedDataLifecycle
  readonly userScopedState: UserScopedStateRegistry
  readonly userGeneration: UserGenerationLease
  readonly sessionRefresh: SessionRefreshCoordinator
}

let sharedPlatformKernel: SharedPlatformKernel | undefined

export function initializeSharedPlatformKernel(): SharedPlatformKernel {
  return getSharedPlatformKernel()
}

export function getSharedPlatformKernel(): SharedPlatformKernel {
  if (!sharedPlatformKernel) {
    const userGeneration = createUserGenerationLease()
    const userScopedState = createUserScopedStateRegistry(userGeneration)
    const sessionRefresh = new SessionRefreshCoordinator()
    const localUserData = createGenerationBoundUserScopedDataLifecycle(
      createWeappUserScopedDataLifecycle(),
      userGeneration,
      userScopedState,
    )
    userScopedState.register(() => sessionRefresh.clear())
    sharedPlatformKernel = {
      localUserData,
      userScopedState,
      userGeneration,
      sessionRefresh,
    }
  }
  return sharedPlatformKernel
}

export function createGenerationBoundUserScopedDataLifecycle(
  localUserData: WeappUserScopedDataLifecycle,
  userGeneration: UserGenerationLease,
  userScopedState?: Pick<
    UserScopedStateRegistry,
    | 'assertCanUse'
    | 'assertCanReadClearedSession'
    | 'assertCanActivate'
    | 'isClearVerified'
    | 'markActivated'
  >,
): WeappUserScopedDataLifecycle {
  return {
    async runUserOperation<T>(operation: () => Promise<T>): Promise<T> {
      userScopedState?.assertCanUse()
      const generation = userGeneration.capture()
      try {
        const result = await localUserData.runUserOperation(async () => {
          userScopedState?.assertCanUse()
          userGeneration.assertCurrent(generation)
          try {
            const value = await operation()
            userGeneration.assertCurrent(generation)
            return value
          } catch (error) {
            userGeneration.assertCurrent(generation)
            throw error
          }
        })
        userGeneration.assertCurrent(generation)
        return result
      } catch (error) {
        userGeneration.assertCurrent(generation)
        throw error
      }
    },

    async runClearedSessionRead<T>(
      operation: () => Promise<T | null>,
    ): Promise<T | null> {
      userScopedState?.assertCanReadClearedSession()
      const generation = userGeneration.capture()
      try {
        const result = await localUserData.runClearedSessionRead(async () => {
          userScopedState?.assertCanReadClearedSession()
          userGeneration.assertCurrent(generation)
          try {
            const value = await operation()
            userGeneration.assertCurrent(generation)
            return value
          } catch (error) {
            userGeneration.assertCurrent(generation)
            throw error
          }
        })
        userGeneration.assertCurrent(generation)
        return result
      } catch (error) {
        userGeneration.assertCurrent(generation)
        throw error
      }
    },

    isClearVerified: () => localUserData.isClearVerified()
      && (userScopedState?.isClearVerified() ?? true),

    purge: (reason) => {
      userGeneration.invalidate()
      return localUserData.purge(reason)
    },
    activate: () => {
      userScopedState?.assertCanActivate()
      localUserData.activate()
      userScopedState?.markActivated()
    },
  }
}

export function createUserGenerationLease(): UserGenerationLease {
  let generation = 0
  return {
    capture: () => generation,

    begin(): number {
      generation += 1
      return generation
    },

    assertCurrent(expectedGeneration): void {
      if (generation !== expectedGeneration) {
        throw new UserGenerationInvalidatedError()
      }
    },

    invalidate(): void {
      generation += 1
    },
  }
}

export function createUserScopedStateRegistry(
  userGeneration: UserGenerationLease,
): UserScopedStateRegistry {
  const observers = new Set<ClearUserScopedState>()
  let failedObserverCount = 0
  let blocked = false
  let clearIncomplete = false
  let clearVerified = false
  return {
    register(clear): () => void {
      observers.add(clear)
      return () => observers.delete(clear)
    },

    clearAll(): void {
      blocked = true
      clearIncomplete = true
      clearVerified = false
      userGeneration.invalidate()
      let failures = 0
      for (const clear of observers) {
        try {
          clear()
        } catch {
          failures += 1
        }
      }
      failedObserverCount = failures
      if (failures > 0) throw new UserScopedStateClearError(failures)
    },

    completeClear(): void {
      if (failedObserverCount > 0) {
        throw new UserScopedStateClearError(failedObserverCount)
      }
      clearIncomplete = false
      clearVerified = true
    },

    isClearVerified(): boolean {
      return blocked
        && !clearIncomplete
        && failedObserverCount === 0
        && clearVerified
    },

    assertCanUse(): void {
      if (!blocked) return
      if (failedObserverCount > 0) {
        throw new UserScopedStateClearError(failedObserverCount)
      }
      throw new UserScopedStateBlockedError()
    },

    assertCanReadClearedSession(): void {
      if (failedObserverCount > 0) {
        throw new UserScopedStateClearError(failedObserverCount)
      }
      if (clearIncomplete) throw new UserScopedStateBlockedError()
    },

    assertCanActivate(): void {
      if (failedObserverCount > 0) {
        throw new UserScopedStateClearError(failedObserverCount)
      }
      if (clearIncomplete) throw new UserScopedStateBlockedError()
    },

    markActivated(): void {
      if (failedObserverCount > 0) {
        throw new UserScopedStateClearError(failedObserverCount)
      }
      if (clearIncomplete) throw new UserScopedStateBlockedError()
      blocked = false
      clearVerified = false
    },
  }
}
