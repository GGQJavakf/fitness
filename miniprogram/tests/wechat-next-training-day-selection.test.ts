import { beforeEach, describe, expect, it, vi } from 'vitest'

const taro = vi.hoisted(() => ({
  getStorage: vi.fn(),
  setStorage: vi.fn(),
  removeStorage: vi.fn(),
}))

vi.mock('@tarojs/taro', () => ({ default: taro }))

import { createWechatNextTrainingDaySelection } from '../src/platform/weapp/adapters'

describe('WeChat next training day selection', () => {
  beforeEach(() => vi.clearAllMocks())

  it('persists the navigation intent and consumes it once', async () => {
    const selection = createWechatNextTrainingDaySelection()
    taro.getStorage
      .mockResolvedValueOnce({ data: 'DAY_2' })
      .mockRejectedValueOnce({ errMsg: 'getStorage:fail data not found' })

    await selection.remember(' DAY_2 ')

    expect(taro.setStorage).toHaveBeenCalledWith({
      key: 'fitness.workout.draft.next-training-day.v1',
      data: 'DAY_2',
    })
    await expect(selection.consume()).resolves.toBe('DAY_2')
    expect(taro.removeStorage).toHaveBeenCalledWith({
      key: 'fitness.workout.draft.next-training-day.v1',
    })
    await expect(selection.consume()).resolves.toBeUndefined()
  })
})
