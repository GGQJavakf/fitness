import Taro from '@tarojs/taro'

export const STARTUP_BUILD_LABEL = 'R13'
export const STARTUP_BUILD_FINGERPRINT = `FIT-STARTUP-ISOLATED-20260829-${STARTUP_BUILD_LABEL}`
export const STARTUP_DIAGNOSTICS_STORAGE_KEY = 'fitness.startup.diagnostics.v1'

const MAX_STARTUP_DIAGNOSTICS = 8

const STARTUP_FAILURE_STAGES: readonly StartupFailureStage[] = [
  'APP_RENDER',
  'BOOTSTRAP_SUBPACKAGE_LOAD',
  'BOOTSTRAP_REDIRECT',
  'SAFE_HOME_NAVIGATION',
  'STARTUP_MODULE_LOAD',
  'STARTUP_COMPOSITION_ROOT',
  'STARTUP_SESSION_RESTORE',
  'FEATURE_MODULE_LOAD',
  'FEATURE_RENDER',
  'WORKOUT_MOTION_GUIDE_LOAD',
  'WORKOUT_MOTION_GUIDE_RENDER',
]

const STARTUP_FAILURE_CATEGORIES: readonly StartupFailureCategory[] = [
  'RENDER',
  'SUBPACKAGE_LOAD',
  'NAVIGATION',
  'MODULE_LOAD',
  'COMPOSITION_ROOT',
  'SESSION_RESTORE',
]

export type StartupFailureStage =
  | 'APP_RENDER'
  | 'BOOTSTRAP_SUBPACKAGE_LOAD'
  | 'BOOTSTRAP_REDIRECT'
  | 'SAFE_HOME_NAVIGATION'
  | 'STARTUP_MODULE_LOAD'
  | 'STARTUP_COMPOSITION_ROOT'
  | 'STARTUP_SESSION_RESTORE'
  | 'FEATURE_MODULE_LOAD'
  | 'FEATURE_RENDER'
  | 'WORKOUT_MOTION_GUIDE_LOAD'
  | 'WORKOUT_MOTION_GUIDE_RENDER'

export type StartupFailureCategory =
  | 'RENDER'
  | 'SUBPACKAGE_LOAD'
  | 'NAVIGATION'
  | 'MODULE_LOAD'
  | 'COMPOSITION_ROOT'
  | 'SESSION_RESTORE'

export interface StartupDiagnosticRecord {
  readonly schemaVersion: 1
  readonly build: string
  readonly stage: StartupFailureStage
  readonly category: StartupFailureCategory
  readonly occurredAt: number
}

interface SynchronousStorageRuntime {
  getStorageSync?(key: string): unknown
  setStorageSync?(key: string, value: unknown): void
}

/**
 * Records only a fixed stage/category pair and build id. Raw errors, stacks,
 * tokens, route parameters, and user data are deliberately never accepted.
 */
export function recordStartupFailure(
  stage: StartupFailureStage,
  category: StartupFailureCategory,
): StartupDiagnosticRecord {
  const record: StartupDiagnosticRecord = {
    schemaVersion: 1,
    build: STARTUP_BUILD_FINGERPRINT,
    stage,
    category,
    occurredAt: Date.now(),
  }
  const runtime = Taro as unknown as SynchronousStorageRuntime
  try {
    const previous = readStartupDiagnostics(runtime)
    runtime.setStorageSync?.(
      STARTUP_DIAGNOSTICS_STORAGE_KEY,
      [...previous, record].slice(-MAX_STARTUP_DIAGNOSTICS),
    )
  } catch {
    // Diagnostics must never replace a visible recovery surface with a crash.
  }
  try {
    console.error('[fitness-startup-diagnostic]', JSON.stringify(record))
  } catch {
    // Some embedded runtimes may not expose a writable console.
  }
  return record
}

export function getStartupDiagnostics(): readonly StartupDiagnosticRecord[] {
  return readStartupDiagnostics(Taro as unknown as SynchronousStorageRuntime)
}

function readStartupDiagnostics(
  runtime: SynchronousStorageRuntime,
): readonly StartupDiagnosticRecord[] {
  try {
    const value = runtime.getStorageSync?.(STARTUP_DIAGNOSTICS_STORAGE_KEY)
    if (!Array.isArray(value)) return []
    return value
      .map(parseStartupDiagnosticRecord)
      .filter((record): record is StartupDiagnosticRecord => record !== null)
      .slice(-MAX_STARTUP_DIAGNOSTICS)
  } catch {
    return []
  }
}

function parseStartupDiagnosticRecord(value: unknown): StartupDiagnosticRecord | null {
  if (typeof value !== 'object' || value === null) return null
  const record = value as Partial<StartupDiagnosticRecord>
  if (
    record.schemaVersion !== 1
    || record.build !== STARTUP_BUILD_FINGERPRINT
    || !STARTUP_FAILURE_STAGES.includes(record.stage as StartupFailureStage)
    || !STARTUP_FAILURE_CATEGORIES.includes(record.category as StartupFailureCategory)
    || !Number.isSafeInteger(record.occurredAt)
  ) {
    return null
  }
  return {
    schemaVersion: 1,
    build: STARTUP_BUILD_FINGERPRINT,
    stage: record.stage as StartupFailureStage,
    category: record.category as StartupFailureCategory,
    occurredAt: record.occurredAt as number,
  }
}
