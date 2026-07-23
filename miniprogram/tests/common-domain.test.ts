import { describe, expect, it } from 'vitest'
import { createP0Weight } from '../src/domain/common/Weight'
import { createRuleReference } from '../src/domain/common/RuleReference'

describe('common domain values', () => {
  it('keeps P0 weights in fixed-point hundredths of a kilogram', () => {
    expect(createP0Weight(6250, 'barbell-main')).toEqual({
      hundredths: 6250,
      unit: 'KG',
      equipmentProfileId: 'barbell-main',
    })
    expect(() => createP0Weight(-1)).toThrow()
    expect(() => createP0Weight(1.5)).toThrow()
  })

  it('requires every version needed for deterministic recalculation', () => {
    expect(createRuleReference('rule-1', 'template-1', 'content-1')).toEqual({
      ruleVersion: 'rule-1',
      templateVersion: 'template-1',
      contentVersion: 'content-1',
    })
    expect(() => createRuleReference('', 'template-1', 'content-1')).toThrow()
  })
})
