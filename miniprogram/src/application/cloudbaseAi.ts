import type { AiGeneratedContent, AiStructuredSummary } from './ai'

export type AiGenerationPurpose = 'PLAN_EXPLANATION' | 'WORKOUT_SUMMARY'

export interface AiTextGenerationRequest {
  purpose: AiGenerationPurpose
  systemPrompt: string
  factsJson: string
}

export interface AiTextGenerationPort {
  generate(request: AiTextGenerationRequest): Promise<string>
}

export interface ValidatedAiContentGenerator {
  generate(
    purpose: AiGenerationPurpose,
    facts: Record<string, unknown>,
    fallback: () => Promise<AiGeneratedContent>,
  ): Promise<AiGeneratedContent>
}

const SUMMARY_FIELDS = [
  'explanation',
  'highlights',
  'issues',
  'nextActions',
  'safetyNotice',
  'summary',
] as const
const NUMBER_PATTERN = /[+-]?\d{1,6}(?:\.\d{1,3})?/g
const NON_ARABIC_NUMBER_PATTERN = /[０-９零〇一二两三四五六七八九十百千万亿]/
const UNSAFE_MARKERS = [
  '忽略之前',
  '忽略以上',
  '系统提示词',
  '开发者消息',
  'ignore previous',
  'system prompt',
  'api key',
  'access token',
  '医疗诊断',
  '康复处方',
]

export function createValidatedAiContentGenerator(
  provider: AiTextGenerationPort,
): ValidatedAiContentGenerator {
  return {
    async generate(purpose, facts, fallback) {
      try {
        const factsJson = JSON.stringify(facts)
        const raw = await provider.generate({
          purpose,
          systemPrompt: systemPrompt(purpose),
          factsJson,
        })
        const summary = validateSummary(raw, collectNumbers(facts))
        if (!summary) return fallback()
        return {
          status: 'READY',
          content: summary.explanation,
          validationStatus: 'VALID',
          structured: summary,
        }
      } catch {
        return fallback()
      }
    },
  }
}

function systemPrompt(purpose: AiGenerationPurpose): string {
  const context = purpose === 'PLAN_EXPLANATION' ? '候选训练计划' : '已完成训练总结'
  return [
    `你是 AI 健身助手的${context}模块。`,
    '只使用用户消息中的事实，不提供医疗诊断或康复处方。',
    '关键数字、训练处方和进阶结论由确定性规则引擎产生；不得新增、推算或修改任何数字，不得改变规则结论，不得覆盖用户锁定字段。',
    '所有数字必须使用半角阿拉伯数字，不得使用中文或全角数字。',
    '只输出一个 JSON 对象，不要 Markdown、代码围栏或额外文字。',
    'JSON 必须且只能包含：{"summary":"非空字符串","highlights":["字符串"],"issues":["字符串"],"nextActions":["字符串"],"explanation":"非空字符串","safetyNotice":null或"字符串"}。',
    'highlights、issues、nextActions 各最多 5 项；没有安全提示时 safetyNotice 必须为 null。',
  ].join('\n')
}

function validateSummary(raw: string, allowedNumbers: Set<string>): AiStructuredSummary | null {
  if (!raw || raw.length > 4000) return null
  try {
    const value: unknown = JSON.parse(raw)
    if (!isRecord(value)) return null
    if (Object.keys(value).sort().join('|') !== [...SUMMARY_FIELDS].sort().join('|')) return null
    const summary = boundedText(value.summary, 300)
    const highlights = boundedTextArray(value.highlights)
    const issues = boundedTextArray(value.issues)
    const nextActions = boundedTextArray(value.nextActions)
    const explanation = boundedText(value.explanation, 500)
    const safetyNotice = value.safetyNotice === null
      ? null
      : boundedText(value.safetyNotice, 240)
    if (!summary || !highlights || !issues || !nextActions || !explanation
      || (value.safetyNotice !== null && !safetyNotice)) return null
    const result: AiStructuredSummary = {
      summary,
      highlights,
      issues,
      nextActions,
      explanation,
      safetyNotice,
    }
    const allText = [
      summary,
      ...highlights,
      ...issues,
      ...nextActions,
      explanation,
      safetyNotice ?? '',
    ].join('\n')
    if (allText.length > 1800 || isUnsafe(allText)
      || NON_ARABIC_NUMBER_PATTERN.test(allText)
      || hasNumericConflict(allText, allowedNumbers)) {
      return null
    }
    return result
  } catch {
    return null
  }
}

function boundedText(value: unknown, maxLength: number): string | null {
  if (typeof value !== 'string') return null
  const text = value.trim()
  return text.length > 0 && text.length <= maxLength ? text : null
}

function boundedTextArray(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > 5) return null
  const items = value.map((item) => boundedText(item, 160))
  return items.every((item): item is string => item !== null) ? items : null
}

function collectNumbers(value: unknown, result = new Set<string>()): Set<string> {
  if (typeof value === 'number' && Number.isFinite(value)) {
    result.add(normalizeNumber(value))
  } else if (typeof value === 'string') {
    for (const match of value.match(NUMBER_PATTERN) ?? []) {
      result.add(normalizeNumber(Number(match)))
    }
  } else if (Array.isArray(value)) {
    value.forEach((item) => collectNumbers(item, result))
  } else if (isRecord(value)) {
    Object.values(value).forEach((item) => collectNumbers(item, result))
  }
  return result
}

function hasNumericConflict(text: string, allowedNumbers: Set<string>): boolean {
  for (const match of text.match(NUMBER_PATTERN) ?? []) {
    if (!allowedNumbers.has(normalizeNumber(Number(match)))) return true
  }
  return false
}

function normalizeNumber(value: number): string {
  return String(Number(value.toFixed(3)))
}

function isUnsafe(text: string): boolean {
  const normalized = text.toLowerCase()
  return UNSAFE_MARKERS.some((marker) => normalized.includes(marker))
    || /1[3-9]\d{9}/.test(text)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
