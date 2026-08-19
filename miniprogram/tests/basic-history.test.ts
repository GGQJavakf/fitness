import { afterEach, describe, expect, it, vi } from 'vitest'

import { formatLocalDateTime, toWorkoutHistoryCard } from '../src/application/history'

describe('basic workout history', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders completed facts without inventing progression conclusions', () => {
    const card = toWorkoutHistoryCard({
      sessionId: 'session-1', trainingDayCode: 'DAY_A', trainingDayName: '全身 A', status: 'COMPLETED',
      startedAt: '2026-07-24T08:00:00Z', completedAt: '2026-07-24T09:00:00Z',
      completedWorkSets: 9, completedVolumeKg: 1234.5, completedReps: 90, usesExternalLoad: true,
    })
    expect(card).toEqual({
      id: 'session-1', trainingDayCode: 'DAY_A', title: '全身 A', statusLabel: '完整完成',
      timeLabel: formatLocalDateTime('2026-07-24T09:00:00Z'),
      factsLabel: '9 组 · 1234.5 KG·次', incomplete: false,
    })
    expect(card.timeLabel).not.toContain('UTC')
  })

  it('renders the instant in the supplied timezone instead of exposing raw UTC', () => {
    expect(formatLocalDateTime('2026-07-24T09:00:00Z', -8 * 60))
      .toBe('2026-07-24 17:00')
    expect(formatLocalDateTime('not-a-time', -8 * 60)).toBe('not-a-time')
  })

  it('uses the product China timezone when the WeChat simulator reports UTC', () => {
    vi.spyOn(Date.prototype, 'getTimezoneOffset').mockReturnValue(0)

    expect(formatLocalDateTime('2026-07-24T09:00:00Z')).toBe('2026-07-24 17:00')
  })

  it('labels an aborted workout as incomplete while preserving completed facts', () => {
    const card = toWorkoutHistoryCard({
      sessionId: 'session-2', trainingDayCode: 'DAY_B', trainingDayName: '全身 B', status: 'ABORTED',
      startedAt: '2026-07-24T08:00:00Z', completedAt: '2026-07-24T08:30:00Z',
      completedWorkSets: 2, completedVolumeKg: 200, completedReps: 16, usesExternalLoad: true,
    })
    expect(card.statusLabel).toBe('提前结束')
    expect(card.incomplete).toBe(true)
    expect(card.factsLabel).toBe('2 组 · 200 KG·次')
  })

  it('describes bodyweight history with completed repetitions', () => {
    const card = toWorkoutHistoryCard({
      sessionId: 'session-3', trainingDayCode: 'BODYWEIGHT_A', trainingDayName: '自重下肢与髋部 A', status: 'COMPLETED',
      startedAt: '2026-07-24T08:00:00Z', completedAt: '2026-07-24T08:30:00Z',
      completedWorkSets: 8, completedVolumeKg: 0, completedReps: 80, usesExternalLoad: false,
    })

    expect(card.factsLabel).toBe('8 组 · 共 80 次')
  })
})
