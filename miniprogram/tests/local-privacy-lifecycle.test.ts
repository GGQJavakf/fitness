import { beforeEach, describe, expect, it, vi } from 'vitest'

const taro = vi.hoisted(() => ({
  getStorage: vi.fn(),
  setStorage: vi.fn(),
  removeStorage: vi.fn(),
  getStorageInfo: vi.fn(),
}))

vi.mock('@tarojs/taro', () => ({ default: taro }))

import {
  LocalUserDataCleanupError,
  createAccountLifecycleUseCases,
} from '../src/application/localPrivacyLifecycle'
import { FitnessApiClient } from '../src/infrastructure/api/client'
import { createWeappSessionStore } from '../src/platform/weapp/adapters'
import {
  UserScopedStorageBlockedError,
  UserScopedStoragePurgeError,
  createWeappUserScopedDataLifecycle,
} from '../src/platform/weapp/WechatUserScopedDataLifecycle'

const missingStorageError = { errMsg: 'removeStorage:fail data not found' }

function installStorage(values: Map<string, unknown>): void {
  taro.getStorageInfo.mockImplementation(async () => ({ keys: [...values.keys()] }))
  taro.getStorage.mockImplementation(async ({ key }: { key: string }) => {
    if (!values.has(key)) throw { errMsg: 'getStorage:fail data not found' }
    return { data: values.get(key) }
  })
  taro.setStorage.mockImplementation(async ({ key, data }: { key: string; data: unknown }) => {
    values.set(key, data)
  })
  taro.removeStorage.mockImplementation(async ({ key }: { key: string }) => {
    if (!values.has(key)) throw missingStorageError
    values.delete(key)
  })
}

