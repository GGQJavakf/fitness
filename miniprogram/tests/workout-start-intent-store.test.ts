import { beforeEach, describe, expect, it, vi } from 'vitest'

const taro = vi.hoisted(() => ({
  getStorage: vi.fn(),
  setStorage: vi.fn(),
  removeStorage: vi.fn(),
}))

vi.mock('@tarojs/taro', () => ({ default: taro }))

import { workoutStartIntentSchemaVersion } from '../src/application/ports/WorkoutStartIntentStore'
import { createWechatWorkoutStartIntentStore } from '../src/platform/weapp/WechatWorkoutStartIntentStore'

describe('WeChat workout start intent storage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('serializes concurrent claims and keeps the first durable client key', async () => {
    const values = new Map<string, unknown>()
    taro.getStorage.mockImplementation(async ({ key }: { key: string }) => {
      if (!values.has(key)) throw { errMsg: 'getStorage:fail data not found' }
      return { data: values.get(key) }
    })
    taro.setStorage.mockImplementation(async ({ key, data }: { key: string; data: unknown }) => {
      values.set(key, data)
    })
    taro.removeStorage.mockImplementation(async ({ key }: { key: string }) => {
      values.delete(key)
    })
    const store = createWechatWorkoutStartIntentStore()
    const source = {
      schemaVersion: workoutStartIntentSchemaVersion,
      planId: 'plan-1',
      planVersionNo: 1,
      planDayId: 'DAY_1',
    } as const

    const [first, concurrent] = await Promise.all([
      store.claim({ ...source, clientSessionKey: 'client-session-first' }),
      store.claim({ ...source, clientSessionKey: 'client-session-second' }),
    ])

    expect(first.clientSessionKey).toBe('client-session-first')
    expect(concurrent.clientSessionKey).toBe('client-session-first')
    expect(taro.setStorage).toHaveBeenCalledOnce()

    await store.clear('client-session-second')
    expect(values.has('fitness.workout.draft.start-intent.v1')).toBe(true)
    await store.clear('client-session-first')
    expect(values.has('fitness.workout.draft.start-intent.v1')).toBe(false)
  })

  it('replaces a corrupted pending intent only after removing it, relying on the server active-session guard', async () => {
    const key = 'fitness.workout.draft.start-intent.v1'
    const values = new Map<string, unknown>([[key, { schemaVersion: 1, clientSessionKey: 7 }]])
    taro.getStorage.mockImplementation(async ({ key: requested }: { key: string }) => {
      if (!values.has(requested)) throw { errMsg: 'getStorage:fail data not found' }
      return { data: values.get(requested) }
    })
    taro.setStorage.mockImplementation(async ({ key: requested, data }: { key: string; data: unknown }) => {
      values.set(requested, data)
    })
    taro.removeStorage.mockImplementation(async ({ key: requested }: { key: string }) => {
      values.delete(requested)
    })
    const store = createWechatWorkoutStartIntentStore()
    const valid = {
      schemaVersion: workoutStartIntentSchemaVersion,
      clientSessionKey: 'recovered-client-session',
      planId: 'plan-1',
      planVersionNo: 1,
      planDayId: 'DAY_1',
    } as const

    await expect(store.claim(valid)).resolves.toEqual(valid)

    expect(taro.removeStorage).toHaveBeenCalledWith({ key })
    expect(taro.setStorage).toHaveBeenCalledWith({ key, data: valid })
    expect(values.get(key)).toEqual(valid)
  })
})
