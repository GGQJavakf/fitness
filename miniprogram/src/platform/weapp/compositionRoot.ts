import { createNavigationUseCases } from '../../application/navigation'
import { createStartupUseCases } from '../../application/onboarding'
import { createFitnessApplication } from '../../application/useCases'
import { createVerifiedPrivacyUseCases } from '../../application/privacy'
import { createAccountLifecycleUseCases } from '../../application/localPrivacyLifecycle'
import {
  DEFAULT_DENY_AI_CONSENT_PORT,
  createValidatedAiContentGenerator,
  createValidatedAiPlanGenerator,
} from '../../application/cloudbaseAi'
import { WorkoutSyncService } from '../../application/use-cases/WorkoutSyncService'
import { WorkoutFlowService } from '../../application/use-cases/WorkoutFlowService'
import { WorkoutStartCoordinator } from '../../application/use-cases/WorkoutStartCoordinator'
import {
  FitnessApiClient,
  type AuthenticationFailureCode,
} from '../../infrastructure/api/client'
import { createWorkoutSummaryRequest } from '../../application/workoutSummary'
import {
  createWeappLogin,
  createWeappNavigation,
  createWechatNextTrainingDaySelection,
  createWeappSessionStore,
  createWeappTransport,
  currentWeappRouteParameter,
} from './adapters'
import { createWechatWorkoutDraftStore } from './WechatStorageAdapter'
import { createWechatWorkoutStartIntentStore } from './WechatWorkoutStartIntentStore'
import { createWechatTelemetryReporter } from './WechatTelemetryReporter'
import { createWeappUserScopedDataLifecycle } from './WechatUserScopedDataLifecycle'
import { currentWeappRuntimeConfigurationIssue } from './runtimeConfiguration'
import { WorkoutDraftRecoveryRequiredError } from '../../application/ports/WorkoutDraftStore'
import {
  createWeappCloudBaseAiTextProvider,
  initializeWeappCloudBase,
} from './CloudBaseAiAdapter'

declare const __FITNESS_API_BASE_URL__: string
declare const __FITNESS_CLOUDBASE_ENV_ID__: string
declare const __FITNESS_CLOUDBASE_AI_MODEL__: string
declare const __FITNESS_CLOUDBASE_AI_PROVIDER_GROUP__: string
declare const __FITNESS_CLOUDBASE_AI_ENABLED__: boolean
declare const __FITNESS_CLOUDBASE_AI_APPROVED__: boolean
declare const __FITNESS_CLOUDBASE_AI_ELIGIBLE__: boolean
declare const __FITNESS_CLOUDBASE_AI_MODEL_READY__: boolean
declare const __FITNESS_CLOUDBASE_SERVICE_NAME__: string

const localUserData = createWeappUserScopedDataLifecycle()
const startupConfigurationIssue = currentWeappRuntimeConfigurationIssue({
  apiBaseUrl: __FITNESS_API_BASE_URL__,
  cloudBaseServiceName: __FITNESS_CLOUDBASE_SERVICE_NAME__,
})
const sessions = createWeappSessionStore(localUserData)
const navigationPort = createWeappNavigation()
const reauthentication = createWeappLogin()
let handleAuthenticationFailure = async (_code: AuthenticationFailureCode): Promise<void> => {
  await navigationPort.replaceApp('LOGIN')
}
const api = new FitnessApiClient(
  __FITNESS_API_BASE_URL__,
  createWeappTransport({
    environmentId: __FITNESS_CLOUDBASE_ENV_ID__,
    serviceName: __FITNESS_CLOUDBASE_SERVICE_NAME__,
  }),
  sessions,
  (code) => handleAuthenticationFailure(code),
)
initializeWeappCloudBase(__FITNESS_CLOUDBASE_ENV_ID__)
const aiContentConsent = DEFAULT_DENY_AI_CONSENT_PORT
const aiTextProvider = createWeappCloudBaseAiTextProvider({
  model: __FITNESS_CLOUDBASE_AI_MODEL__,
  providerGroup: __FITNESS_CLOUDBASE_AI_PROVIDER_GROUP__,
  releaseEnabled: __FITNESS_CLOUDBASE_AI_ENABLED__,
  approvalGranted: __FITNESS_CLOUDBASE_AI_APPROVED__,
  billingEligible: __FITNESS_CLOUDBASE_AI_ELIGIBLE__,
  modelReady: __FITNESS_CLOUDBASE_AI_MODEL_READY__,
  consentPort: aiContentConsent,
})
const aiPlanGenerationAvailable = __FITNESS_CLOUDBASE_AI_ENABLED__
  && __FITNESS_CLOUDBASE_AI_APPROVED__
  && __FITNESS_CLOUDBASE_AI_ELIGIBLE__
  && __FITNESS_CLOUDBASE_AI_MODEL_READY__
  && __FITNESS_CLOUDBASE_ENV_ID__.trim().length > 0
  && __FITNESS_CLOUDBASE_AI_MODEL__.trim() === 'hy3'
  && __FITNESS_CLOUDBASE_AI_PROVIDER_GROUP__.trim() === 'cloudbase'
