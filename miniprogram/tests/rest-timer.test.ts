import { describe, expect, it } from 'vitest'

import {
  adjustRestTimer,
  resumeRestTimer,
  skipRestTimer,
  startRestTimer,
} from '../src/domain/workout/RestTimer'

describe('timestamp rest timer', () => {
  it('starts from absolute timestamps and derives remaining time without interval state', () => {
    const timer = startRestTimer({
      sourceSetKey: 'set-1',
      configuredDurationSeconds: 90,
      nowUtc: '2026-07-24T09:00:00.000Z',
    })

    expect(timer).toEqual({
      restStartedAtUtc: '2026-07-24T09:00:00.000Z',
      restEndsAtUtc: '2026-07-24T09:01:30.000Z',
      configuredDurationSeconds: 90,
      adjustmentSeconds: 0,
      sourceSetKey: 'set-1',
      timerStatus: 'RUNNING',
      lastObservedAtUtc: '2026-07-24T09:00:00.000Z',
    })
    expect(resumeRestTimer(timer, '2026-07-24T09:00:30.000Z')).toMatchObject({
      remainingSeconds: 60,
      clockRollbackDetected: false,
      timer: { timerStatus: 'RUNNING' },
    })
  })

  it('supports exact plus or minus fifteen-second adjustments and finishes at zero', () => {
    const timer = startRestTimer({
      sourceSetKey: 'set-2',
      configuredDurationSeconds: 30,
      nowUtc: '2026-07-24T09:00:00.000Z',
    })

    const extended = adjustRestTimer(timer, 15, '2026-07-24T09:00:10.000Z')
    expect(extended.restEndsAtUtc).toBe('2026-07-24T09:00:45.000Z')
    expect(extended.adjustmentSeconds).toBe(15)

    const shortened = adjustRestTimer(extended, -15, '2026-07-24T09:00:40.000Z')
    expect(shortened.restEndsAtUtc).toBe('2026-07-24T09:00:30.000Z')
    expect(shortened.adjustmentSeconds).toBe(0)
    expect(shortened.timerStatus).toBe('FINISHED')
    expect(resumeRestTimer(shortened, '2026-07-24T09:00:40.000Z').remainingSeconds).toBe(0)
  })

  it('rejects unsupported adjustments instead of changing a rule-owned duration silently', () => {
    const timer = startRestTimer({
      sourceSetKey: 'set-3',
      configuredDurationSeconds: 60,
      nowUtc: '2026-07-24T09:00:00.000Z',
    })

    expect(() => adjustRestTimer(timer, 30, '2026-07-24T09:00:01.000Z')).toThrow(/15 seconds/)
  })

  it('skips explicitly and never reports skipped time as completed countdown work', () => {
    const timer = startRestTimer({
      sourceSetKey: 'set-4',
      configuredDurationSeconds: 60,
      nowUtc: '2026-07-24T09:00:00.000Z',
    })

    const skipped = skipRestTimer(timer, '2026-07-24T09:00:05.000Z')
    expect(skipped.timerStatus).toBe('SKIPPED')
    expect(resumeRestTimer(skipped, '2026-07-24T09:00:20.000Z').remainingSeconds).toBe(0)
  })

  it('expires immediately when the observed time reaches or passes the end timestamp', () => {
    const timer = startRestTimer({
      sourceSetKey: 'set-5',
      configuredDurationSeconds: 15,
      nowUtc: '2026-07-24T09:00:00.000Z',
    })

    const resumed = resumeRestTimer(timer, '2026-07-24T09:05:00.000Z')
    expect(resumed.remainingSeconds).toBe(0)
    expect(resumed.timer.timerStatus).toBe('FINISHED')
  })
})
