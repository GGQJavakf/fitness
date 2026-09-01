import { describe, expect, it } from 'vitest'

import {
  allowedFrequenciesForSplit,
  planGenerationTrainingSplit,
  recommendedTrainingSplit,
  resolveTrainingSplit,
  validateOnboardingDraft,
} from '../src/application/onboarding'

describe('training split selection', () => {
  it('recommends full-body training for beginners and advanced splits by experience', () => {
    expect(recommendedTrainingSplit('BEGINNER')).toBe('FULL_BODY')
    expect(recommendedTrainingSplit('INTERMEDIATE')).toBe('PUSH_PULL_LEGS')
    expect(recommendedTrainingSplit('ADVANCED')).toBe('BODY_PART_FIVE_DAY')
  })

  it('exposes only compatible weekly frequencies for each split', () => {
    expect(allowedFrequenciesForSplit('FULL_BODY')).toEqual([2, 3])
    expect(allowedFrequenciesForSplit('UPPER_LOWER')).toEqual([2, 4])
    expect(allowedFrequenciesForSplit('PUSH_PULL_LEGS')).toEqual([3, 6])
    expect(allowedFrequenciesForSplit('BODY_PART_FIVE_DAY')).toEqual([5])
  })

  it('uses experience to disambiguate two or three days while preserving explicit selections', () => {
    expect(resolveTrainingSplit({ experience: 'BEGINNER', weeklyFrequency: 2 })).toBe('FULL_BODY')
    expect(resolveTrainingSplit({ experience: 'BEGINNER', weeklyFrequency: 3 })).toBe('FULL_BODY')
    expect(resolveTrainingSplit({ experience: 'INTERMEDIATE', weeklyFrequency: 3 })).toBe('PUSH_PULL_LEGS')
    expect(resolveTrainingSplit({
      experience: 'BEGINNER',
      weeklyFrequency: 3,
      trainingSplit: 'PUSH_PULL_LEGS',
    })).toBe('PUSH_PULL_LEGS')
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

  it('does not pre-filter bodyweight templates for home or empty-equipment generation requests', () => {
    expect(planGenerationTrainingSplit({
      equipment: [],
      experience: 'BEGINNER',
      location: 'GYM',
      trainingSplit: 'UPPER_LOWER',
      weeklyFrequency: 4,
    })).toBeUndefined()
    expect(planGenerationTrainingSplit({
      equipment: [{
        clientEquipmentKey: '00000000-0000-4000-8000-000000000001',
        equipmentType: 'DUMBBELL',
        minIncrement: { value: 2.5, unit: 'KG' },
        availableLevels: [{ value: 5, unit: 'KG' }],
      }],
      experience: 'BEGINNER',
      location: 'HOME',
      trainingSplit: 'UPPER_LOWER',
      weeklyFrequency: 4,
    })).toBeUndefined()
    expect(planGenerationTrainingSplit({
      equipment: [{
        clientEquipmentKey: '00000000-0000-4000-8000-000000000001',
        equipmentType: 'DUMBBELL',
        minIncrement: { value: 2.5, unit: 'KG' },
        availableLevels: [{ value: 5, unit: 'KG' }],
      }],
      experience: 'BEGINNER',
      location: 'GYM',
      trainingSplit: 'UPPER_LOWER',
      weeklyFrequency: 4,
    })).toBe('UPPER_LOWER')
  })
})
