import { afterEach, describe, expect, it, vi } from 'vitest'

import type { AiTextGenerationRequest } from '../src/application/cloudbaseAi'
import {
  createWeappCloudBaseAiTextProvider,
  initializeWeappCloudBase,
  resetWeappCloudBaseForTests,
  type WeappCloudBaseAiOptions,
} from '../src/platform/weapp/CloudBaseAiAdapter'

const request: AiTextGenerationRequest = {
  purpose: 'WORKOUT_SUMMARY',
  systemPrompt: 'system rules',
  factsJson: '{"completedWorkSets":3}',
}

function options(overrides: Partial<WeappCloudBaseAiOptions> = {}): WeappCloudBaseAiOptions {
  return {
    model: 'verified-model',
    providerGroup: 'cloudbase',
    releaseEnabled: true,
    approvalGranted: true,
    billingEligible: true,
    modelReady: true,
    consentPort: { hasConsent: vi.fn().mockResolvedValue(true) },
    ...overrides,
  }
}

describe('WeChat CloudBase AI adapter', () => {
  afterEach(() => {
    resetWeappCloudBaseForTests()
    Reflect.deleteProperty(globalThis, 'wx')
  })

  it('initializes once and calls an approved ready model after consent', async () => {
    const generateText = vi.fn().mockResolvedValue({
      choices: [{ message: { content: '{"summary":"ok"}' } }],
    })
    const createModel = vi.fn().mockReturnValue({ generateText })
    const init = vi.fn()
    Reflect.set(globalThis, 'wx', { cloud: { init, extend: { AI: { createModel } } } })

    initializeWeappCloudBase('fitness-env')
    initializeWeappCloudBase('fitness-env')
    const result = await createWeappCloudBaseAiTextProvider(options()).generate(request)

    expect(init).toHaveBeenCalledTimes(1)
    expect(createModel).toHaveBeenCalledWith('cloudbase')
    expect(generateText).toHaveBeenCalledWith({
      model: 'verified-model',
      messages: [
        { role: 'system', content: 'system rules' },
        { role: 'user', content: '{"completedWorkSets":3}' },
      ],
    })
    expect(result).toBe('{"summary":"ok"}')
  })

  it.each([
    ['release flag', { releaseEnabled: false }],
    ['approval', { approvalGranted: false }],
    ['billing eligibility', { billingEligible: false }],
    ['model readiness', { modelReady: false }],
  ] as const)('makes zero SDK calls when %s is absent', async (_label, override) => {
    const createModel = vi.fn()
    Reflect.set(globalThis, 'wx', {
      cloud: { init: vi.fn(), extend: { AI: { createModel } } },
    })
    initializeWeappCloudBase('fitness-env')

    await expect(createWeappCloudBaseAiTextProvider(options(override)).generate(request))
      .rejects.toMatchObject({
        category: 'billingEligible' in override && override.billingEligible === false
          ? 'ELIGIBILITY'
          : 'CONFIGURATION',
      })
    expect(createModel).not.toHaveBeenCalled()
  })

  it('makes zero SDK calls when consent is absent', async () => {
    const createModel = vi.fn()
    Reflect.set(globalThis, 'wx', {
      cloud: { init: vi.fn(), extend: { AI: { createModel } } },
    })
    initializeWeappCloudBase('fitness-env')

    const provider = createWeappCloudBaseAiTextProvider(options({
      consentPort: { hasConsent: vi.fn().mockResolvedValue(false) },
    }))
    await expect(provider.generate(request)).rejects.toMatchObject({ category: 'ELIGIBILITY' })
    expect(createModel).not.toHaveBeenCalled()
  })

  it('allows PLAN_GENERATION only with request-scoped explicit consent', async () => {
    const generateText = vi.fn().mockResolvedValue({
      choices: [{ message: { content: '{"name":"candidate","days":[]}' } }],
    })
    const createModel = vi.fn().mockReturnValue({ generateText })
    Reflect.set(globalThis, 'wx', {
      cloud: { init: vi.fn(), extend: { AI: { createModel } } },
    })
    initializeWeappCloudBase('fitness-env')
    const planRequest: AiTextGenerationRequest = {
      ...request,
      purpose: 'PLAN_GENERATION',
      explicitUserConsent: true,
    }

    const result = await createWeappCloudBaseAiTextProvider(options({
      model: 'hy3',
      consentPort: { hasConsent: vi.fn().mockResolvedValue(false) },
    })).generate(planRequest)

    expect(result).toContain('candidate')
    expect(createModel).toHaveBeenCalledWith('cloudbase')
  })

  it.each([
    ['provider group', { providerGroup: 'hunyuan-exp' }],
    ['model', { model: 'verified-model' }],
  ] as const)('makes zero SDK calls when the PLAN_GENERATION %s is not approved', async (
    _label,
    override,
  ) => {
    const createModel = vi.fn()
    Reflect.set(globalThis, 'wx', {
      cloud: { init: vi.fn(), extend: { AI: { createModel } } },
    })
    initializeWeappCloudBase('fitness-env')

    await expect(createWeappCloudBaseAiTextProvider(options(override)).generate({
      ...request,
      purpose: 'PLAN_GENERATION',
      explicitUserConsent: true,
    })).rejects.toMatchObject({
      category: 'CONFIGURATION',
      code: 'AI_PLAN_MODEL_NOT_APPROVED',
    })
    expect(createModel).not.toHaveBeenCalled()
  })

  it('makes zero SDK calls when PLAN_GENERATION lacks request-scoped consent', async () => {
    const createModel = vi.fn()
    Reflect.set(globalThis, 'wx', {
      cloud: { init: vi.fn(), extend: { AI: { createModel } } },
    })
    initializeWeappCloudBase('fitness-env')

    await expect(createWeappCloudBaseAiTextProvider(options({ model: 'hy3' })).generate({
      ...request,
      purpose: 'PLAN_GENERATION',
    })).rejects.toMatchObject({ code: 'AI_CONSENT_REQUIRED' })
    expect(createModel).not.toHaveBeenCalled()
  })

  it('classifies a missing SDK and an empty response as non-fallback diagnostics', async () => {
    initializeWeappCloudBase('fitness-env')
    await expect(createWeappCloudBaseAiTextProvider(options()).generate(request))
      .rejects.toMatchObject({ category: 'SDK' })

    Reflect.set(globalThis, 'wx', {
      cloud: {
        init: vi.fn(),
        extend: { AI: { createModel: vi.fn().mockReturnValue({
          generateText: vi.fn().mockResolvedValue({ choices: [] }),
        }) } },
      },
    })
    resetWeappCloudBaseForTests()
    initializeWeappCloudBase('fitness-env')
    await expect(createWeappCloudBaseAiTextProvider(options()).generate(request))
      .rejects.toMatchObject({ category: 'CONTRACT' })
  })

  it('preserves CloudBase initialization failure as an SDK diagnostic', async () => {
    const createModel = vi.fn()
    Reflect.set(globalThis, 'wx', {
      cloud: {
        init: vi.fn(() => { throw new Error('invalid environment') }),
        extend: { AI: { createModel } },
      },
    })

    initializeWeappCloudBase('fitness-env')
    await expect(createWeappCloudBaseAiTextProvider(options()).generate(request))
      .rejects.toMatchObject({ category: 'SDK', code: 'AI_SDK_UNAVAILABLE' })
    expect(createModel).not.toHaveBeenCalled()
  })

  it('classifies timeout and throttling as ordinary fallback diagnostics', async () => {
    vi.useFakeTimers()
    try {
      const generateText = vi.fn()
        .mockReturnValueOnce(new Promise(() => undefined))
        .mockRejectedValueOnce({ errCode: 429, errMsg: 'rate limited' })
      Reflect.set(globalThis, 'wx', {
        cloud: {
          init: vi.fn(),
          extend: { AI: { createModel: vi.fn().mockReturnValue({ generateText }) } },
        },
      })
      initializeWeappCloudBase('fitness-env')
      const provider = createWeappCloudBaseAiTextProvider(options(), 100)
      const timeout = provider.generate(request)
      const assertion = expect(timeout).rejects.toMatchObject({ category: 'TIMEOUT' })
      await vi.advanceTimersByTimeAsync(100)
      await assertion
      await expect(provider.generate(request)).rejects.toMatchObject({ category: 'THROTTLING' })
    } finally {
      vi.useRealTimers()
    }
  })
})
