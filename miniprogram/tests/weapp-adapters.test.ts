import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const taro = vi.hoisted(() => ({
  getStorage: vi.fn(),
  getStorageSync: vi.fn(),
  setStorage: vi.fn(),
  removeStorage: vi.fn(),
  request: vi.fn(),
  login: vi.fn(),
  navigateTo: vi.fn(),
  redirectTo: vi.fn(),
  reLaunch: vi.fn(),
  navigateBack: vi.fn(),
}))

const wechat = vi.hoisted(() => ({
  loadSubpackage: vi.fn(),
}))

vi.mock('@tarojs/taro', () => ({ default: taro }))

import {
  createWeappNavigation,
  createWeappSessionStore,
  createWeappTransport,
} from '../src/platform/weapp/adapters'

describe('WeApp session storage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    wechat.loadSubpackage.mockImplementation((options: { success: () => void }) => {
      options.success()
    })
    Reflect.set(globalThis, 'wx', wechat)
  })

  afterEach(() => {
    Reflect.deleteProperty(globalThis, 'wx')
  })

  it('treats only missing storage as an empty session', async () => {
    taro.getStorage.mockRejectedValueOnce({ errMsg: 'getStorage:fail data not found' })
    await expect(createWeappSessionStore().load()).resolves.toBeNull()

    taro.getStorage.mockRejectedValueOnce(new Error('storage permission denied'))
    await expect(createWeappSessionStore().load()).rejects.toThrow('storage permission denied')
  })

  it('captures a validated session synchronously for atomic logout preparation', () => {
    const session = {
      accessToken: 'access-redacted',
      refreshToken: 'refresh-redacted',
      expiresAt: '2026-08-28T12:00:00Z',
    }
    taro.getStorageSync.mockReturnValueOnce(session).mockReturnValueOnce({ accessToken: 'partial' })

    expect(createWeappSessionStore().loadImmediately?.()).toEqual(session)
    expect(createWeappSessionStore().loadImmediately?.()).toBeNull()
  })

  it('does not hide unexpected session removal failures', async () => {
    taro.removeStorage.mockRejectedValueOnce(new Error('storage unavailable'))
    await expect(createWeappSessionStore().clear()).rejects.toThrow('storage unavailable')
  })

  it('relaunches the login entry and clears the stale page stack after authentication expires', async () => {
    await createWeappNavigation().replaceApp('LOGIN')

    expect(taro.reLaunch).toHaveBeenCalledWith({
      url: '/presentation/pages/home/index',
    })
    expect(wechat.loadSubpackage).not.toHaveBeenCalled()
    expect(taro.redirectTo).not.toHaveBeenCalled()
  })

  it('loads the workout feature before navigating to the live workout route', async () => {
    await createWeappNavigation().open('WORKOUT_SESSION')

    expect(wechat.loadSubpackage).toHaveBeenCalledWith({
      name: 'workout',
      success: expect.any(Function),
      fail: expect.any(Function),
    })
    expect(taro.navigateTo).toHaveBeenCalledWith({
      url: '/subpackages/workout/pages/workout-session/index',
    })
    expect(wechat.loadSubpackage.mock.invocationCallOrder[0])
      .toBeLessThan(taro.navigateTo.mock.invocationCallOrder[0])
  })

  it('loads the startup feature before opening the business home', async () => {
    await createWeappNavigation().open('HOME')

    expect(wechat.loadSubpackage).toHaveBeenCalledWith({
      name: 'startup',
      success: expect.any(Function),
      fail: expect.any(Function),
    })
    expect(taro.navigateTo).toHaveBeenCalledWith({
      url: '/subpackages/startup/pages/home/index',
    })
  })

  it('encodes page parameters when replacing the current page', async () => {
    await createWeappNavigation().replace('WORKOUT_PREPARE', {
      trainingDayCode: 'DAY A/1',
    })

    expect(taro.redirectTo).toHaveBeenCalledWith({
      url: '/subpackages/workout/pages/workout-prepare/index?trainingDayCode=DAY%20A%2F1',
    })
  })

  it('does not navigate when loading the destination feature fails', async () => {
    wechat.loadSubpackage.mockImplementation((options: { fail: (error: Error) => void }) => {
      options.fail(new Error('feature package unavailable'))
    })

    await expect(createWeappNavigation().open('HISTORY'))
      .rejects.toThrow('feature package unavailable')

    expect(taro.navigateTo).not.toHaveBeenCalled()
  })

  it.each([
    ['open', () => createWeappNavigation(generationFence).open('HISTORY'), 'navigateTo'],
    ['replace', () => createWeappNavigation(generationFence).replace('HISTORY'), 'redirectTo'],
    ['replaceApp', () => createWeappNavigation(generationFence).replaceApp('HOME'), 'reLaunch'],
  ] as const)('does not execute stale %s navigation after subpackage loading', async (
    _name,
    navigate,
    taroMethod,
  ) => {
    let finishLoad!: () => void
    wechat.loadSubpackage.mockImplementationOnce((options: { success: () => void }) => {
      finishLoad = options.success
    })

    const pending = navigate()
    await vi.waitFor(() => expect(wechat.loadSubpackage).toHaveBeenCalledOnce())
    generationFence.begin()
    finishLoad()

    await expect(pending).rejects.toThrow('generation invalidated')
    expect(taro[taroMethod]).not.toHaveBeenCalled()
  })

  it('uses the CloudBase private container path when a service is configured', async () => {
    const callContainer = vi.fn().mockResolvedValue({
      statusCode: 200,
      data: { data: { ok: true } },
      header: { 'X-Has-More': 'false' },
    })
    Reflect.set(globalThis, 'wx', { cloud: { callContainer } })

    const response = await createWeappTransport({
      environmentId: 'fitness-env',
      serviceName: 'fitness-api',
    }).request({
      url: 'http://127.0.0.1:8080/api/v1/profile?include=plan',
      method: 'GET',
      headers: { Authorization: 'Bearer redacted' },
    })

    expect(taro.request).not.toHaveBeenCalled()
    expect(callContainer).toHaveBeenCalledWith({
      config: { env: 'fitness-env' },
      path: '/api/v1/profile?include=plan',
      method: 'GET',
      header: {
        Authorization: 'Bearer redacted',
        'X-WX-SERVICE': 'fitness-api',
      },
    })
    expect(response).toEqual({
      statusCode: 200,
      data: { data: { ok: true } },
      headers: { 'x-has-more': 'false' },
    })
  })

  it('rejects a CloudBase response without a valid HTTP status code', async () => {
    const callContainer = vi.fn().mockResolvedValue({
      data: { error: 'malformed response' },
    })
    Reflect.set(globalThis, 'wx', { cloud: { callContainer } })

    const request = createWeappTransport({
      environmentId: 'fitness-env',
      serviceName: 'fitness-api',
    }).request({
      url: 'http://127.0.0.1:8080/api/v1/profile',
      method: 'GET',
      headers: {},
    })

    await expect(request).rejects.toThrow('CloudBase container returned an invalid HTTP status code')
  })

  it('normalizes mixed-case response headers from the direct Taro transport', async () => {
    taro.request.mockResolvedValueOnce({
      statusCode: 200,
      data: { data: [] },
      header: {
        'X-HaS-MoRe': 'true',
        'x-NeXt-CuRsOr': 'cursor-direct-taro',
        Ignored: 7,
      },
    })

    const response = await createWeappTransport({}).request({
      url: 'http://127.0.0.1:8080/api/v1/progression-recommendations',
      method: 'GET',
      headers: {},
    })

    expect(response.headers).toEqual({
      'x-has-more': 'true',
      'x-next-cursor': 'cursor-direct-taro',
    })
  })

  it('times out a stalled CloudBase container call instead of leaving an action pending forever', async () => {
    vi.useFakeTimers()
    try {
      const callContainer = vi.fn(() => new Promise(() => undefined))
      Reflect.set(globalThis, 'wx', { cloud: { callContainer } })
      const request = createWeappTransport({
        environmentId: 'fitness-env',
        serviceName: 'fitness-api',
        requestTimeoutMs: 25,
      }).request({
        url: 'http://127.0.0.1:8080/api/v1/workouts',
        method: 'POST',
        headers: {},
      })

      const rejection = expect(request).rejects.toThrow('CloudBase 请求超时')
      await vi.advanceTimersByTimeAsync(25)
      await rejection
    } finally {
      vi.useRealTimers()
    }
  })
})

let navigationGeneration = 0
const generationFence = {
  capture: () => navigationGeneration,
  assertCurrent(expected: number): void {
    if (expected !== navigationGeneration) throw new Error('generation invalidated')
  },
  begin(): void {
    navigationGeneration += 1
  },
}
