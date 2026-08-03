import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const taro = vi.hoisted(() => ({
  getStorage: vi.fn(),
  setStorage: vi.fn(),
  removeStorage: vi.fn(),
  request: vi.fn(),
  login: vi.fn(),
  navigateTo: vi.fn(),
  redirectTo: vi.fn(),
  navigateBack: vi.fn(),
}))

vi.mock('@tarojs/taro', () => ({ default: taro }))

import { createWeappSessionStore, createWeappTransport } from '../src/platform/weapp/adapters'

describe('WeApp session storage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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

  it('does not hide unexpected session removal failures', async () => {
    taro.removeStorage.mockRejectedValueOnce(new Error('storage unavailable'))
    await expect(createWeappSessionStore().clear()).rejects.toThrow('storage unavailable')
  })

  it('uses the CloudBase private container path when a service is configured', async () => {
    const callContainer = vi.fn().mockResolvedValue({
      statusCode: 200,
      data: { data: { ok: true } },
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
    expect(response).toEqual({ statusCode: 200, data: { data: { ok: true } } })
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
})
