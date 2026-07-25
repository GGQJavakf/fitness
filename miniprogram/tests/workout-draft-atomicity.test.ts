import { beforeEach, describe, expect, it, vi } from 'vitest'

const taro = vi.hoisted(() => ({
  getStorage: vi.fn(),
  setStorage: vi.fn(),
  removeStorage: vi.fn(),
}))

vi.mock('@tarojs/taro', () => ({ default: taro }))

import { createOperationQueue } from '../src/domain/sync/OperationQueue'
import type { WorkoutDraftStore } from '../src/application/ports/WorkoutDraftStore'
import { WorkoutDraftStorageFullError } from '../src/application/ports/WorkoutDraftStore'
import { WorkoutSyncService } from '../src/application/use-cases/WorkoutSyncService'
import {
  createWechatWorkoutDraftStore,
  migrateWorkoutDraft,
} from '../src/platform/weapp/WechatStorageAdapter'

describe('WeChat workout draft atomic storage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('writes and verifies a new record before switching the active pointer', async () => {
    const values = new Map<string, unknown>()
    taro.setStorage.mockImplementation(async ({ key, data }: { key: string; data: unknown }) => {
      values.set(key, data)
    })
    taro.getStorage.mockImplementation(async ({ key }: { key: string }) => {
      if (!values.has(key)) throw { errMsg: 'getStorage:fail data not found' }
      return { data: values.get(key) }
    })

    await createWechatWorkoutDraftStore().save(draft(1))

    const calls = taro.setStorage.mock.calls.map(([value]) => value.key as string)
    expect(calls).toHaveLength(2)
    expect(calls[0]).toMatch(/^fitness\.workout\.draft\.record\./)
    expect(calls[1]).toBe('fitness.workout.draft.active.v1')
    expect(taro.getStorage).toHaveBeenCalledWith({ key: calls[0] })
  })

  it('keeps the previous active draft if pointer switching fails', async () => {
    const values = new Map<string, unknown>([
      ['fitness.workout.draft.active.v1', { recordKey: 'old-record', schemaVersion: 1 }],
    ])
    taro.setStorage.mockImplementation(async ({ key, data }: { key: string; data: unknown }) => {
      if (key === 'fitness.workout.draft.active.v1') throw new Error('simulated process termination')
      values.set(key, data)
    })
    taro.getStorage.mockImplementation(async ({ key }: { key: string }) => ({ data: values.get(key) }))

    await expect(createWechatWorkoutDraftStore().save(draft(2)))
      .rejects.toThrow('simulated process termination')
    expect(values.get('fitness.workout.draft.active.v1'))
      .toEqual({ recordKey: 'old-record', schemaVersion: 1 })
  })

  it('does not replace the active pointer when storage is full', async () => {
    taro.setStorage.mockRejectedValueOnce({ errMsg: 'setStorage:fail exceed storage limit' })

    await expect(createWechatWorkoutDraftStore().save(draft(3)))
      .rejects.toBeInstanceOf(WorkoutDraftStorageFullError)
    expect(taro.setStorage).toHaveBeenCalledTimes(1)
  })

  it('rejects a corrupted active record instead of returning a partial draft', async () => {
    taro.getStorage
      .mockResolvedValueOnce({
        data: { recordKey: 'fitness.workout.draft.record.draft-0001.1.deadbeef', schemaVersion: 1 },
      })
      .mockResolvedValueOnce({ data: { schemaVersion: 1, payloadJson: '{}', checksum: 'bad' } })

    await expect(createWechatWorkoutDraftStore().loadActive()).rejects.toThrow('checksum')
  })

  it('migrates a legacy queue without discarding pending operations', () => {
    const current = draft(4)
    const migrated = migrateWorkoutDraft({
      ...current,
      schemaVersion: 0,
      queue: undefined,
      nextClientOperationSeq: 2,
      pendingOperations: [{
        clientOperationSeq: 1,
        idempotencyKey: 'set-key-0001',
        type: 'UPSERT_SET',
        payload: { reps: 10 },
        createdAtUtc: '2026-07-24T08:00:00Z',
        status: 'PENDING',
      }],
    })

    expect(migrated.schemaVersion).toBe(1)
    expect(migrated.queue.operations).toHaveLength(1)
    expect(migrated.queue.nextClientOperationSeq).toBe(2)
  })

  it('clears only the expected completed draft and its active pointer', async () => {
    const values = new Map<string, unknown>()
    taro.setStorage.mockImplementation(async ({ key, data }: { key: string; data: unknown }) => { values.set(key, data) })
    taro.getStorage.mockImplementation(async ({ key }: { key: string }) => {
      if (!values.has(key)) throw { errMsg: 'getStorage:fail data not found' }
      return { data: values.get(key) }
    })
    taro.removeStorage.mockImplementation(async ({ key }: { key: string }) => { values.delete(key) })
    const store = createWechatWorkoutDraftStore()
    await store.save(draft(5))

    await store.clearActive('another-draft')
    expect(await store.loadActive()).toMatchObject({ draftId: 'draft-0001' })

    await store.clearActive('draft-0001')
    await expect(store.loadActive()).resolves.toBeNull()
    expect([...values.keys()].filter((key) => key.startsWith('fitness.workout.draft.'))).toEqual([])
  })

  it('does not revive a completed draft when obsolete-record cleanup fails', async () => {
    const values = new Map<string, unknown>()
    taro.setStorage.mockImplementation(async ({ key, data }: { key: string; data: unknown }) => { values.set(key, data) })
    taro.getStorage.mockImplementation(async ({ key }: { key: string }) => {
      if (!values.has(key)) throw { errMsg: 'getStorage:fail data not found' }
      return { data: values.get(key) }
    })
    taro.removeStorage.mockImplementation(async ({ key }: { key: string }) => {
      if (key.startsWith('fitness.workout.draft.record.')) throw new Error('cleanup failed')
      values.delete(key)
    })
    const store = createWechatWorkoutDraftStore()
    await store.save(draft(6))

    await expect(store.clearActive('draft-0001')).resolves.toBeUndefined()
    await expect(store.loadActive()).resolves.toBeNull()
  })

  it('does not expose a locally recorded operation until the atomic save finishes', async () => {
    let releaseSave!: () => void
    const saveGate = new Promise<void>((resolve) => { releaseSave = resolve })
    const store: WorkoutDraftStore = {
      loadActive: vi.fn(),
      save: vi.fn(() => saveGate),
      clearActive: vi.fn(),
    }
    const service = new WorkoutSyncService(store, () => '2026-07-24T08:00:00Z')
    let resolved = false

    const result = service.recordLocalOperation(draft(5), {
      idempotencyKey: 'set-key-0001',
      type: 'UPSERT_SET',
      payload: { reps: 10 },
    }).then((value) => {
      resolved = true
      return value
    })
    await Promise.resolve()

    expect(store.save).toHaveBeenCalledOnce()
    expect(resolved).toBe(false)
    releaseSave()
    expect((await result).operation.clientOperationSeq).toBe(1)
  })
})

function draft(revision: number) {
  return {
    schemaVersion: 1 as const,
    draftId: 'draft-0001',
    revision,
    clientSessionKey: 'session-key-0001',
    sessionId: null,
    planSnapshot: { planVersionId: 'plan-version-0001' },
    currentExerciseIndex: 0,
    currentSetIndex: 0,
    setRecords: [],
    restTimer: null,
    queue: createOperationQueue(),
    lastServerVersion: 0,
    updatedAtUtc: '2026-07-24T08:00:00Z',
  }
}
