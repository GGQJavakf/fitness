export interface RuleReference {
  readonly ruleVersion: string
  readonly templateVersion: string
  readonly contentVersion: string
}

export function createRuleReference(
  ruleVersion: string,
  templateVersion: string,
  contentVersion: string,
): RuleReference {
  for (const [name, value] of Object.entries({
    ruleVersion,
    templateVersion,
    contentVersion,
  })) {
    if (value.trim() === '') {
      throw new Error(`${name} must not be blank`)
    }
  }

  return { ruleVersion, templateVersion, contentVersion }
}
