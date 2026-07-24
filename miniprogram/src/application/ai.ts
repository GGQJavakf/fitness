export type AiContentStatus = 'READY' | 'PENDING' | 'DEGRADED'

export interface AiStructuredSummary {
  summary: string
  highlights: string[]
  issues: string[]
  nextActions: string[]
  explanation: string
  safetyNotice?: string | null
}

export interface AiGeneratedContent {
  status: AiContentStatus
  content: string
  validationStatus: string
  structured?: AiStructuredSummary
}
