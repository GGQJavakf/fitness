import Taro from '@tarojs/taro'

import type { WeappUserScopedDataLifecycle } from '../WechatUserScopedDataLifecycle'
import { WEAPP_WORKOUT_DRAFT_STORAGE_PREFIX } from '../WechatUserScopedDataLifecycle'

export type WechatWorkoutStartupState = 'NONE' | 'ACTIVE' | 'RECOVERY_REQUIRED'

const activePointerKey = `${WEAPP_WORKOUT_DRAFT_STORAGE_PREFIX}active.v1`
const recoveryKey = `${WEAPP_WORKOUT_DRAFT_STORAGE_PREFIX}recovery.v1`

export function createWechatWorkoutStartupStateAdapter(
  lifecycle: WeappUserScopedDataLifecycle,
) {
  async function getStartupState(): Promise<WechatWorkoutStartupState> {
    return lifecycle.runUserOperation(async () => {
      if (await storageKeyExists(recoveryKey)) return 'RECOVERY_REQUIRED'
      return await storageKeyExists(activePointerKey) ? 'ACTIVE' : 'NONE'
    })
  }

  return {
    getStartupState,
    hasActive: async (): Promise<boolean> => (await getStartupState()) !== 'NONE',
  }
}

async function storageKeyExists(key: string): Promise<boolean> {
  try {
    await Taro.getStorage<unknown>({ key })
    return true
  } catch (error) {
    if (isMissingStorage(error)) return false
    throw error
  }
}

function isMissingStorage(value: unknown): boolean {
  const message = value instanceof Error
    ? value.message
    : typeof value === 'object' && value !== null && 'errMsg' in value
      ? String((value as { errMsg?: unknown }).errMsg ?? '')
      : ''
  return /(?:data )?not found/i.test(message)
}
