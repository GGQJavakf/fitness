import { createNavigationUseCases } from '../../application/navigation'
import { createStartupUseCases } from '../../application/onboarding'
import { createFitnessApplication } from '../../application/useCases'
import { createVerifiedPrivacyUseCases } from '../../application/privacy'
import { createValidatedAiContentGenerator } from '../../application/cloudbaseAi'
import { WorkoutSyncService } from '../../application/use-cases/WorkoutSyncService'
import { WorkoutFlowService } from '../../application/use-cases/WorkoutFlowService'
import { FitnessApiClient } from '../../infrastructure/api/client'
import { createTelemetryReporter } from '../../infrastructure/telemetry/events'
import {
  createWeappLogin,
  createWeappNavigation,
  createWeappSessionStore,
  createWeappTransport,
  currentWeappRouteParameter,
} from './adapters'
import { createWechatWorkoutDraftStore } from './WechatStorageAdapter'
import {
  createWeappCloudBaseAiTextProvider,
  initializeWeappCloudBase,
} from './CloudBaseAiAdapter'

declare const __FITNESS_API_BASE_URL__: string
declare const __FITNESS_CLOUDBASE_ENV_ID__: string
declare const __FITNESS_CLOUDBASE_AI_MODEL__: string
declare const __FITNESS_CLOUDBASE_SERVICE_NAME__: string

const sessions = createWeappSessionStore()
const navigationPort = createWeappNavigation()
const reauthentication = createWeappLogin()
const api = new FitnessApiClient(
  __FITNESS_API_BASE_URL__,
  createWeappTransport({
    environmentId: __FITNESS_CLOUDBASE_ENV_ID__,
    serviceName: __FITNESS_CLOUDBASE_SERVICE_NAME__,
  }),
  sessions,
  () => navigationPort.replaceApp('LOGIN'),
)
const fitness = createFitnessApplication(api, api)
initializeWeappCloudBase(__FITNESS_CLOUDBASE_ENV_ID__)
const aiContent = createValidatedAiContentGenerator(
  createWeappCloudBaseAiTextProvider(__FITNESS_CLOUDBASE_AI_MODEL__),
)
const workoutDrafts = createWechatWorkoutDraftStore()
const startup = createStartupUseCases({
  sessionStore: sessions,
  wechatLogin: reauthentication,
  auth: { login: (code) => api.login(code) },
  workout: { hasActive: async () => (await workoutDrafts.loadActive()) !== null },
  profile: { exists: () => api.profileExists() },
  plan: { hasActivePlan: async () => (await api.getActivePlan()) !== null },
  navigation: {
    replace: (destination) => destination === 'LOGIN' || destination === 'HOME'
      ? Promise.resolve()
      : navigationPort.replaceApp(destination),
  },
})
const navigation = createNavigationUseCases(navigationPort)
const privacy = createVerifiedPrivacyUseCases(api, {
  getProof: async () => api.issueReauthenticationProof(await reauthentication.getCode()),
})
const clock = { nowUtc: () => new Date().toISOString() }
const workoutSync = new WorkoutSyncService(workoutDrafts, () => clock.nowUtc())
const workouts = new WorkoutFlowService(workoutDrafts, clock, api, api, api)
const telemetry = createTelemetryReporter()

export function getWeappApplication() {
  return {
    ...fitness,
    startup,
    navigation,
    privacy,
    workoutSync,
    workouts,
    telemetry,
    listSyncConflicts: () => api.listSyncConflicts(),
    resolveSyncConflict: (
      conflictId: string,
      request: Parameters<typeof api.resolveSyncConflict>[1],
    ) => api.resolveSyncConflict(conflictId, request),
    startWorkoutSession: (request: Parameters<typeof api.startWorkoutSession>[0]) => api.startWorkoutSession(request),
    listWorkoutHistory: (cursor?: string, limit?: number) => api.listHistory(cursor, limit),
    listProgressionRecommendations: () => api.listRecommendations('PENDING'),
    applyProgressionRecommendation: (
      id: string, expectedVersion: number, acceptedWeightKg: number, idempotencyKey: string,
    ) => api.applyRecommendation(id, expectedVersion, acceptedWeightKg, idempotencyKey),
    dismissProgressionRecommendation: (id: string) => api.dismissRecommendation(id),
    getExerciseTrend: (exerciseCode: string) => api.getExerciseTrend(exerciseCode),
    listExercises: () => api.listExercises(),
    getExercise: (idOrCode: string) => api.getExercise(idOrCode),
    getExercisePreferences: () => api.getPreferences(),
    saveExercisePreferences: (
      request: Parameters<typeof api.savePreferences>[0],
    ) => api.savePreferences(request),
    requestPlanExplanation: (candidateId: string) => {
      const candidate = fitness.getCandidate()
      if (!candidate || candidate.candidateId !== candidateId) {
        return api.requestPlanExplanation(candidateId)
      }
      return aiContent.generate(
        'PLAN_EXPLANATION',
        {
          trainingDayCount: candidate.days.length,
          days: candidate.days,
        },
        () => api.requestPlanExplanation(candidateId),
      )
    },
    hasActiveWorkout: async () => (await workoutDrafts.loadActive()) !== null,
    requestWorkoutSummary: async (sessionId: string) => {
      const fallback = () => api.requestWorkoutSummary(sessionId)
      try {
        const history = await api.listHistory(undefined, 100)
        const workout = history.items.find((item) => item.sessionId === sessionId)
        return workout
          ? aiContent.generate('WORKOUT_SUMMARY', {
            workout: {
              trainingDayCode: workout.trainingDayCode,
              status: workout.status,
              completedWorkSets: workout.completedWorkSets,
              completedVolumeKg: workout.completedVolumeKg,
              completedReps: workout.completedReps,
              usesExternalLoad: workout.usesExternalLoad,
            },
          }, fallback)
          : fallback()
      } catch {
        return fallback()
      }
    },
    routeParameter: (name: string) => currentWeappRouteParameter(name),
  }
}
