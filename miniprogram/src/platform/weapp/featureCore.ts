import { createNavigationUseCases } from '../../application/navigation'
import {
  FitnessApiClient,
  type AuthenticationFailureCode,
} from '../../infrastructure/api/client'
import {
  createWeappLogin,
  createWeappNavigation,
  createWeappSessionStore,
  createWeappTransport,
} from './adapters'
import { createRetryableLazyValue } from './retryableLazy'
import { getSharedPlatformKernel } from './sharedPlatformKernel'

declare const __FITNESS_API_BASE_URL__: string
declare const __FITNESS_CLOUDBASE_ENV_ID__: string
declare const __FITNESS_CLOUDBASE_SERVICE_NAME__: string

interface WechatCloudRuntime {
  cloud?: {
    init?(options: { env: string; traceUser: boolean }): void
  }
}

declare const wx: WechatCloudRuntime | undefined

function createWeappFeatureCore() {
  const shared = getSharedPlatformKernel()
  const sessions = createWeappSessionStore(shared.localUserData)
  const navigationPort = createWeappNavigation(shared.userGeneration)
  const reauthentication = createWeappLogin()
  let terminalAuthenticationFailure: {
    readonly sourceGeneration: number
    readonly promise: Promise<void>
  } | null = null

  async function clearPersistedUserState(
    reason: 'AUTHENTICATION_EXPIRED' | 'ACCESS_REVOKED',
  ): Promise<void> {
    let memoryClearError: unknown
    try {
      shared.userScopedState.clearAll()
    } catch (error) {
      memoryClearError = error
    }

    // purge() invalidates synchronously. The captured transition generation owns
    // only the remaining purge/navigation continuation, never a later login.
    const purge = shared.localUserData.purge(reason)
    const transitionGeneration = shared.userGeneration.capture()
    let purgeError: unknown
    try {
      await purge
      shared.userGeneration.assertCurrent(transitionGeneration)
    } catch (error) {
      shared.userGeneration.assertCurrent(transitionGeneration)
      purgeError = error
    }

    await navigationPort.replaceApp('LOGIN')
    shared.userGeneration.assertCurrent(transitionGeneration)
    if (memoryClearError !== undefined) throw memoryClearError
    if (purgeError !== undefined) throw purgeError
    shared.userScopedState.completeClear()
  }

  async function handleAuthenticationFailure(code: AuthenticationFailureCode): Promise<void> {
    if (code !== 'ACCESS_REVOKED') {
      await clearPersistedUserState('AUTHENTICATION_EXPIRED')
      return
    }
    const sourceGeneration = shared.userGeneration.capture()
    if (terminalAuthenticationFailure?.sourceGeneration === sourceGeneration) {
      return terminalAuthenticationFailure.promise
    }
    const currentFailure = {
      sourceGeneration,
      promise: clearPersistedUserState('ACCESS_REVOKED'),
    }
    terminalAuthenticationFailure = currentFailure
    try {
      await currentFailure.promise
    } finally {
      if (terminalAuthenticationFailure === currentFailure) {
        terminalAuthenticationFailure = null
      }
    }
  }

  initializeCloudBaseTransport(__FITNESS_CLOUDBASE_ENV_ID__)
  const api = new FitnessApiClient(
    __FITNESS_API_BASE_URL__,
    createWeappTransport({
      environmentId: __FITNESS_CLOUDBASE_ENV_ID__,
      serviceName: __FITNESS_CLOUDBASE_SERVICE_NAME__,
    }),
    sessions,
    handleAuthenticationFailure,
    shared.sessionRefresh,
    shared.userGeneration,
  )

  return {
    ...shared,
    api,
    sessions,
    navigationPort,
    navigation: createNavigationUseCases(navigationPort),
    reauthentication,
  }
}

export type WeappFeatureCore = ReturnType<typeof createWeappFeatureCore>

export const getWeappFeatureCore = createRetryableLazyValue(createWeappFeatureCore)

function initializeCloudBaseTransport(environmentId: string): void {
  const normalized = environmentId.trim()
  if (!normalized) return
  const runtime = typeof wx === 'undefined' ? undefined : wx
  try {
    runtime?.cloud?.init?.({ env: normalized, traceUser: true })
  } catch {
    // Transport calls surface their own actionable error if CloudBase remains unavailable.
  }
}
