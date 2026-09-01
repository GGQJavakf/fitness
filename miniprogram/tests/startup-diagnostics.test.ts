import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const storage = vi.hoisted(() => ({
  value: undefined as unknown,
  getStorageSync: vi.fn(),
  setStorageSync: vi.fn(),
}))

vi.mock('@tarojs/taro', () => ({
  default: {
    getStorageSync: storage.getStorageSync,
    setStorageSync: storage.setStorageSync,
  },
}))

const {
  STARTUP_BUILD_FINGERPRINT,
  STARTUP_DIAGNOSTICS_STORAGE_KEY,
  getStartupDiagnostics,
  recordStartupFailure,
} = await import('../src/platform/weapp/startupDiagnostics')

function safeRecord(overrides: Record<string, unknown> = {}) {
  return {
    schemaVersion: 1,
    build: STARTUP_BUILD_FINGERPRINT,
    stage: 'APP_RENDER',
    category: 'RENDER',
    occurredAt: 1,
    ...overrides,
  }
}

describe('privacy-safe startup diagnostics', () => {
  let consoleError: ReturnType<typeof vi.spyOn> | undefined

  beforeEach(() => {
    vi.clearAllMocks()
    storage.value = undefined
    storage.getStorageSync.mockImplementation((key: string) => {
      expect(key).toBe(STARTUP_DIAGNOSTICS_STORAGE_KEY)
      return storage.value
    })
    storage.setStorageSync.mockImplementation((key: string, value: unknown) => {
      expect(key).toBe(STARTUP_DIAGNOSTICS_STORAGE_KEY)
      storage.value = value
    })
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    consoleError?.mockRestore()
    vi.restoreAllMocks()
  })

  it('keeps only the latest eight fixed-schema records', () => {
    let timestamp = 0
    vi.spyOn(Date, 'now').mockImplementation(() => ++timestamp)

    for (let index = 0; index < 10; index += 1) {
      recordStartupFailure('BOOTSTRAP_SUBPACKAGE_LOAD', 'SUBPACKAGE_LOAD')
    }

    const records = getStartupDiagnostics()
    expect(records).toHaveLength(8)
    expect(records.map((record) => record.occurredAt)).toEqual([3, 4, 5, 6, 7, 8, 9, 10])
    for (const record of records) {
      expect(record).toEqual({
        schemaVersion: 1,
        build: STARTUP_BUILD_FINGERPRINT,
        stage: 'BOOTSTRAP_SUBPACKAGE_LOAD',
        category: 'SUBPACKAGE_LOAD',
        occurredAt: record.occurredAt,
      })
      expect(Object.keys(record).sort()).toEqual([
        'build',
        'category',
        'occurredAt',
        'schemaVersion',
        'stage',
      ])
    }
  })

  it('accepts fixed workout motion-guide stages without storing an error payload', () => {
    recordStartupFailure('WORKOUT_MOTION_GUIDE_LOAD', 'MODULE_LOAD')
    recordStartupFailure('WORKOUT_MOTION_GUIDE_RENDER', 'RENDER')

    expect(getStartupDiagnostics()).toEqual([
      expect.objectContaining({
        stage: 'WORKOUT_MOTION_GUIDE_LOAD',
        category: 'MODULE_LOAD',
      }),
      expect.objectContaining({
        stage: 'WORKOUT_MOTION_GUIDE_RENDER',
        category: 'RENDER',
      }),
    ])
    expect(JSON.stringify(storage.value)).not.toMatch(/error|stack|token/i)
  })

  it('ignores malformed records and identifiers outside the fixed stage/category vocabulary', () => {
    const clean = safeRecord()
    storage.value = [
      null,
      'not-a-record',
      safeRecord({ schemaVersion: 2 }),
      safeRecord({ build: 'unknown-build' }),
      safeRecord({ stage: 'RAW_ERROR' }),
      safeRecord({ category: 'TOKEN' }),
      safeRecord({ occurredAt: Number.NaN }),
      clean,
    ]

    expect(getStartupDiagnostics()).toEqual([clean])
  })

  it('tolerates damaged and unavailable storage without throwing', () => {
    storage.value = { damaged: true }
    expect(getStartupDiagnostics()).toEqual([])

    storage.getStorageSync.mockImplementation(() => {
      throw new Error('storage read failed')
    })
    storage.setStorageSync.mockImplementation(() => {
      throw new Error('storage write failed')
    })

    expect(getStartupDiagnostics()).toEqual([])
    expect(() => recordStartupFailure('APP_RENDER', 'RENDER')).not.toThrow()
    expect(recordStartupFailure('APP_RENDER', 'RENDER')).toMatchObject({
      build: STARTUP_BUILD_FINGERPRINT,
      stage: 'APP_RENDER',
      category: 'RENDER',
    })
  })

  it('never accepts or persists raw errors, tokens, or stacks', () => {
    const secret = 'Bearer reusable-secret-token'
    storage.value = [{
      ...safeRecord(),
      rawError: new Error(secret),
      token: secret,
      stack: `Error: ${secret}\n at unsafe`,
    }]
    const unsafeCall = recordStartupFailure as unknown as (...arguments_: unknown[]) => unknown

    const returned = unsafeCall(
      'STARTUP_MODULE_LOAD',
      'MODULE_LOAD',
      { rawError: new Error(secret), token: secret, stack: `Error: ${secret}` },
    )

    for (const value of [returned, storage.value, getStartupDiagnostics()]) {
      const serialized = JSON.stringify(value)
      expect(serialized).not.toContain(secret)
      expect(serialized).not.toMatch(/rawError|token|stack/i)
    }
  })
})
