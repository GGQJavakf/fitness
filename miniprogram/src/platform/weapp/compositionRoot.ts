import { createNavigationUseCases } from '../../application/navigation'
import { createStartupUseCases } from '../../application/onboarding'
import { createFitnessApplication } from '../../application/useCases'
import { createVerifiedPrivacyUseCases } from '../../application/privacy'
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

declare const __FITNESS_API_BASE_URL__: string

const sessions = createWeappSessionStore()
const navigationPort = createWeappNavigation()
const reauthentication = createWeappLogin()
const api = new FitnessApiClient(
  __FITNESS_API_BASE_URL__,
  createWeappTransport(),
  sessions,
  () => navigationPort.replaceApp('LOGIN'),
)
const fitness = createFitnessApplication(api, api)
const startup = createStartupUseCases({
  sessionStore: sessions,
  wechatLogin: reauthentication,
  auth: { login: (code) => api.login(code) },
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
const workoutDrafts = createWechatWorkoutDraftStore()
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
    requestPlanExplanation: (candidateId: string) => api.requestPlanExplanation(candidateId),
    requestWorkoutSummary: async () => {
      const draft = await workoutDrafts.loadActive()
      return draft?.sessionId ? api.requestWorkoutSummary(draft.sessionId) : null
    },
    routeParameter: (name: string) => currentWeappRouteParameter(name),
  }
}