describe('WeApp user-scoped local privacy lifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('purges every user-scoped session/draft/revision/queue/export/consent key and preserves device settings', async () => {
    const values = new Map<string, unknown>([
      ['fitness.session.v1', { accessToken: 'redacted' }],
      ['fitness.workout.draft.active.v1', 'corrupt-pointer'],
      ['fitness.workout.draft.record.old-user.7.bad', 'corrupt-record'],
      ['fitness.workout.draft.recovery.v1', { reason: 'corrupt' }],
      ['fitness.workout.draft.quarantine.v1', { raw: 'corrupt' }],
      ['fitness.workout.draft.start-intent.v1', { clientSessionKey: 'old-user-start' }],
      ['fitness.workout.revision.session-1.v1', 7],
      ['fitness.workout.queue.pending.v1', [{ operation: 'old-user' }]],
      ['fitness.privacy.export.temporary.v1', { records: ['old-user'] }],
      ['fitness.ai.consent.v1', { granted: true }],
      ['fitness.settings.theme.v1', 'dark'],
      ['fitness.device.locale.v1', 'zh-CN'],
      ['third-party.cache', { keep: true }],
    ])
    installStorage(values)

    const lifecycle = createWeappUserScopedDataLifecycle()
    await lifecycle.purge('LOGOUT')

    expect([...values.entries()]).toEqual([
      ['fitness.settings.theme.v1', 'dark'],
      ['fitness.device.locale.v1', 'zh-CN'],
      ['third-party.cache', { keep: true }],
    ])
  })

  it('is idempotent and removes corrupt entries without attempting to parse them', async () => {
    const values = new Map<string, unknown>([
      ['fitness.session.v1', Symbol('corrupt-session')],
      ['fitness.workout.draft.record.%broken', new Uint8Array([1, 2, 3])],
    ])
    installStorage(values)
    const lifecycle = createWeappUserScopedDataLifecycle()

    await lifecycle.purge('ACCESS_REVOKED')
    await expect(lifecycle.purge('ACCESS_REVOKED')).resolves.toBeUndefined()

    expect(values.size).toBe(0)
    expect(taro.getStorage).not.toHaveBeenCalled()
  })

  it('fails closed when the storage inventory is malformed instead of claiming dynamic keys were removed', async () => {
    const values = new Map<string, unknown>([
      ['fitness.session.v1', { accessToken: 'redacted' }],
    ])
    installStorage(values)
    taro.getStorageInfo.mockResolvedValue({ currentSize: 1 })
    const lifecycle = createWeappUserScopedDataLifecycle()

    const error = await lifecycle.purge('LOGOUT').catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(UserScopedStoragePurgeError)
    expect(error).toMatchObject({ failedKeys: ['[storage-inventory]'] })
    expect(values.has('fitness.session.v1')).toBe(false)
  })

  it('continues best-effort cleanup, reports residual user keys, and remains blocked until a clean retry', async () => {
    const values = new Map<string, unknown>([
      ['fitness.session.v1', { accessToken: 'redacted' }],
      ['fitness.workout.draft.record.old-user.1.bad', 'corrupt-record'],
      ['fitness.settings.theme.v1', 'light'],
    ])
    installStorage(values)
    taro.removeStorage.mockImplementation(async ({ key }: { key: string }) => {
      if (key.startsWith('fitness.workout.draft.record.')) throw new Error('storage unavailable')
      if (!values.has(key)) throw missingStorageError
      values.delete(key)
    })
    const lifecycle = createWeappUserScopedDataLifecycle()

    const error = await lifecycle.purge('ACCOUNT_SWITCH').catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(UserScopedStoragePurgeError)
    expect(error).toMatchObject({ failedKeys: ['fitness.workout.draft.record.old-user.1.bad'] })
    expect(values.has('fitness.session.v1')).toBe(false)
    expect(values.has('fitness.settings.theme.v1')).toBe(true)
    await expect(lifecycle.runUserOperation(async () => undefined))
      .rejects.toBeInstanceOf(UserScopedStorageBlockedError)

    installStorage(values)
    await lifecycle.purge('ACCOUNT_SWITCH')
    lifecycle.activate()
    await expect(lifecycle.runUserOperation(async () => 'new-user')).resolves.toBe('new-user')
  })

  it('serializes purge after an in-flight workout write and rejects writes that arrive after purge begins', async () => {
    const values = new Map<string, unknown>()
    installStorage(values)
    const lifecycle = createWeappUserScopedDataLifecycle()
    let finishWrite: (() => void) | undefined
    const writeMayFinish = new Promise<void>((resolve) => { finishWrite = resolve })
    const oldWrite = lifecycle.runUserOperation(async () => {
      await writeMayFinish
      values.set('fitness.workout.draft.record.old-user.2.good', { old: true })
    })

    const purge = lifecycle.purge('LOGOUT')
    const lateWrite = lifecycle.runUserOperation(async () => {
      values.set('fitness.workout.draft.record.old-user.3.late', { old: true })
    })
    finishWrite?.()

    await oldWrite
    await purge
    await expect(lateWrite).rejects.toBeInstanceOf(UserScopedStorageBlockedError)
    expect([...values.keys()]).toEqual([])
  })

  it('clears only the stale session on ordinary authentication expiry and preserves workout recovery data', async () => {
    const values = new Map<string, unknown>([
      ['fitness.session.v1', { accessToken: 'expired', refreshToken: 'stale', expiresAt: '2026-08-01T00:00:00Z' }],
      ['fitness.workout.draft.recovery.v1', { reason: 'checksum mismatch' }],
      ['fitness.workout.draft.quarantine.v1', { record: 'corrupt' }],
    ])
    installStorage(values)
    const lifecycle = createWeappUserScopedDataLifecycle()

    await createWeappSessionStore(lifecycle).clear()

    expect(values.has('fitness.session.v1')).toBe(false)
    expect(values.has('fitness.workout.draft.recovery.v1')).toBe(true)
    expect(values.has('fitness.workout.draft.quarantine.v1')).toBe(true)
  })
})

