import { describe, expect, it } from 'vitest'

import {
  DEFAULT_FORMAL_WEIGHT_KG,
  chooseAutomaticWorkoutWeight,
} from '../src/application/automaticWorkoutWeight'

describe('automatic workout weight', () => {
  it('uses the newest valid completed weight without another confirmation', () => {
    expect(chooseAutomaticWorkoutWeight([
      { completedAt: '2026-08-10T10:00:00Z', topWeightKg: 12.5 },
      { completedAt: '2026-08-12T10:00:00Z', topWeightKg: 15 },
      { completedAt: '2026-08-13T10:00:00Z', topWeightKg: 0 },
      { completedAt: 'invalid-date', topWeightKg: 99 },
    ], 10)).toBe(15)
  })

  it('uses a plan target next, then a non-empty conservative default', () => {
    expect(chooseAutomaticWorkoutWeight([], 7.5)).toBe(7.5)
    expect(chooseAutomaticWorkoutWeight([])).toBe(DEFAULT_FORMAL_WEIGHT_KG)
    expect(DEFAULT_FORMAL_WEIGHT_KG).toBe(2.5)
  })
})
