import { describe, expect, it } from 'vitest'

import {
  createRecommendationActionGate,
  toProgressionCard,
  type ProgressionRecommendationData,
} from '../src/application/progression'

function recommendation(
  decision: ProgressionRecommendationData['decision'],
  reasonCode: string,
  recommendedWeightKg = 42.5,
): ProgressionRecommendationData {
  return {
    id: `recommendation-${decision}`,
    exerciseCode: 'GOBLET_SQUAT',
    status: 'PENDING',
    decision,
    reasonCode,
    currentWeightKg: 40,
    recommendedWeightKg,
    algorithmVersion: 'double-progression-v1',
    createdAt: '2026-07-24T12:00:00Z',
  }
}

describe('progression recommendation presentation', () => {
  it.each([
    ['INCREASE', 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR', '建议加重', '40 KG → 42.5 KG'],
    ['KEEP', 'WITHIN_TARGET_RANGE', '保持重量', '继续使用 40 KG'],
    ['REDUCE', 'CONSECUTIVE_BELOW_MIN', '建议减重', '40 KG → 37.5 KG'],
    ['REVIEW', 'ANOMALOUS_INPUT', '需要确认', '本次不自动给出新重量'],
  ] as const)('maps %s using server-owned numbers', (decision, reason, title, weightLabel) => {
    const recommended = decision === 'REDUCE' ? 37.5 : 42.5
    const card = toProgressionCard(recommendation(decision, reason, recommended))

    expect(card.title).toBe(title)
    expect(card.weightLabel).toBe(weightLabel)
    expect(card.algorithmLabel).toBe('规则 double-progression-v1')
  })

  it('shows a lock explanation and never exposes an apply action', () => {
    const card = toProgressionCard(recommendation('KEEP', 'WEIGHT_USER_LOCKED', 40))

    expect(card.locked).toBe(true)
    expect(card.actionable).toBe(false)
    expect(card.reason).toContain('已锁定')
  })

  it('explains bodyweight progression without asking users to understand weight rules', () => {
    const card = toProgressionCard(recommendation('REVIEW', 'BODYWEIGHT_REQUIRES_CONFIRMATION', 0))

    expect(card.title).toBe('可以尝试动作进阶')
    expect(card.weightLabel).toBe('次数已达到当前上限')
    expect(card.reason).toContain('更难的动作变式')
    expect(card.actionable).toBe(false)
  })

  it('blocks repeated apply or dismiss clicks until the first action settles', () => {
    const gate = createRecommendationActionGate()

    expect(gate.tryStart('recommendation-1')).toBe(true)
    expect(gate.tryStart('recommendation-1')).toBe(false)
    gate.finish('recommendation-1')
    expect(gate.tryStart('recommendation-1')).toBe(true)
  })
})
