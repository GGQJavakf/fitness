import { createAccountLifecycleUseCases } from '../../../application/localPrivacyLifecycle'
import { createVerifiedPrivacyUseCases } from '../../../application/privacy'
import { createStartupUseCases } from '../../../application/startup'
import { createRetryableLazyValue } from '../retryableLazy'
import { getWeappFeatureCore } from '../featureCore'
import { createWechatWorkoutStartupStateAdapter } from './WechatWorkoutStartupStateAdapter'

export function createGenerationBoundReauthenticationProof(dependencies: {
  readonly userGeneration: {
    capture(): number
    assertCurrent(generation: number): void
  }
  readonly getCode: () => Promise<string>
  readonly issueProof: (code: string) => Promise<string>
}): { getProof(): Promise<string> } {
  async function awaitCurrent<T>(
    generation: number,
    operation: () => Promise<T>,
  ): Promise<T> {
    dependencies.userGeneration.assertCurrent(generation)
    try {
      const value = await operation()
      dependencies.userGeneration.assertCurrent(generation)
      return value
    } catch (error) {
      dependencies.userGeneration.assertCurrent(generation)
      throw error
    }
  }

  return {
    async getProof(): Promise<string> {
      const generation = dependencies.userGeneration.capture()
      const code = await awaitCurrent(generation, dependencies.getCode)
      return awaitCurrent(generation, () => dependencies.issueProof(code))
    },
  }
}

function createAccountApplication() {
  const core = getWeappFeatureCore()
  const workout = createWechatWorkoutStartupStateAdapter(core.localUserData)
  const login = createStartupUseCases({
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
  }).login
  const account = createAccountLifecycleUseCases({
    remote: {
      logout: () => core.api.logout(),
      prepareLogout: () => core.api.prepareLogout(),
    },
    localData: core.localUserData,
    navigation: { replaceLogin: () => core.navigationPort.replaceApp('LOGIN') },
    login,
    clearMemory: () => core.userScopedState.clearAll(),
    completeMemoryClear: () => core.userScopedState.completeClear(),
    userGeneration: core.userGeneration,
  })
  const privacy = createVerifiedPrivacyUseCases(core.api, createGenerationBoundReauthenticationProof({
    userGeneration: core.userGeneration,
    getCode: () => core.reauthentication.getCode(),
    issueProof: (code) => core.api.issueReauthenticationProof(code),
  }), {
    onAccessRevoked: async (status) => {
      await account.handleAccessRevoked(status)
    },
  }, core.userGeneration)

  return {
    navigation: core.navigation,
    account,
    privacy,
    listExercises: () => core.api.listExercises(),
    getExercisePreferences: () => core.api.getPreferences(),
    saveExercisePreferences: (
      request: Parameters<typeof core.api.savePreferences>[0],
    ) => core.api.savePreferences(request),
  }
}

export const getAccountApplication = createRetryableLazyValue(createAccountApplication)
