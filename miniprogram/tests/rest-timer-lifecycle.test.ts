import { describe, expect, it } from 'vitest'

import type { Clock } from '../src/application/ports/Clock'
import { resumeRestTimer, startRestTimer } from '../src/domain/workout/RestTimer'

class MutableClock implements Clock {
  constructor(private current: string) {}

  nowUtc(): string {
    return this.current
  }

  set(value: string): void {
    this.current = value
  }
}

describe('rest timer lifecycle recovery', () => {
  it('restores after backgrounding or process recreation from persisted timestamps', () => {
    const clock = new MutableClock('2026-07-24T09:00:00.000Z')
    const started = startRestTimer({
      sourceSetKey: 'set-background',
      configuredDurationSeconds: 90,
      nowUtc: clock.nowUtc(),
    })
    const persisted = JSON.parse(JSON.stringify(started)) as typeof started

    clock.set('2026-07-24T09:01:00.000Z')
    const foreground = resumeRestTimer(persisted, clock.nowUtc())

    expect(foreground.remainingSeconds).toBe(30)
    expect(foreground.timer.timerStatus).toBe('RUNNING')
  })

  it('does not depend on delayed callbacks and completes on the next lifecycle observation', () => {
    const clock = new MutableClock('2026-07-24T09:00:00.000Z')
    const timer = startRestTimer({
      sourceSetKey: 'set-delayed',
      configuredDurationSeconds: 15,
      nowUtc: clock.nowUtc(),
    })

    clock.set('2026-07-24T09:10:00.000Z')
    const foreground = resumeRestTimer(timer, clock.nowUtc())

    expect(foreground.remainingSeconds).toBe(0)
    expect(foreground.timer.timerStatus).toBe('FINISHED')
  })

  it('detects clock rollback without increasing remaining time or blocking the workout', () => {
    const timer = startRestTimer({
      sourceSetKey: 'set-clock',
      configuredDurationSeconds: 90,
      nowUtc: '2026-07-24T09:00:00.000Z',
    })
    const observed = resumeRestTimer(timer, '2026-07-24T09:00:30.000Z')

    const rolledBack = resumeRestTimer(observed.timer, '2026-07-24T08:59:30.000Z')

    expect(rolledBack.clockRollbackDetected).toBe(true)
    expect(rolledBack.remainingSeconds).toBe(60)
    expect(rolledBack.timer.timerStatus).toBe('RUNNING')
    expect(rolledBack.timer.lastObservedAtUtc).toBe('2026-07-24T09:00:30.000Z')
  })
})
