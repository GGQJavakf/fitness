import Taro from '@tarojs/taro'

import {
  workoutStartIntentSchemaVersion,
  type WorkoutStartIntent,
  type WorkoutStartIntentStore,
} from '../../application/ports/WorkoutStartIntentStore'
import {
  WEAPP_WORKOUT_DRAFT_STORAGE_PREFIX,
  createWeappUserScopedDataLifecycle,
  type WeappUserScopedDataLifecycle,
} from './WechatUserScopedDataLifecycle'

const startIntentKey = `${WEAPP_WORKOUT_DRAFT_STORAGE_PREFIX}start-intent.v1`

export function createWechatWorkoutStartIntentStore(
  lifecycle: WeappUserScopedDataLifecycle = createWeappUserScopedDataLifecycle(),
): WorkoutStartIntentStore {
  return {
    claim: (intent) => lifecycle.runUserOperation(async () => {
      const checked = validate(intent)
      let existing: WorkoutStartIntent | null
      try {
        existing = await load()
      } catch (error) {
        if (!(error instanceof WorkoutStartIntentCorruptedError)) throw error
        await Taro.removeStorage({ key: startIntentKey })
        existing = null
      }
      if (existing) return existing
      await Taro.setStorage({ key: startIntentKey, data: checked })
      const persisted = await load()
      if (!persisted || JSON.stringify(persisted) !== JSON.stringify(checked)) {
        throw new Error('workout start intent was not durably stored')
      }
      return persisted
    }),
    clear: (expectedClientSessionKey) => lifecycle.runUserOperation(async () => {
      const current = await load()
      if (!current || current.clientSessionKey !== expectedClientSessionKey) return
      await Taro.removeStorage({ key: startIntentKey })
    }),
  }
}

async function load(): Promise<WorkoutStartIntent | null> {
  try {
    return validate((await Taro.getStorage<unknown>({ key: startIntentKey })).data)
  } catch (error) {
    if (missing(error)) return null
    throw error
  }
}

function validate(value: unknown): WorkoutStartIntent {
  if (!isRecord(value)
    || value.schemaVersion !== workoutStartIntentSchemaVersion
    || typeof value.clientSessionKey !== 'string'
    || value.clientSessionKey.length < 8 || value.clientSessionKey.length > 128
    || typeof value.planId !== 'string' || value.planId.length === 0
    || !Number.isSafeInteger(value.planVersionNo) || (value.planVersionNo as number) < 1
    || typeof value.planDayId !== 'string' || value.planDayId.length === 0) {
    throw new WorkoutStartIntentCorruptedError()
  }
  return {
    schemaVersion: workoutStartIntentSchemaVersion,
    clientSessionKey: value.clientSessionKey,
    planId: value.planId,
    planVersionNo: value.planVersionNo as number,
    planDayId: value.planDayId,
  }
}

class WorkoutStartIntentCorruptedError extends Error {
  constructor() {
    super('workout start intent is invalid')
    this.name = 'WorkoutStartIntentCorruptedError'
  }
}

function missing(error: unknown): boolean {
  const message = error instanceof Error
    ? error.message
    : isRecord(error) && typeof error.errMsg === 'string' ? error.errMsg : ''
  return /(?:data )?not found/i.test(message)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
