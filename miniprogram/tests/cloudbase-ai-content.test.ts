import { describe, expect, it, vi } from 'vitest'

import {
  AiDiagnosticError,
  createValidatedAiContentGenerator,
  type AiTextGenerationPort,
} from '../src/application/cloudbaseAi'
import type { AiGeneratedContent } from '../src/application/ai'

const fallbackContent: AiGeneratedContent = {
  status: 'DEGRADED',
  content: '规则模板',
  validationStatus: 'AI_DISABLED',
}

const validSummary = JSON.stringify({
  summary: '本计划包含 3 个正式组。',
  highlights: ['每个动作安排 3 组'],
  issues: [],
  nextActions: ['按计划执行并记录事实'],
  explanation: '规则引擎已经确定每组 8～12 次，AI 只解释安排。',
  safetyNotice: null,
})

describe('validated CloudBase AI content generation', () => {
  it('projects plan facts through an allowlist and strips identifiers, tokens, contacts and free text', async () => {
    const provider: AiTextGenerationPort = { generate: vi.fn().mockResolvedValue(validSummary) }
    const generator = createValidatedAiContentGenerator(provider)

    await generator.generate('PLAN_EXPLANATION', {
      candidateId: 'candidate-anonymous',
      exercises: [{
        exerciseCode: 'SQUAT', workSets: 3, repMin: 8, repMax: 12,
        restSeconds: 75, weightStatus: 'NEEDS_CALIBRATION',
        name: 'must be removed',
      }],
      ruleReference: { ruleVersion: '1.3.0', templateVersion: '1.3.0', contentVersion: '1.3.0' },
      openid: 'private-openid',
      accessToken: 'private-token',
      phone: '13800138000',
      rawProfile: { name: 'private-name' },
      additionalRequirements: 'unrestricted free text',
    }, vi.fn().mockResolvedValue(fallbackContent))

    const sent = JSON.parse(vi.mocked(provider.generate).mock.calls[0][0].factsJson)
    expect(sent).toEqual({
      candidateId: 'candidate-anonymous',
      exercises: [{
        exerciseCode: 'SQUAT', workSets: 3, repMin: 8, repMax: 12,
        restSeconds: 75, weightStatus: 'NEEDS_CALIBRATION',
      }],
      ruleReference: { ruleVersion: '1.3.0', templateVersion: '1.3.0', contentVersion: '1.3.0' },
    })
    expect(vi.mocked(provider.generate).mock.calls[0][0].factsJson)
      .not.toMatch(/private|13800138000|free text|rawProfile/)
  })

  it.each(['TIMEOUT', 'THROTTLING', 'TRANSIENT'] as const)(
    'uses ordinary fallback only for %s',
    async (category) => {
      const provider: AiTextGenerationPort = {
        generate: vi.fn().mockRejectedValue(new AiDiagnosticError(category, `AI_${category}`)),
      }
      const fallback = vi.fn().mockResolvedValue(fallbackContent)
      await expect(createValidatedAiContentGenerator(provider).generate(
        'WORKOUT_SUMMARY',
        {
          sessionId: 'anonymous-session', status: 'COMPLETED', completedWorkSets: 3,
          completedVolumeKg: 0, reasonCodes: [], progressionConclusion: null,
        },
        fallback,
      )).resolves.toEqual(fallbackContent)
      expect(fallback).toHaveBeenCalledOnce()
    },
  )

  it.each(['CONFIGURATION', 'ELIGIBILITY', 'SDK', 'CONTRACT', 'UNSAFE_OUTPUT'] as const)(
    'preserves %s diagnostics instead of hiding them behind fallback',
    async (category) => {
      const provider: AiTextGenerationPort = {
        generate: vi.fn().mockRejectedValue(new AiDiagnosticError(category, `AI_${category}`)),
      }
      const fallback = vi.fn().mockResolvedValue(fallbackContent)
      await expect(createValidatedAiContentGenerator(provider).generate(
        'WORKOUT_SUMMARY',
        {
          sessionId: 'anonymous-session', status: 'COMPLETED', completedWorkSets: 3,
          completedVolumeKg: 0, reasonCodes: [], progressionConclusion: null,
        },
        fallback,
      )).rejects.toMatchObject({ category })
      expect(fallback).not.toHaveBeenCalled()
    },
  )

  it('classifies malformed response contracts and unsafe invented numbers', async () => {
    const fallback = vi.fn().mockResolvedValue(fallbackContent)
    const provider: AiTextGenerationPort = {
      generate: vi.fn()
        .mockResolvedValueOnce('```json\n{}\n```')
        .mockResolvedValueOnce(JSON.stringify({
          summary: '建议训练 7 天。', highlights: [], issues: [], nextActions: [],
          explanation: '这是额外推算。', safetyNotice: null,
        })),
    }
    const generator = createValidatedAiContentGenerator(provider)
    const facts = {
      sessionId: 'anonymous-session', status: 'COMPLETED', completedWorkSets: 3,
      completedVolumeKg: 0, reasonCodes: [], progressionConclusion: null,
    }
    await expect(generator.generate('WORKOUT_SUMMARY', facts, fallback))
      .rejects.toMatchObject({ category: 'CONTRACT' })
    await expect(generator.generate('WORKOUT_SUMMARY', facts, fallback))
      .rejects.toMatchObject({ category: 'UNSAFE_OUTPUT' })
    expect(fallback).not.toHaveBeenCalled()
  })

  it('does not treat digits embedded in identifiers or version strings as approved training numbers', async () => {
    const provider: AiTextGenerationPort = {
      generate: vi.fn().mockResolvedValue(JSON.stringify({
        summary: '建议完成 3 个正式组。', highlights: [], issues: [], nextActions: [],
        explanation: '这是从版本号推算出的训练数字。', safetyNotice: null,
      })),
    }
    const generator = createValidatedAiContentGenerator(provider)

    await expect(generator.generate('PLAN_EXPLANATION', {
      candidateId: 'candidate-3',
      exercises: [{ exerciseCode: 'ROW_3', weightStatus: 'NEEDS_CALIBRATION' }],
      ruleReference: {
        ruleVersion: '1.3.0', templateVersion: '1.3.0', contentVersion: '1.3.0',
      },
    }, vi.fn().mockResolvedValue(fallbackContent)))
      .rejects.toMatchObject({ category: 'UNSAFE_OUTPUT', code: 'AI_UNSAFE_OUTPUT' })
  })
})