describe('account logout and switch lifecycle', () => {
  it('purges and returns to login after a successful explicit logout', async () => {
    const order: string[] = []
    const account = createAccountLifecycleUseCases({
      remote: { logout: vi.fn(async () => { order.push('remote-logout') }) },
      localData: { purge: vi.fn(async () => { order.push('purge') }) },
      navigation: { replaceLogin: vi.fn(async () => { order.push('login-screen') }) },
      login: vi.fn(),
      clearMemory: vi.fn(() => { order.push('clear-memory') }),
    })

    await expect(account.logout()).resolves.toEqual({ remoteLogoutSucceeded: true })
    expect(order).toEqual(['clear-memory', 'remote-logout', 'purge', 'login-screen'])
  })

  it('still purges stale local data when remote logout fails', async () => {
    const purge = vi.fn()
    const replaceLogin = vi.fn()
    const account = createAccountLifecycleUseCases({
      remote: { logout: vi.fn().mockRejectedValue(new Error('offline')) },
      localData: { purge },
      navigation: { replaceLogin },
      login: vi.fn(),
    })

    await expect(account.logout()).resolves.toEqual({ remoteLogoutSucceeded: false })
    expect(purge).toHaveBeenCalledWith('LOGOUT')
    expect(replaceLogin).toHaveBeenCalledOnce()
  })

  it('switches only after old-user purge and leaves no stale session when the new login fails', async () => {
    const order: string[] = []
    const login = vi.fn(async () => {
      order.push('new-login')
      throw new Error('login failed')
    })
    const account = createAccountLifecycleUseCases({
      remote: { logout: vi.fn(async () => { order.push('remote-logout') }) },
      localData: { purge: vi.fn(async () => { order.push('purge') }) },
      navigation: { replaceLogin: vi.fn(async () => { order.push('login-screen') }) },
      login,
    })

    await expect(account.switchAccount()).rejects.toThrow('login failed')
    expect(order).toEqual(['remote-logout', 'purge', 'login-screen', 'new-login', 'login-screen'])
  })

  it('does not start a new-account login when old-user purge is incomplete', async () => {
    const login = vi.fn()
    const replaceLogin = vi.fn()
    const account = createAccountLifecycleUseCases({
      remote: { logout: vi.fn() },
      localData: { purge: vi.fn().mockRejectedValue(new Error('purge failed')) },
      navigation: { replaceLogin },
      login,
    })

    await expect(account.switchAccount()).rejects.toBeInstanceOf(LocalUserDataCleanupError)
    expect(login).not.toHaveBeenCalled()
    expect(replaceLogin).toHaveBeenCalledOnce()
  })

  it('calls the existing logout API before local cleanup', async () => {
    const request = vi.fn().mockResolvedValue({
      statusCode: 200,
      data: { data: {}, meta: { requestId: 'request-1', serverTime: '2026-08-11T00:00:00Z' } },
    })
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      { request },
      {
        load: vi.fn().mockResolvedValue({
          accessToken: 'access-redacted', refreshToken: 'refresh-redacted', expiresAt: '2026-08-11T01:00:00Z',
        }),
        save: vi.fn(), clear: vi.fn(),
      },
    )

    await client.logout()

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://127.0.0.1:8080/api/v1/auth/logout',
      method: 'POST',
      headers: expect.objectContaining({ Authorization: 'Bearer access-redacted' }),
    }))
  })

  it('preserves drafts on an ordinary 401 while clearing only the stale session', async () => {
    const values = new Map<string, unknown>([
      ['fitness.session.v1', { accessToken: 'expired', refreshToken: 'expired-refresh', expiresAt: '2026-08-11T01:00:00Z' }],
      ['fitness.workout.draft.record.user.1.good', { exercise: 'SQUAT' }],
      ['fitness.workout.queue.pending.v1', [{ operation: 'record-set' }]],
    ])
    installStorage(values)
    const lifecycle = createWeappUserScopedDataLifecycle()
    const navigate = vi.fn()
    const request = vi.fn()
      .mockResolvedValueOnce({
        statusCode: 401,
        data: { error: { code: 'AUTHENTICATION_REQUIRED', fieldErrors: [], retryable: false } },
      })
      .mockResolvedValueOnce({
        statusCode: 401,
        data: { error: { code: 'AUTHENTICATION_REQUIRED', fieldErrors: [], retryable: false } },
      })
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      { request },
      createWeappSessionStore(lifecycle),
      async () => navigate(),
    )

    await expect(client.getProfileVersion()).rejects.toMatchObject({ code: 'AUTHENTICATION_REQUIRED' })

    expect(values.has('fitness.session.v1')).toBe(false)
    expect(values.has('fitness.workout.draft.record.user.1.good')).toBe(true)
    expect(values.has('fitness.workout.queue.pending.v1')).toBe(true)
    expect(navigate).toHaveBeenCalledOnce()
  })

  it('serially purges terminally revoked user data before navigating to login', async () => {
    const order: string[] = []
    const values = new Map<string, unknown>([
      ['fitness.session.v1', { accessToken: 'revoked', refreshToken: 'revoked-refresh', expiresAt: '2026-08-11T01:00:00Z' }],
      ['fitness.workout.draft.record.user.1.good', { exercise: 'SQUAT' }],
      ['fitness.workout.revision.session-1.v1', 4],
      ['fitness.workout.queue.pending.v1', [{ operation: 'record-set' }]],
    ])
    installStorage(values)
    taro.removeStorage.mockImplementation(async ({ key }: { key: string }) => {
      order.push(`remove:${key}`)
      if (!values.has(key)) throw missingStorageError
      values.delete(key)
    })
    const lifecycle = createWeappUserScopedDataLifecycle()
    const account = createAccountLifecycleUseCases({
      remote: { logout: vi.fn() },
      localData: lifecycle,
      navigation: { replaceLogin: vi.fn(async () => { order.push('login-screen') }) },
      login: vi.fn(),
    })
    const request = vi.fn().mockResolvedValue({
      statusCode: 401,
      data: { error: { code: 'ACCESS_REVOKED', fieldErrors: [], retryable: false } },
    })
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      { request },
      createWeappSessionStore(lifecycle),
      async (code) => {
        if (code !== 'ACCESS_REVOKED') throw new Error('expected terminal authentication failure')
        await account.handleTerminalAuthenticationFailure(code)
      },
    )

    await expect(client.getProfileVersion()).rejects.toMatchObject({ code: 'ACCESS_REVOKED' })

    expect([...values.keys()]).toEqual([])
    expect(request).toHaveBeenCalledOnce()
    expect(order.at(-1)).toBe('login-screen')
    expect(order.filter((item) => item === 'login-screen')).toHaveLength(1)
  })

  it('keeps terminally revoked storage blocked and does not navigate when purge is incomplete', async () => {
    const values = new Map<string, unknown>([
      ['fitness.session.v1', { accessToken: 'revoked', refreshToken: 'revoked-refresh', expiresAt: '2026-08-11T01:00:00Z' }],
      ['fitness.workout.draft.record.user.1.good', { exercise: 'SQUAT' }],
    ])
    installStorage(values)
    taro.removeStorage.mockImplementation(async ({ key }: { key: string }) => {
      if (key.startsWith('fitness.workout.draft.record.')) throw new Error('storage unavailable')
      if (!values.has(key)) throw missingStorageError
      values.delete(key)
    })
    const lifecycle = createWeappUserScopedDataLifecycle()
    const replaceLogin = vi.fn()
    const account = createAccountLifecycleUseCases({
      remote: { logout: vi.fn() },
      localData: lifecycle,
      navigation: { replaceLogin },
      login: vi.fn(),
    })
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      { request: vi.fn().mockResolvedValue({
        statusCode: 401,
        data: { error: { code: 'ACCESS_REVOKED', fieldErrors: [], retryable: false } },
      }) },
      createWeappSessionStore(lifecycle),
      async (code) => {
        if (code !== 'ACCESS_REVOKED') throw new Error('expected terminal authentication failure')
        await account.handleTerminalAuthenticationFailure(code)
      },
    )

    await expect(client.getProfileVersion()).rejects.toBeInstanceOf(LocalUserDataCleanupError)
    await expect(lifecycle.runUserOperation(async () => undefined))
      .rejects.toBeInstanceOf(UserScopedStorageBlockedError)
    expect(values.has('fitness.session.v1')).toBe(false)
    expect(values.has('fitness.workout.draft.record.user.1.good')).toBe(true)
    expect(replaceLogin).not.toHaveBeenCalled()
  })
})
