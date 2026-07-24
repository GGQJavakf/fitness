export type RestTimerStatus = 'RUNNING' | 'FINISHED' | 'SKIPPED'

export interface RestTimerState {
  readonly restStartedAtUtc: string
  readonly restEndsAtUtc: string
  readonly configuredDurationSeconds: number
  readonly adjustmentSeconds: number
  readonly sourceSetKey: string
  readonly timerStatus: RestTimerStatus
  readonly lastObservedAtUtc: string
}

export interface RestTimerSnapshot {
  readonly timer: RestTimerState
  readonly remainingSeconds: number
  readonly clockRollbackDetected: boolean
}

export interface StartRestTimerInput {
  readonly sourceSetKey: string
  readonly configuredDurationSeconds: number
  readonly nowUtc: string
}

export function startRestTimer(input: StartRestTimerInput): RestTimerState {
  if (input.sourceSetKey.trim().length === 0) throw new Error('sourceSetKey is required')
  if (!Number.isSafeInteger(input.configuredDurationSeconds) || input.configuredDurationSeconds <= 0) {
    throw new Error('configuredDurationSeconds must be a positive integer')
  }
  const startedAtMillis = parseUtc(input.nowUtc, 'nowUtc')
  const endsAtMillis = startedAtMillis + input.configuredDurationSeconds * 1_000
  if (!Number.isSafeInteger(endsAtMillis)) throw new Error('rest timer timestamp is outside the supported range')
  const startedAtUtc = new Date(startedAtMillis).toISOString()
  return {
    restStartedAtUtc: startedAtUtc,
    restEndsAtUtc: new Date(endsAtMillis).toISOString(),
    configuredDurationSeconds: input.configuredDurationSeconds,
    adjustmentSeconds: 0,
    sourceSetKey: input.sourceSetKey,
    timerStatus: 'RUNNING',
    lastObservedAtUtc: startedAtUtc,
  }
}

export function adjustRestTimer(
  timer: RestTimerState,
  adjustmentSeconds: number,
  nowUtc: string,
): RestTimerState {
  if (adjustmentSeconds !== 15 && adjustmentSeconds !== -15) {
    throw new Error('rest timer adjustment must be exactly plus or minus 15 seconds')
  }
  const current = resumeRestTimer(timer, nowUtc)
  if (current.timer.timerStatus !== 'RUNNING') return current.timer
  const adjustedEndsAtMillis = parseUtc(current.timer.restEndsAtUtc, 'restEndsAtUtc') + adjustmentSeconds * 1_000
  const effectiveNowMillis = parseUtc(current.timer.lastObservedAtUtc, 'lastObservedAtUtc')
  return {
    ...current.timer,
    restEndsAtUtc: new Date(adjustedEndsAtMillis).toISOString(),
    adjustmentSeconds: current.timer.adjustmentSeconds + adjustmentSeconds,
    timerStatus: adjustedEndsAtMillis <= effectiveNowMillis ? 'FINISHED' : 'RUNNING',
  }
}

export function skipRestTimer(timer: RestTimerState, nowUtc: string): RestTimerState {
  const current = resumeRestTimer(timer, nowUtc)
  if (current.timer.timerStatus !== 'RUNNING') return current.timer
  return { ...current.timer, timerStatus: 'SKIPPED' }
}

export function resumeRestTimer(timer: RestTimerState, nowUtc: string): RestTimerSnapshot {
  validateTimer(timer)
  const observedNowMillis = parseUtc(nowUtc, 'nowUtc')
  const previousObservedMillis = parseUtc(timer.lastObservedAtUtc, 'lastObservedAtUtc')
  const clockRollbackDetected = observedNowMillis < previousObservedMillis
  const effectiveNowMillis = Math.max(observedNowMillis, previousObservedMillis)
  const lastObservedAtUtc = new Date(effectiveNowMillis).toISOString()

  if (timer.timerStatus !== 'RUNNING') {
    return {
      timer: { ...timer, lastObservedAtUtc },
      remainingSeconds: 0,
      clockRollbackDetected,
    }
  }

  const endsAtMillis = parseUtc(timer.restEndsAtUtc, 'restEndsAtUtc')
  const remainingSeconds = Math.max(0, Math.ceil((endsAtMillis - effectiveNowMillis) / 1_000))
  return {
    timer: {
      ...timer,
      lastObservedAtUtc,
      timerStatus: remainingSeconds === 0 ? 'FINISHED' : 'RUNNING',
    },
    remainingSeconds,
    clockRollbackDetected,
  }
}

function validateTimer(timer: RestTimerState): void {
  parseUtc(timer.restStartedAtUtc, 'restStartedAtUtc')
  parseUtc(timer.restEndsAtUtc, 'restEndsAtUtc')
  parseUtc(timer.lastObservedAtUtc, 'lastObservedAtUtc')
  if (!Number.isSafeInteger(timer.configuredDurationSeconds) || timer.configuredDurationSeconds <= 0) {
    throw new Error('configuredDurationSeconds must be a positive integer')
  }
  if (!Number.isSafeInteger(timer.adjustmentSeconds)) throw new Error('adjustmentSeconds must be an integer')
  if (timer.sourceSetKey.trim().length === 0) throw new Error('sourceSetKey is required')
  if (!['RUNNING', 'FINISHED', 'SKIPPED'].includes(timer.timerStatus)) throw new Error('timerStatus is invalid')
}

function parseUtc(value: string, field: string): number {
  if (typeof value !== 'string' || value.trim().length === 0) throw new Error(`${field} must be a UTC timestamp`)
  const millis = Date.parse(value)
  if (!Number.isFinite(millis)) throw new Error(`${field} must be a UTC timestamp`)
  return millis
}
