import { describe, expect, it } from 'vitest'

import { toWeightInputValue } from '../src/presentation/workoutWeightInput'

describe('workout weight input', () => {
  it('keeps missing or invalid planned weights out of the input', () => {
    for (const value of [null, undefined, Number.NaN, Number.POSITIVE_INFINITY, -1]) {
      expect(toWeightInputValue(value)).toBeNull()
    }
  })

  it('formats valid planned weights for editing', () => {
    expect(toWeightInputValue(0)).toBe('0')
    expect(toWeightInputValue(22.5)).toBe('22.5')
  })
})
