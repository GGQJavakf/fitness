import type { AiGeneratedContent } from './ai'
import type {
  AiConsentPort,
  ValidatedAiContentGenerator,
} from './cloudbaseAi'
import type { WorkoutSessionSummary } from './history'

export interface WorkoutSummaryRequestDependencies {
  readonly aiEnabled: boolean
  readonly consent: AiConsentPort
  readonly getSummaryFacts: (sessionId: string) => Promise<WorkoutSessionSummary>
  readonly generate: ValidatedAiContentGenerator
  readonly fallback: (sessionId: string) => Promise<AiGeneratedContent>
}

export function createWorkoutSummaryRequest(
  dependencies: WorkoutSummaryRequestDependencies,
): (sessionId: string) => Promise<AiGeneratedContent> {
  return async (sessionId) => {
    const fallback = () => dependencies.fallback(sessionId)
    if (!dependencies.aiEnabled) return fallback()
    if (!await dependencies.consent.hasConsent('WORKOUT_SUMMARY')) return fallback()

    let facts: WorkoutSessionSummary
    try {
      facts = await dependencies.getSummaryFacts(sessionId)
    } catch {
      return fallback()
    }

    return dependencies.generate.generate('WORKOUT_SUMMARY', {
      sessionId,
      status: facts.status,
      completedWorkSets: facts.completedWorkSets,
      completedVolumeKg: facts.completedVolumeKg,
      reasonCodes: [],
      progressionConclusion: null,
    }, fallback)
  }
}
