import Taro from '@tarojs/taro'

import type {
  UserScopedLocalDataPort,
  UserScopedLocalDataPurgeReason,
} from '../../application/localPrivacyLifecycle'

export const WEAPP_SESSION_STORAGE_KEY = 'fitness.session.v1'
export const WEAPP_WORKOUT_DRAFT_STORAGE_PREFIX = 'fitness.workout.draft.'

const userScopedExactKeys = [
  WEAPP_SESSION_STORAGE_KEY,
  'fitness.ai.consent.v1',
] as const

const userScopedKeyPrefixes = [
  WEAPP_WORKOUT_DRAFT_STORAGE_PREFIX,
  'fitness.workout.revision.',
  'fitness.workout.queue.',
  'fitness.privacy.export.',
] as const

export class UserScopedStorageBlockedError extends Error {
  constructor() {
    super('user-scoped local storage is blocked until a new login is established')
    this.name = 'UserScopedStorageBlockedError'
  }
}

export class UserScopedStoragePurgeError extends Error {
  constructor(readonly failedKeys: readonly string[]) {
    super('user-scoped local data could not be completely purged')
    this.name = 'UserScopedStoragePurgeError'
  }
}

export interface WeappUserScopedDataLifecycle extends UserScopedLocalDataPort {
  runUserOperation<T>(operation: () => Promise<T>): Promise<T>
  runClearedSessionRead<T>(operation: () => Promise<T | null>): Promise<T | null>
  isClearVerified(): boolean
  activate(): void
}

export function createWeappUserScopedDataLifecycle(): WeappUserScopedDataLifecycle {
  let tail: Promise<void> = Promise.resolve()
  let blocked = false
  let purgesInFlight = 0
  let purgeFailed = false
  let clearVerified = false

  function enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const task = tail.then(operation, operation)
    tail = task.then(() => undefined, () => undefined)
    return task
  }

  return {
    runUserOperation<T>(operation: () => Promise<T>): Promise<T> {
      if (blocked) return Promise.reject(new UserScopedStorageBlockedError())
      return enqueue(operation)
    },

    runClearedSessionRead<T>(operation: () => Promise<T | null>): Promise<T | null> {
      if (!blocked) return enqueue(operation)
      if (purgesInFlight === 0 && !purgeFailed) return Promise.resolve(null)
      return Promise.reject(new UserScopedStorageBlockedError())
    },

    isClearVerified(): boolean {
      return blocked && purgesInFlight === 0 && !purgeFailed && clearVerified
    },

    async purge(_reason: UserScopedLocalDataPurgeReason): Promise<void> {
      blocked = true
      clearVerified = false
      purgesInFlight += 1
      try {
        await enqueue(purgeUserScopedStorage)
        purgeFailed = false
        clearVerified = true
      } catch (error) {
        purgeFailed = true
        throw error
      } finally {
        purgesInFlight -= 1
      }
    },

    activate(): void {
      if (purgesInFlight > 0 || purgeFailed) {
        throw new UserScopedStorageBlockedError()
      }
      blocked = false
      clearVerified = false
    },
  }
}

async function purgeUserScopedStorage(): Promise<void> {
  const failedKeys = new Set<string>()
  let discoveredKeys: readonly string[] = []
  let inventoryAvailable = true
  try {
    discoveredKeys = storageInfoKeys(await Taro.getStorageInfo())
  } catch {
    inventoryAvailable = false
    failedKeys.add('[storage-inventory]')
  }

  const candidates = new Set<string>(userScopedExactKeys)
  for (const key of discoveredKeys) {
    if (isUserScopedKey(key)) candidates.add(key)
  }
  for (const key of candidates) {
    try {
      await Taro.removeStorage({ key })
    } catch (error) {
      if (!isMissingStorage(error)) failedKeys.add(key)
    }
  }

  if (inventoryAvailable) {
    try {
      for (const key of storageInfoKeys(await Taro.getStorageInfo())) {
        if (isUserScopedKey(key)) failedKeys.add(key)
      }
    } catch {
      failedKeys.add('[storage-verification]')
    }
  }

  if (failedKeys.size > 0) {
    throw new UserScopedStoragePurgeError([...failedKeys].sort())
  }
}

function storageInfoKeys(value: unknown): string[] {
  if (typeof value !== 'object' || value === null || !('keys' in value)) {
    throw new TypeError('storage inventory is missing keys')
  }
  const keys = (value as { keys?: unknown }).keys
  if (!Array.isArray(keys) || keys.some((key) => typeof key !== 'string')) {
    throw new TypeError('storage inventory keys are invalid')
  }
  return keys as string[]
}

function isUserScopedKey(key: string): boolean {
  return (userScopedExactKeys as readonly string[]).includes(key)
    || userScopedKeyPrefixes.some((prefix) => key.startsWith(prefix))
}

function isMissingStorage(value: unknown): boolean {
  return errorMessage(value).match(/(?:data )?not found/i) !== null
}

function errorMessage(value: unknown): string {
  if (value instanceof Error) return value.message
  if (typeof value === 'object' && value !== null && 'errMsg' in value) {
    const errMsg = (value as { errMsg?: unknown }).errMsg
    return typeof errMsg === 'string' ? errMsg : ''
  }
  return ''
}
