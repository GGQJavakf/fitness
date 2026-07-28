import { describe, expect, it, vi } from 'vitest'

import {
  createValidatedAiContentGenerator,
  type AiTextGenerationPort,
} from '../src/application/cloudbaseAi'
import type { AiGeneratedContent } from '../src/application/ai'

const fallbackContent: AiGeneratedContent = {
  status: 'DEGRADED',
  content: '规则模板',
  validationStatus: 'AI_DISABLED',
}

describe('validated CloudBase AI content generation', () => {
  it('accepts a closed JSON explanation that only repeats authoritative facts', async () => {
    const provider: AiTextGenerationPort = {
      generate: vi.fn().mockResolvedValue(JSON.stringify({
        summary: '本计划每周训练 3 天。',
        highlights: ['每个动作安排 3 组'],
        issues: [],
        nextActions: ['按计划执行并记录事实'],
        explanation: '规则引擎已经确定每组 8～12 次，AI 只解释安排。',
        safetyNotice: null,
      })),
    }
    const fallback = vi.fn().mockResolvedValue(fallbackContent)
    const generator = createValidatedAiContentGenerator(provider)

    await expect(generator.generate(
      'PLAN_EXPLANATION',
      {
        weeklyFrequency: 3,
        days: [{ exercises: [{ workSets: 3, repMin: 8, repMax: 12 }] }],
      },
      fallback,
    )).resolves.toMatchObject({
      status: 'READY',
      validationStatus: 'VALID',
      content: '规则引擎已经确定每组 8～12 次，AI 只解释安排。',
    })
    expect(fallback).not.toHaveBeenCalled()
  })

  it('uses the authoritative fallback when AI invents a number or changes the schema', async () => {
    const fallback = vi.fn().mockResolvedValue(fallbackContent)
    const provider: AiTextGenerationPort = {
      generate: vi.fn()
        .mockResolvedValueOnce(JSON.stringify({
          summary: '建议训练 7 天。',
          highlights: [],
          issues: [],
          nextActions: [],
          explanation: '这是额外推算。',
          safetyNotice: null,
        }))
        .mockResolvedValueOnce('```json\n{}\n```'),
    }
    const generator = createValidatedAiContentGenerator(provider)

    await expect(generator.generate(
      'PLAN_EXPLANATION',
      { weeklyFrequency: 3 },
      fallback,
    )).resolves.toEqual(fallbackContent)
    await expect(generator.generate(
      'WORKOUT_SUMMARY',
      { completedWorkSets: 3 },
      fallback,
    )).resolves.toEqual(fallbackContent)
    expect(fallback).toHaveBeenCalledTimes(2)
  })

  it('rejects Chinese or full-width numbers that could bypass fact validation', async () => {
    const fallback = vi.fn().mockResolvedValue(fallbackContent)
    const provider: AiTextGenerationPort = {
      generate: vi.fn().mockResolvedValue(JSON.stringify({
        summary: '建议每周训练七天。',
        highlights: [],
        issues: [],
        nextActions: [],
        explanation: '再增加３组。',
        safetyNotice: null,
      })),
    }

    await expect(createValidatedAiContentGenerator(provider).generate(
      'PLAN_EXPLANATION',
      { weeklyFrequency: 3 },
      fallback,
    )).resolves.toEqual(fallbackContent)
  })

  it('uses the fallback when CloudBase is unavailable', async () => {
    const provider: AiTextGenerationPort = {
      generate: vi.fn().mockRejectedValue(new Error('provider unavailable')),
    }
    const fallback = vi.fn().mockResolvedValue(fallbackContent)

    await expect(createValidatedAiContentGenerator(provider).generate(
      'WORKOUT_SUMMARY',
      { completedWorkSets: 3 },
      fallback,
    )).resolves.toEqual(fallbackContent)
  })
})
