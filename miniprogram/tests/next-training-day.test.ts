import { describe, expect, it } from 'vitest'

import { selectNextTrainingDayCode } from '../src/application/selectNextTrainingDay'
import type { WorkoutHistoryItem } from '../src/application/history'

const days = [{ code: 'DAY_A' }, { code: 'DAY_B' }, { code: 'DAY_C' }]

function history(
  trainingDayCode: string,
  status: WorkoutHistoryItem['status'],
  completedAt: string,
): WorkoutHistoryItem {
  return {
    sessionId: `${trainingDayCode}-${completedAt}`,
    trainingDayCode,
    trainingDayName: trainingDayCode,
    status,
    startedAt: completedAt,
    completedAt,
    completedWorkSets: 3,
    completedVolumeKg: 300,
    completedReps: 24,
    usesExternalLoad: true,
  }
}

describe('next training day selection', () => {
  it('rotates after the most recent completed day regardless of response order', () => {
    expect(selectNextTrainingDayCode(days, [
      history('DAY_A', 'COMPLETED', '2026-08-01T09:00:00Z'),
      history('DAY_B', 'COMPLETED', '2026-08-03T09:00:00Z'),
    ])).toBe('DAY_C')
  })

  it('does not advance for an aborted workout and wraps after the final day', () => {
    expect(selectNextTrainingDayCode(days, [
      history('DAY_C', 'ABORTED', '2026-08-04T09:00:00Z'),
      history('DAY_B', 'COMPLETED', '2026-08-03T09:00:00Z'),
    ])).toBe('DAY_C')
    expect(selectNextTrainingDayCode(days, [
      history('DAY_C', 'COMPLETED', '2026-08-04T09:00:00Z'),
    ])).toBe('DAY_A')
  })

  it('starts from the first day when no matching completion exists', () => {
    expect(selectNextTrainingDayCode(days, [
      history('OLD_DAY', 'COMPLETED', '2026-08-04T09:00:00Z'),
    ])).toBe('DAY_A')
  })
})
