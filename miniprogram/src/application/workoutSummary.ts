import type { AiGeneratedContent } from './ai'
import type {
  AiConsentPort,
  ValidatedAiContentGenerator,
} from './cloudbaseAi'
import type { WorkoutSessionSummary } from './history'

export interface WorkoutSummaryOperationGuard {
  capture(): number
  assertCurrent(generation: number): void
}

export interface WorkoutSummaryRequestDependencies {
  readonly aiEnabled: boolean
  readonly consent: AiConsentPort
  readonly getSummaryFacts: (sessionId: string) => Promise<WorkoutSessionSummary>
  readonly generate: ValidatedAiContentGenerator
  readonly fallback: (sessionId: string) => Promise<AiGeneratedContent>
  readonly operationGuard?: WorkoutSummaryOperationGuard
}

export function createWorkoutSummaryRequest(
  dependencies: WorkoutSummaryRequestDependencies,
): (sessionId: string) => Promise<AiGeneratedContent> {
  return async (sessionId) => {
    const generation = dependencies.operationGuard?.capture()
    const assertCurrent = (): void => {
      if (generation !== undefined) dependencies.operationGuard?.assertCurrent(generation)
    }
    const awaitCurrent = async <T>(operation: () => Promise<T>): Promise<T> => {
      assertCurrent()
      try {
        const result = await operation()
        assertCurrent()
        return result
      } catch (error) {
        // A generation change is more important than a provider/network error:
        // callers must never continue into the new user's fallback or API path.
        assertCurrent()
        throw error
      }
    }
    const fallback = () => awaitCurrent(() => dependencies.fallback(sessionId))
    if (!dependencies.aiEnabled) return fallback()
    if (!await awaitCurrent(() => dependencies.consent.hasConsent('WORKOUT_SUMMARY'))) {
      return fallback()
    }

    let facts: WorkoutSessionSummary
    try {
      facts = await awaitCurrent(() => dependencies.getSummaryFacts(sessionId))
    } catch {
      assertCurrent()
      return fallback()
    }

    return awaitCurrent(() => dependencies.generate.generate('WORKOUT_SUMMARY', {
        sessionId,
        status: facts.status,
        completedWorkSets: facts.completedWorkSets,
        completedVolumeKg: facts.completedVolumeKg,
        reasonCodes: [],
        progressionConclusion: null,
      }, fallback))
  }
}
