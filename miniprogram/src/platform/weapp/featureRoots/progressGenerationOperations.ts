import { ApplicationError } from '../../../application/errors'
import type { ActivePlanData } from '../../../application/models'
import type {
  WorkoutConflictResolutionIntent,
  WorkoutConflictResolutionResult,
} from '../../../application/ports/WorkoutConflictResolutionPort'
import {
  progressionApplyIdempotencyKey,
  type ProgressionRecommendationData,
} from '../../../application/progression'
import type { components } from '../../../infrastructure/api/schema.generated'
import type { UserGenerationLease } from '../sharedPlatformKernel'

type SyncConflictData = components['schemas']['SyncConflictData']
type ResolveSyncConflictRequest = components['schemas']['ResolveSyncConflictRequest']

interface ProgressGenerationDependencies {
  readonly userGeneration: UserGenerationLease
  readonly workouts: {
    pendingConflictResolutions(): Promise<readonly WorkoutConflictResolutionIntent[]>
    rememberConflictResolution(intent: WorkoutConflictResolutionIntent): Promise<boolean>
    convergeConflict(result: WorkoutConflictResolutionResult): Promise<unknown>
  }
  readonly api: {
    listSyncConflicts(): Promise<SyncConflictData[]>
    resolveSyncConflict(
      conflictId: string,
      request: ResolveSyncConflictRequest,
    ): Promise<WorkoutConflictResolutionResult>
    getActivePlan(): Promise<ActivePlanData | null>
    applyRecommendation(
      id: string,
      expectedVersion: number,
      acceptedWeightKg: number,
      idempotencyKey: string,
    ): Promise<ProgressionRecommendationData>
  }
}

interface ProgressionApplyAttempt {
  readonly generation: number
  readonly expectedVersion: number
  readonly acceptedWeightKg: number
  readonly idempotencyKey: string
}

export function createProgressGenerationOperations(
  dependencies: ProgressGenerationDependencies,
) {
  const progressionApplyAttempts = new Map<string, ProgressionApplyAttempt>()

  async function awaitCurrent<T>(
    generation: number,
    operation: () => Promise<T>,
  ): Promise<T> {
    dependencies.userGeneration.assertCurrent(generation)
    try {
      const result = await operation()
      dependencies.userGeneration.assertCurrent(generation)
      return result
    } catch (error) {
      dependencies.userGeneration.assertCurrent(generation)
      throw error
    }
  }

  async function resolveIntent(
    generation: number,
    intent: WorkoutConflictResolutionIntent,
  ): Promise<WorkoutConflictResolutionResult> {
    const result = await awaitCurrent(
      generation,
      () => dependencies.api.resolveSyncConflict(intent.conflictId, {
        resolution: intent.resolution,
        expectedVersion: intent.expectedConflictVersion,
      }),
    )
    await awaitCurrent(generation, () => dependencies.workouts.convergeConflict(result))
    return result
  }

  return {
    clearUserState(): void {
      progressionApplyAttempts.clear()
    },

    async reconcileSyncConflicts(): Promise<SyncConflictData[]> {
      const generation = dependencies.userGeneration.capture()
      const intents = await awaitCurrent(
        generation,
        () => dependencies.workouts.pendingConflictResolutions(),
      )
      for (const intent of intents) {
        await resolveIntent(generation, intent)
      }
      return awaitCurrent(generation, () => dependencies.api.listSyncConflicts())
    },

    async resolveSyncConflictWithLocalState(
      intent: WorkoutConflictResolutionIntent,
    ): Promise<WorkoutConflictResolutionResult> {
      const generation = dependencies.userGeneration.capture()
      await awaitCurrent(
        generation,
        () => dependencies.workouts.rememberConflictResolution(intent),
      )
      return resolveIntent(generation, intent)
    },

    async applyProgressionRecommendationForActivePlan(
      id: string,
      acceptedWeightKg: number,
    ): Promise<ProgressionRecommendationData> {
      const generation = dependencies.userGeneration.capture()
      let attempt = progressionApplyAttempts.get(id)
      if (attempt?.generation !== generation
        || attempt.acceptedWeightKg !== acceptedWeightKg) {
        const plan = await awaitCurrent(generation, () => dependencies.api.getActivePlan())
        if (!plan) throw new Error('active plan is missing')
        attempt = {
          generation,
          acceptedWeightKg,
          expectedVersion: plan.activeVersion.versionNumber,
          idempotencyKey: progressionApplyIdempotencyKey(
            id,
            acceptedWeightKg,
            plan.activeVersion.versionNumber,
          ),
        }
        progressionApplyAttempts.set(id, attempt)
      }

      try {
        const result = await awaitCurrent(
          generation,
          () => dependencies.api.applyRecommendation(
            id,
            attempt.expectedVersion,
            attempt.acceptedWeightKg,
            attempt.idempotencyKey,
          ),
        )
        if (progressionApplyAttempts.get(id) === attempt) {
          progressionApplyAttempts.delete(id)
        }
        return result
      } catch (error) {
        dependencies.userGeneration.assertCurrent(generation)
        if (!isUncertainRecommendationOutcome(error)
          && progressionApplyAttempts.get(id) === attempt) {
          progressionApplyAttempts.delete(id)
        }
        throw error
      }
    },
  }
}

function isUncertainRecommendationOutcome(error: unknown): boolean {
  return error instanceof ApplicationError
    && ['NETWORK_ERROR', 'INVALID_RESPONSE', 'INTERNAL_ERROR'].includes(error.code)
}
