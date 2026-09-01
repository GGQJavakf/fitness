import { WorkoutFlowService } from '../../../application/use-cases/WorkoutFlowService'
import { WorkoutStartCoordinator } from '../../../application/use-cases/WorkoutStartCoordinator'
import { createWorkoutSummaryRequest } from '../../../application/workoutSummary'
import { createRetryableLazyValue } from '../retryableLazy'
import { getWeappFeatureCore } from '../featureCore'
import {
  createWechatNextTrainingDaySelection,
  currentWeappRouteParameter,
} from '../adapters'
import { createWechatWorkoutDraftStore } from '../WechatStorageAdapter'
import { createWechatWorkoutStartIntentStore } from '../WechatWorkoutStartIntentStore'
import { createWechatTelemetryReporter } from '../WechatTelemetryReporter'
import { createWeappAiRuntime } from './aiRuntime'
import { createWorkoutGenerationOperations } from './workoutGenerationOperations'

function createWorkoutApplication() {
  const core = getWeappFeatureCore()
  const drafts = createWechatWorkoutDraftStore(core.localUserData)
  const clock = { nowUtc: () => new Date().toISOString() }
  const workouts = new WorkoutFlowService(
    drafts,
    clock,
    core.api,
    core.api,
    core.api,
    core.api,
    core.userGeneration,
  )
  const workoutStart = new WorkoutStartCoordinator(
    workouts,
    createWechatWorkoutStartIntentStore(core.localUserData),
    core.userGeneration,
  )
  const ai = createWeappAiRuntime(core.userGeneration)
  const requestWorkoutSummary = createWorkoutSummaryRequest({
    aiEnabled: ai.enabled,
    consent: ai.consent,
    getSummaryFacts: (sessionId) => core.api.getWorkoutSessionSummary(sessionId),
    generate: ai.content,
    fallback: (sessionId) => core.api.requestWorkoutSummary(sessionId),
    operationGuard: core.userGeneration,
  })
  const nextTrainingDaySelection = createWechatNextTrainingDaySelection(core.localUserData)
  const generationOperations = createWorkoutGenerationOperations({
    userGeneration: core.userGeneration,
    workouts,
    workoutStart,
    nextTrainingDaySelection,
    api: core.api,
    navigation: core.navigation,
    requestWorkoutSummary,
  })

  return {
    navigation: core.navigation,
    telemetry: createWechatTelemetryReporter(),
    workouts,
    workoutStart,
    nextTrainingDaySelection,
    ...generationOperations,
    loadActivePlan: () => core.api.getActivePlan(),
    listWorkoutHistory: (cursor?: string, limit?: number) => core.api.listHistory(cursor, limit),
    getWorkoutSessionSummary: (sessionId: string) => core.api.getWorkoutSessionSummary(sessionId),
    requestWorkoutSummary,
    getExerciseTrend: (exerciseCode: string) => core.api.getExerciseTrend(exerciseCode),
    getExercise: (idOrCode: string) => core.api.getExercise(idOrCode),
    routeParameter: (name: string) => currentWeappRouteParameter(name),
  }
}

export const getWorkoutApplication = createRetryableLazyValue(createWorkoutApplication)
