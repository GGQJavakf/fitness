import Taro from '@tarojs/taro'

import {
  WorkoutDraftCorruptedError,
  WorkoutDraftStorageFullError,
  workoutDraftSchemaVersion,
  type WorkoutDraft,
  type WorkoutDraftStore,
} from '../../application/ports/WorkoutDraftStore'
import { restoreOperationQueue } from '../../domain/sync/OperationQueue'

const activePointerKey = 'fitness.workout.draft.active.v1'
const recordKeyPrefix = 'fitness.workout.draft.record.'

interface ActivePointer {
  recordKey: string
  schemaVersion: number
}

interface StoredEnvelope {
  schemaVersion: number
  payloadJson: string
  checksum: string
}

export function createWechatWorkoutDraftStore(): WorkoutDraftStore {
  return {
    async loadActive(): Promise<WorkoutDraft | null> {
      const pointer = await readOptional(activePointerKey)
      if (pointer === null) return null
      if (!isPointer(pointer)) throw new WorkoutDraftCorruptedError('workout draft active pointer is invalid')
      const envelope = await readRequired(pointer.recordKey)
      return decodeEnvelope(envelope)
    },

    async save(input: WorkoutDraft): Promise<void> {
      const draft = validateDraft(input)
      const payloadJson = JSON.stringify(draft)
      const checksum = integrityChecksum(payloadJson)
      const recordKey = `${recordKeyPrefix}${encodeURIComponent(draft.draftId)}.${draft.revision}.${checksum}`
      const envelope: StoredEnvelope = { schemaVersion: workoutDraftSchemaVersion, payloadJson, checksum }
      try {
        await Taro.setStorage({ key: recordKey, data: envelope })
        const verified = await readRequired(recordKey)
        decodeEnvelope(verified)
        await Taro.setStorage({
          key: activePointerKey,
          data: { recordKey, schemaVersion: workoutDraftSchemaVersion } satisfies ActivePointer,
        })
      } catch (error) {
        if (isStorageFull(error)) throw new WorkoutDraftStorageFullError()
        throw error
      }
    },

    async clearActive(expectedDraftId: string): Promise<void> {
      const pointer = await readOptional(activePointerKey)
      if (pointer === null) return
      if (!isPointer(pointer)) throw new WorkoutDraftCorruptedError('workout draft active pointer is invalid')
      const draft = decodeEnvelope(await readRequired(pointer.recordKey))
      if (draft.draftId !== expectedDraftId) return
      await removeOptional(activePointerKey)
      try {
        await removeOptional(pointer.recordKey)
      } catch {
        // The active pointer is the source of truth. An orphaned revision is safer than reviving a completed workout.
      }
    },
  }
}

function decodeEnvelope(value: unknown): WorkoutDraft {
  if (!isEnvelope(value) || integrityChecksum(value.payloadJson) !== value.checksum) {
    throw new WorkoutDraftCorruptedError('workout draft checksum does not match')
  }
  try {
    return migrateWorkoutDraft(JSON.parse(value.payloadJson) as unknown)
  } catch (error) {
    if (error instanceof WorkoutDraftCorruptedError) throw error
    throw new WorkoutDraftCorruptedError('workout draft payload is invalid')
  }
}

export function migrateWorkoutDraft(value: unknown): WorkoutDraft {
  if (!isRecord(value)) throw new WorkoutDraftCorruptedError('workout draft payload is not an object')
  if (value.schemaVersion === workoutDraftSchemaVersion) return validateDraft(value)
  if (value.schemaVersion === 0) {
    return validateDraft({
      ...value,
      schemaVersion: workoutDraftSchemaVersion,
      queue: {
        nextClientOperationSeq: value.nextClientOperationSeq,
        operations: value.pendingOperations,
      },
    })
  }
  throw new WorkoutDraftCorruptedError('workout draft schema version is unsupported')
}

function validateDraft(value: unknown): WorkoutDraft {
  if (!isRecord(value)
    || value.schemaVersion !== workoutDraftSchemaVersion
    || typeof value.draftId !== 'string' || value.draftId.length === 0
    || !Number.isSafeInteger(value.revision) || (value.revision as number) < 0
    || typeof value.clientSessionKey !== 'string' || value.clientSessionKey.length === 0
    || !(typeof value.sessionId === 'string' || value.sessionId === null)
    || !isRecord(value.planSnapshot)
    || !Number.isSafeInteger(value.currentExerciseIndex) || (value.currentExerciseIndex as number) < 0
    || !Number.isSafeInteger(value.currentSetIndex) || (value.currentSetIndex as number) < 0
    || !Array.isArray(value.setRecords)
    || !(value.restTimer === null || isRecord(value.restTimer))
    || !isRecord(value.queue) || !Array.isArray(value.queue.operations)
    || !Number.isSafeInteger(value.lastServerVersion) || (value.lastServerVersion as number) < 0
    || typeof value.updatedAtUtc !== 'string') {
    throw new WorkoutDraftCorruptedError('workout draft fields are invalid')
  }
  return {
    schemaVersion: workoutDraftSchemaVersion,
    draftId: value.draftId,
    revision: value.revision as number,
    clientSessionKey: value.clientSessionKey,
    sessionId: value.sessionId,
    planSnapshot: value.planSnapshot,
    currentExerciseIndex: value.currentExerciseIndex as number,
    currentSetIndex: value.currentSetIndex as number,
    setRecords: value.setRecords as readonly Readonly<Record<string, unknown>>[],
    restTimer: value.restTimer,
    queue: restoreOperationQueue(value.queue as unknown as Parameters<typeof restoreOperationQueue>[0]),
    lastServerVersion: value.lastServerVersion as number,
    updatedAtUtc: value.updatedAtUtc,
  }
}

function isPointer(value: unknown): value is ActivePointer {
  return isRecord(value)
    && typeof value.recordKey === 'string'
    && value.recordKey.startsWith(recordKeyPrefix)
    && value.schemaVersion === workoutDraftSchemaVersion
}

function isEnvelope(value: unknown): value is StoredEnvelope {
  return isRecord(value)
    && value.schemaVersion === workoutDraftSchemaVersion
    && typeof value.payloadJson === 'string'
    && typeof value.checksum === 'string'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

async function readOptional(key: string): Promise<unknown | null> {
  try {
    return (await Taro.getStorage<unknown>({ key })).data
  } catch (error) {
    if (isMissingStorage(error)) return null
    throw error
  }
}

async function readRequired(key: string): Promise<unknown> {
  const value = await readOptional(key)
  if (value === null) throw new WorkoutDraftCorruptedError('workout draft record is missing')
  return value
}

async function removeOptional(key: string): Promise<void> {
  try {
    await Taro.removeStorage({ key })
  } catch (error) {
    if (!isMissingStorage(error)) throw error
  }
}

function isMissingStorage(value: unknown): boolean {
  return errorMessage(value).match(/(?:data )?not found/i) !== null
}

function isStorageFull(value: unknown): boolean {
  return errorMessage(value).match(/(?:exceed|quota|storage (?:is )?full|storage limit)/i) !== null
}

function errorMessage(value: unknown): string {
  if (value instanceof Error) return value.message
  if (isRecord(value) && typeof value.errMsg === 'string') return value.errMsg
  return ''
}

function integrityChecksum(value: string): string {
  let hash = 0x811c9dc5
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}
