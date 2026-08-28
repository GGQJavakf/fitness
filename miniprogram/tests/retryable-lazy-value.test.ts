import { describe, expect, it, vi } from 'vitest'

import { createRetryableLazyValue } from '../src/platform/weapp/retryableLazy'

describe('retryable lazy value', () => {
  it('does not initialize before first access and caches a successful value', () => {
    const factory = vi.fn(() => ({ id: 'application' }))
    const getValue = createRetryableLazyValue(factory)

    expect(factory).not.toHaveBeenCalled()

    const first = getValue()
    const second = getValue()

    expect(first).toBe(second)
    expect(factory).toHaveBeenCalledOnce()
  })

  it('does not cache a synchronous initialization failure', () => {
    const application = { id: 'recovered' }
    const factory = vi.fn()
      .mockImplementationOnce(() => {
        throw new Error('runtime adapter unavailable')
      })
      .mockReturnValue(application)
    const getValue = createRetryableLazyValue(factory)

    expect(() => getValue()).toThrow('runtime adapter unavailable')
    expect(getValue()).toBe(application)
    expect(getValue()).toBe(application)
    expect(factory).toHaveBeenCalledTimes(2)
  })
})
