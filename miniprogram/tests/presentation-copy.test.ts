import { describe, expect, it } from 'vitest'

import {
  exerciseDisplayName,
  experienceDisplayName,
  goalDisplayName,
  locationDisplayName,
  planFieldDisplayName,
  planIssueDisplayMessage,
  weightStatusDisplayName,
} from '../src/presentation/copy'

describe('presentation copy', () => {
  it('shows user-facing Chinese labels instead of domain enums', () => {
    expect(goalDisplayName('GENERAL_FITNESS')).toBe('一般健身')
    expect(goalDisplayName('FAT_LOSS')).toBe('减脂')
    expect(experienceDisplayName('INTERMEDIATE')).toBe('有训练经验')
    expect(locationDisplayName('GYM')).toBe('健身房')
    expect(weightStatusDisplayName('NEEDS_CALIBRATION')).toBe('首次训练时校准重量')
  })

  it('shows reviewed exercise names and a readable fallback', () => {
    expect(exerciseDisplayName('GOBLET_SQUAT')).toBe('高脚杯深蹲')
    expect(exerciseDisplayName('PRONE_W_RAISE')).toBe('俯卧 W 提拉')
    expect(exerciseDisplayName('PRONE_Y_RAISE')).toBe('俯卧 Y 提拉')
    expect(exerciseDisplayName('UNKNOWN_MOVEMENT')).toBe('Unknown movement')
  })

  it('turns rule reason codes into actionable copy', () => {
    expect(planIssueDisplayMessage('REST_OUT_OF_RANGE')).toContain('休息时间')
    expect(planIssueDisplayMessage('INITIAL_WEIGHT_NEEDS_CALIBRATION')).toContain('首次训练')
    expect(planIssueDisplayMessage('SESSION_DURATION_EXCEEDED')).toContain('时长')
  })

  it('turns stable lock paths into readable plan fields', () => {
    expect(planFieldDisplayName('/days/DAY_A/exercises/GOBLET_SQUAT/restSeconds'))
      .toBe('训练日 A · 高脚杯深蹲 · 休息时间')
  })
})
