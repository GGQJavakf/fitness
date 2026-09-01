import { WorkoutFlowService } from '../../../application/use-cases/WorkoutFlowService'
import { createRetryableLazyValue } from '../retryableLazy'
import { getWeappFeatureCore } from '../featureCore'
import { currentWeappRouteParameter } from '../adapters'
import { createWechatWorkoutDraftStore } from '../WechatStorageAdapter'
import { createWechatTelemetryReporter } from '../WechatTelemetryReporter'
import { createProgressGenerationOperations } from './progressGenerationOperations'

function createProgressApplication() {
  const core = getWeappFeatureCore()
  const drafts = createWechatWorkoutDraftStore(core.localUserData)
  const workouts = new WorkoutFlowService(
    drafts,
    { nowUtc: () => new Date().toISOString() },
    core.api,
    core.api,
    core.api,
    core.api,
    core.userGeneration,
  )
  const generationOperations = createProgressGenerationOperations({
    userGeneration: core.userGeneration,
    workouts,
    api: core.api,
  })
  core.userScopedState.register(() => generationOperations.clearUserState())

  return {
    navigation: core.navigation,
    telemetry: createWechatTelemetryReporter(),
    workouts,
    listSyncConflicts: () => core.api.listSyncConflicts(),
    resolveSyncConflict: (
      conflictId: string,
      request: Parameters<typeof core.api.resolveSyncConflict>[1],
    ) => core.api.resolveSyncConflict(conflictId, request),
    reconcileSyncConflicts: generationOperations.reconcileSyncConflicts,
    resolveSyncConflictWithLocalState:
      generationOperations.resolveSyncConflictWithLocalState,
    listWorkoutHistory: (cursor?: string, limit?: number) => core.api.listHistory(cursor, limit),
    listProgressionRecommendations: (cursor?: string, limit?: number) => (
      core.api.listRecommendations('PENDING', cursor, limit)
    ),
    applyProgressionRecommendation: (
      id: string,
      expectedVersion: number,
      acceptedWeightKg: number,
      idempotencyKey: string,
    ) => core.api.applyRecommendation(id, expectedVersion, acceptedWeightKg, idempotencyKey),
    applyProgressionRecommendationForActivePlan:
      generationOperations.applyProgressionRecommendationForActivePlan,
    dismissProgressionRecommendation: (id: string) => core.api.dismissRecommendation(id),
    getExerciseTrend: (exerciseCode: string) => core.api.getExerciseTrend(exerciseCode),
    loadActivePlan: () => core.api.getActivePlan(),
    routeParameter: (name: string) => currentWeappRouteParameter(name),
  }
}

export const getProgressApplication = createRetryableLazyValue(createProgressApplication)
