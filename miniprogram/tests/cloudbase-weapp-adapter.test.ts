import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  createWeappCloudBaseAiTextProvider,
  initializeWeappCloudBase,
  resetWeappCloudBaseForTests,
} from '../src/platform/weapp/CloudBaseAiAdapter'

describe('WeChat CloudBase AI adapter', () => {
  afterEach(() => {
    resetWeappCloudBaseForTests()
    Reflect.deleteProperty(globalThis, 'wx')
  })

  it('initializes the configured environment once and returns generated text', async () => {
    const generateText = vi.fn().mockResolvedValue({
      choices: [{ message: { content: '{"summary":"ok"}' } }],
    })
    const init = vi.fn()
    Reflect.set(globalThis, 'wx', {
      cloud: {
        init,
        extend: {
          AI: {
            createModel: vi.fn().mockReturnValue({ generateText }),
          },
        },
      },
    })

    initializeWeappCloudBase('fitness-env')
    initializeWeappCloudBase('fitness-env')
    const provider = createWeappCloudBaseAiTextProvider('hy3')
    const result = await provider.generate({
      purpose: 'WORKOUT_SUMMARY',
      systemPrompt: 'system rules',
      factsJson: '{"completedWorkSets":3}',
    })

    expect(init).toHaveBeenCalledTimes(1)
    expect(init).toHaveBeenCalledWith({ env: 'fitness-env' })
    expect(generateText).toHaveBeenCalledWith({
      model: 'hy3',
      messages: [
        { role: 'system', content: 'system rules' },
        { role: 'user', content: '{"completedWorkSets":3}' },
      ],
    })
    expect(result).toBe('{"summary":"ok"}')
  })

  it('fails closed when the environment or assistant content is unavailable', async () => {
    expect(() => initializeWeappCloudBase('')).not.toThrow()
    const provider = createWeappCloudBaseAiTextProvider('hy3')
    await expect(provider.generate({
      purpose: 'PLAN_EXPLANATION',
      systemPrompt: 'system',
      factsJson: '{}',
    })).rejects.toThrow('CloudBase AI is not available')
  })

  it('does not break application startup when CloudBase initialization fails', async () => {
    Reflect.set(globalThis, 'wx', {
      cloud: {
        init: vi.fn(() => { throw new Error('invalid environment') }),
      },
    })

    expect(() => initializeWeappCloudBase('invalid-env')).not.toThrow()
    await expect(createWeappCloudBaseAiTextProvider('hy3').generate({
      purpose: 'WORKOUT_SUMMARY',
      systemPrompt: 'system',
      factsJson: '{}',
    })).rejects.toThrow('CloudBase AI is not available')
  })
})
