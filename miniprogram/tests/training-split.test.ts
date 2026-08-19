import { describe, expect, it } from 'vitest'

import {
  allowedFrequenciesForSplit,
  recommendedTrainingSplit,
  resolveTrainingSplit,
  validateOnboardingDraft,
} from '../src/application/onboarding'

describe('training split selection', () => {
  it('recommends 2, 3, and 5-way splits by training experience', () => {
    expect(recommendedTrainingSplit('BEGINNER')).toBe('UPPER_LOWER')
    expect(recommendedTrainingSplit('INTERMEDIATE')).toBe('PUSH_PULL_LEGS')
    expect(recommendedTrainingSplit('ADVANCED')).toBe('BODY_PART_FIVE_DAY')
  })

  it('exposes only compatible weekly frequencies for each split', () => {
    expect(allowedFrequenciesForSplit('UPPER_LOWER')).toEqual([2, 4])
    expect(allowedFrequenciesForSplit('PUSH_PULL_LEGS')).toEqual([3, 6])
    expect(allowedFrequenciesForSplit('BODY_PART_FIVE_DAY')).toEqual([5])
  })

  it('keeps old drafts compatible by resolving their frequency and rejects explicit mismatches', () => {
    expect(resolveTrainingSplit({ experience: 'BEGINNER', weeklyFrequency: 3 })).toBe('PUSH_PULL_LEGS')
    expect(validateOnboardingDraft({
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS',
      experience: 'ADVANCED',
      trainingSplit: 'BODY_PART_FIVE_DAY',
      weeklyFrequency: 3,
      sessionMinutes: 45,
      location: 'GYM',
      equipment: [],
      preferences: [],
    })).toContain('当前分化与每周训练天数不匹配，请重新选择')
  })
})