const fitness = createFitnessApplication(
  api,
  api,
  createValidatedAiPlanGenerator(aiTextProvider),
)
const aiContent = createValidatedAiContentGenerator(aiTextProvider)
const requestWorkoutSummary = createWorkoutSummaryRequest({
  aiEnabled: __FITNESS_CLOUDBASE_AI_ENABLED__,
  consent: aiContentConsent,
  getSummaryFacts: (sessionId) => api.getWorkoutSessionSummary(sessionId),
  generate: aiContent,
  fallback: (sessionId) => api.requestWorkoutSummary(sessionId),
})
const workoutDrafts = createWechatWorkoutDraftStore(localUserData)
const workoutStartIntents = createWechatWorkoutStartIntentStore(localUserData)
async function workoutStartupState(): Promise<'NONE' | 'ACTIVE' | 'RECOVERY_REQUIRED'> {
  try {
    return await workoutDrafts.loadActive() ? 'ACTIVE' : 'NONE'
  } catch (error) {
    if (error instanceof WorkoutDraftRecoveryRequiredError) return 'RECOVERY_REQUIRED'
    throw error
  }
}
const startup = createStartupUseCases({
  sessionStore: sessions,
  wechatLogin: reauthentication,
  auth: { login: (code) => api.login(code) },
  workout: {
    hasActive: async () => (await workoutStartupState()) !== 'NONE',
    getStartupState: workoutStartupState,
  },
  profile: { exists: () => api.profileExists() },
  plan: { hasActivePlan: async () => (await api.getActivePlan()) !== null },
  navigation: {
    replace: (destination) => destination === 'LOGIN' || destination === 'HOME'
      ? Promise.resolve()
      : navigationPort.replaceApp(destination),
  },
  localUserData,
})
const navigation = createNavigationUseCases(navigationPort)
const account = createAccountLifecycleUseCases({
  remote: { logout: () => api.logout() },
  localData: localUserData,
  navigation: { replaceLogin: () => navigationPort.replaceApp('LOGIN') },
  login: () => startup.login(),
  clearMemory: () => fitness.clearUserState(),
})
handleAuthenticationFailure = async (code) => {
  if (code === 'ACCESS_REVOKED') {
    await account.handleTerminalAuthenticationFailure(code)
    return
  }
  await navigationPort.replaceApp('LOGIN')
}
const privacy = createVerifiedPrivacyUseCases(api, {
  getProof: async () => api.issueReauthenticationProof(await reauthentication.getCode()),
}, {
  onAccessRevoked: async (status) => {
    await account.handleAccessRevoked(status)
  },
})
const clock = { nowUtc: () => new Date().toISOString() }
const workoutSync = new WorkoutSyncService(workoutDrafts, () => clock.nowUtc())
const workouts = new WorkoutFlowService(workoutDrafts, clock, api, api, api, api)
const workoutStart = new WorkoutStartCoordinator(workouts, workoutStartIntents)
const nextTrainingDaySelection = createWechatNextTrainingDaySelection(localUserData)
const telemetry = createWechatTelemetryReporter()

export function getWeappApplication() {
  return {
    ...fitness,
    startupConfigurationIssue,
    aiPlanGenerationAvailable,
    startup,
    navigation,
    account,
    privacy,
    workoutSync,
    workouts,
    workoutStart,
    nextTrainingDaySelection,
    telemetry,
    listSyncConflicts: () => api.listSyncConflicts(),
    resolveSyncConflict: (
      conflictId: string,
      request: Parameters<typeof api.resolveSyncConflict>[1],
    ) => api.resolveSyncConflict(conflictId, request),
    startWorkoutSession: (request: Parameters<typeof api.startWorkoutSession>[0]) => api.startWorkoutSession(request),
    activateWorkoutSession: (
      sessionId: string,
      expectedVersion: number,
    ) => api.activateWorkoutSession(sessionId, expectedVersion),
    listWorkoutHistory: (cursor?: string, limit?: number) => api.listHistory(cursor, limit),
    getWorkoutSessionSummary: (sessionId: string) => api.getWorkoutSessionSummary(sessionId),
    listProgressionRecommendations: (cursor?: string, limit?: number) => (
      api.listRecommendations('PENDING', cursor, limit)
    ),
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
      if (!__FITNESS_CLOUDBASE_AI_ENABLED__) return api.requestPlanExplanation(candidateId)
      const candidate = fitness.getCandidate()
      if (!candidate || candidate.candidateId !== candidateId) {
        return api.requestPlanExplanation(candidateId)
      }
      return aiContent.generate(
        'PLAN_EXPLANATION',
        {
          candidateId,
          exercises: candidate.days.flatMap((day) => day.exercises.map((exercise) => ({
            exerciseCode: exercise.exerciseCode,
            workSets: exercise.workSets,
          }))),
        },
        () => api.requestPlanExplanation(candidateId),
      )
    },
    hasActiveWorkout: async () => (await workoutStartupState()) !== 'NONE',
    requestWorkoutSummary,
    routeParameter: (name: string) => currentWeappRouteParameter(name),
  }
}
