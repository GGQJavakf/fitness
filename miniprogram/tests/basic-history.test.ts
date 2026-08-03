import { describe, expect, it } from 'vitest'

import { toWorkoutHistoryCard } from '../src/application/history'

describe('basic workout history', () => {
  it('renders completed facts without inventing progression conclusions', () => {
    expect(toWorkoutHistoryCard({
      sessionId: 'session-1', trainingDayCode: 'DAY_A', status: 'COMPLETED',
      startedAt: '2026-07-24T08:00:00Z', completedAt: '2026-07-24T09:00:00Z',
      completedWorkSets: 9, completedVolumeKg: 1234.5, completedReps: 90, usesExternalLoad: true,
    })).toEqual({
      id: 'session-1', title: 'DAY_A', statusLabel: '完整完成',
      timeLabel: '2026-07-24 09:00 UTC', factsLabel: '9 组 · 1234.5 KG·次', incomplete: false,
    })
  })

  it('labels an aborted workout as incomplete while preserving completed facts', () => {
    const card = toWorkoutHistoryCard({
      sessionId: 'session-2', trainingDayCode: 'DAY_B', status: 'ABORTED',
      startedAt: '2026-07-24T08:00:00Z', completedAt: '2026-07-24T08:30:00Z',
      completedWorkSets: 2, completedVolumeKg: 200, completedReps: 16, usesExternalLoad: true,
    })
    expect(card.statusLabel).toBe('提前结束')
    expect(card.incomplete).toBe(true)
    expect(card.factsLabel).toBe('2 组 · 200 KG·次')
  })

  it('describes bodyweight history with completed repetitions', () => {
    const card = toWorkoutHistoryCard({
      sessionId: 'session-3', trainingDayCode: 'BODYWEIGHT_A', status: 'COMPLETED',
      startedAt: '2026-07-24T08:00:00Z', completedAt: '2026-07-24T08:30:00Z',
      completedWorkSets: 8, completedVolumeKg: 0, completedReps: 80, usesExternalLoad: false,
    })

    expect(card.factsLabel).toBe('8 组 · 共 80 次')
  })
})
