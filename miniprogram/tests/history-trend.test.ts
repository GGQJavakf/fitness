import { describe, expect, it } from 'vitest'

import { toExerciseTrendRows } from '../src/application/progression'

describe('exercise history trend', () => {
  it('renders only effective work-set points returned by the server', () => {
    const rows = toExerciseTrendRows([
      {
        sessionId: 'session-1', completedAt: '2026-07-20T09:00:00Z',
        topWeightKg: 40, totalReps: 30, workSetCount: 3,
      },
      {
        sessionId: 'session-2', completedAt: '2026-07-24T09:00:00Z',
        topWeightKg: 42.5, totalReps: 24, workSetCount: 3,
      },
    ])

    expect(rows).toEqual([
      { id: 'session-1', timeLabel: '2026-07-20', weightLabel: '40 KG', volumeLabel: '3 个有效正式组 · 共 30 次' },
      { id: 'session-2', timeLabel: '2026-07-24', weightLabel: '42.5 KG', volumeLabel: '3 个有效正式组 · 共 24 次' },
    ])
  })

  it('does not invent a trend point when the server returns no effective facts', () => {
    expect(toExerciseTrendRows([])).toEqual([])
  })
})
