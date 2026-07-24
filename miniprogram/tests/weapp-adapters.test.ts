import { beforeEach, describe, expect, it, vi } from 'vitest'

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

import { createWeappSessionStore } from '../src/platform/weapp/adapters'

describe('WeApp session storage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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
})
