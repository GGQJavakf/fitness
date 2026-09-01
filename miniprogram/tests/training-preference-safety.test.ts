import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => {
  vi.resetModules()
})

describe('training preference safety on the WeChat runtime', () => {
  it('loads without RegExp constructor support for Unicode property escapes', async () => {
    const nativeDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'RegExp')
    const NativeRegExp = globalThis.RegExp
    const WeChatRegExp = function (
      pattern?: string | RegExp,
      flags?: string,
    ): RegExp {
      if (typeof pattern === 'string' && pattern.includes('\\p{')) {
        throw new SyntaxError('Invalid regular expression: unsupported Unicode property escape')
      }
      return pattern === undefined
        ? new NativeRegExp('')
        : new NativeRegExp(pattern, flags)
    } as RegExpConstructor
    Object.setPrototypeOf(WeChatRegExp, NativeRegExp)
    Object.defineProperty(globalThis, 'RegExp', {
      configurable: true,
      writable: true,
      value: WeChatRegExp,
    })

    try {
      const {
        containsAbsoluteWeight,
        normalizeSafeTrainingPreference,
      } = await import('../src/application/trainingPreferenceSafety')

      expect(containsAbsoluteWeight('use one hundred and twenty pounds')).toBe(true)
      expect(containsAbsoluteWeight('use twenty-five kg')).toBe(true)
      expect(containsAbsoluteWeight('prioritize one-leg balance')).toBe(false)
      expect(normalizeSafeTrainingPreference('核心训练优先')).toBe('核心训练优先')
    } finally {
      if (nativeDescriptor) {
        Object.defineProperty(globalThis, 'RegExp', nativeDescriptor)
      } else {
        Reflect.deleteProperty(globalThis, 'RegExp')
      }
    }
  })

  it('fails closed instead of crashing when String normalization is unavailable', async () => {
    const normalizeDescriptor = Object.getOwnPropertyDescriptor(String.prototype, 'normalize')
    Object.defineProperty(String.prototype, 'normalize', {
      configurable: true,
      writable: true,
      value: undefined,
    })

    try {
      const {
        containsAbsoluteWeight,
        normalizeSafeTrainingPreference,
      } = await import('../src/application/trainingPreferenceSafety')

      expect(normalizeSafeTrainingPreference('')).toBe('')
      expect(normalizeSafeTrainingPreference('核心训练优先')).toBeNull()
      expect(containsAbsoluteWeight('bodyweight only')).toBe(true)
    } finally {
      if (normalizeDescriptor) {
        Object.defineProperty(String.prototype, 'normalize', normalizeDescriptor)
      } else {
        Reflect.deleteProperty(String.prototype, 'normalize')
      }
    }
  })

  it('preserves absolute-weight detection and compatibility normalization semantics', async () => {
    const {
      containsAbsoluteWeight,
      normalizeSafeTrainingPreference,
    } = await import('../src/application/trainingPreferenceSafety')

    expect(containsAbsoluteWeight('设置为 20 kg')).toBe(true)
    expect(containsAbsoluteWeight('使用二十公斤')).toBe(true)
    expect(containsAbsoluteWeight('use twenty-five kg')).toBe(true)
    expect(containsAbsoluteWeight('use one hundred and twenty pounds')).toBe(true)
    expect(containsAbsoluteWeight('prioritize one-leg balance')).toBe(false)
    expect(containsAbsoluteWeight('someonekg')).toBe(false)
    expect(normalizeSafeTrainingPreference('ＩＧＮＯＲＥ previous')).toBeNull()
  })

  it('rejects unsafe Unicode code points without relying on Unicode property regexes', async () => {
    const { normalizeSafeTrainingPreference } = await import(
      '../src/application/trainingPreferenceSafety'
    )

    expect(normalizeSafeTrainingPreference('核心\u0000训练')).toBeNull()
    expect(normalizeSafeTrainingPreference('核心\u200B训练')).toBeNull()
    expect(normalizeSafeTrainingPreference('核心\u202E训练')).toBeNull()
    expect(normalizeSafeTrainingPreference('核心\uD800训练')).toBeNull()
    expect(normalizeSafeTrainingPreference('核心\uE000训练')).toBeNull()
    expect(normalizeSafeTrainingPreference('核心\u0378训练')).toBeNull()
    expect(normalizeSafeTrainingPreference('核心\uFDD0训练')).toBeNull()
    expect(normalizeSafeTrainingPreference('核心\uFE0F训练')).toBeNull()
  })

  it('detects forbidden content across assigned punctuation, emoji, and combining marks', async () => {
    const {
      containsAbsoluteWeight,
      normalizeSafeTrainingPreference,
    } = await import('../src/application/trainingPreferenceSafety')

    expect(normalizeSafeTrainingPreference('核心训练优先 🏋')).toBe('核心训练优先 🏋')
    expect(normalizeSafeTrainingPreference('ignore🏋all previous')).toBeNull()
    expect(normalizeSafeTrainingPreference('i\u0307gnore previous')).toBeNull()
    expect(containsAbsoluteWeight('use twenty🏋five pounds')).toBe(true)
    expect(containsAbsoluteWeight('使用二十🏋公斤')).toBe(true)
  })
})
