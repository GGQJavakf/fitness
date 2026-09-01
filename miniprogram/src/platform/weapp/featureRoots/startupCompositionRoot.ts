import { createStartupUseCases } from '../../../application/startup'
import { currentWeappRuntimeConfigurationIssue } from '../runtimeConfiguration'
import { createRetryableLazyValue } from '../retryableLazy'
import { getWeappFeatureCore } from '../featureCore'
import { createWechatWorkoutStartupStateAdapter } from './WechatWorkoutStartupStateAdapter'

declare const __FITNESS_API_BASE_URL__: string
declare const __FITNESS_CLOUDBASE_SERVICE_NAME__: string

function createStartupApplication() {
  const core = getWeappFeatureCore()
  const workout = createWechatWorkoutStartupStateAdapter(core.localUserData)
  const startup = createStartupUseCases({
    sessionStore: core.sessions,
    wechatLogin: core.reauthentication,
    auth: { login: (code) => core.api.login(code) },
    workout,
    profile: { exists: () => core.api.profileExists() },
    plan: { hasActivePlan: async () => (await core.api.getActivePlan()) !== null },
    navigation: {
      replace: (destination) => destination === 'LOGIN' || destination === 'HOME'
        ? Promise.resolve()
        : core.navigationPort.replaceApp(destination),
    },
    localUserData: core.localUserData,
    userGeneration: core.userGeneration,
    clearUserState: () => core.userScopedState.clearAll(),
    completeUserStateClear: () => core.userScopedState.completeClear(),
  })
  return {
    startupConfigurationIssue: currentWeappRuntimeConfigurationIssue({
      apiBaseUrl: __FITNESS_API_BASE_URL__,
      cloudBaseServiceName: __FITNESS_CLOUDBASE_SERVICE_NAME__,
    }),
    startup,
    navigation: core.navigation,
    hasActiveWorkout: workout.hasActive,
  }
}

export const getStartupApplication = createRetryableLazyValue(createStartupApplication)
