import { createNavigationUseCases } from '../../application/navigation'
import { createStartupUseCases } from '../../application/onboarding'
import { createFitnessApplication } from '../../application/useCases'
import { createVerifiedPrivacyUseCases } from '../../application/privacy'
import { FitnessApiClient } from '../../infrastructure/api/client'
import {
  createWeappLogin,
  createWeappNavigation,
  createWeappSessionStore,
  createWeappTransport,
} from './adapters'

const sessions = createWeappSessionStore()
const navigationPort = createWeappNavigation()
const reauthentication = createWeappLogin()
const api = new FitnessApiClient(
  process.env.TARO_APP_API_BASE_URL ?? 'http://127.0.0.1:8080',
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
  getProof: () => reauthentication.getCode(),
})

export function getWeappApplication() {
  return {
    ...fitness,
    startup,
    navigation,
    privacy,
  }
}
