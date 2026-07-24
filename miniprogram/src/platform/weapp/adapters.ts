import Taro from '@tarojs/taro'

import type { NavigationParameters, PageDestination, PageNavigationPort } from '../../application/navigation'
import type { AppDestination, Session } from '../../application/onboarding'
import type {
  SessionAccessPort,
  TransportPort,
  TransportRequest,
  TransportResponse,
} from '../../infrastructure/api/client'

const sessionStorageKey = 'fitness.session.v1'

const pageRoutes: Record<PageDestination, string> = {
  HOME: '/presentation/pages/home/index',
  ONBOARDING: '/presentation/pages/onboarding/index',
  PLAN_CANDIDATES: '/presentation/pages/plan-candidates/index',
  PLAN_EDITOR: '/presentation/pages/plan-editor/index',
  PLAN: '/presentation/pages/plan/index',
  MY: '/presentation/pages/my/index',
  WORKOUT_PREPARE: '/presentation/pages/workout-prepare/index',
  WORKOUT_SESSION: '/presentation/pages/workout-session/index',
  WORKOUT_SUMMARY: '/presentation/pages/workout-summary/index',
  SYNC_CONFLICTS: '/presentation/pages/sync-conflicts/index',
  HISTORY: '/presentation/pages/history/index',
  EXERCISE_TREND: '/presentation/pages/exercise-trend/index',
}

export function createWeappTransport(): TransportPort {
  return {
    async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
      const response = await Taro.request<T>({
        url: request.url,
        method: request.method,
        header: request.headers,
        ...(request.body === undefined ? {} : { data: request.body }),
      })
      return {
        statusCode: response.statusCode,
        data: response.data,
      }
    },
  }
}

export function createWeappSessionStore(): SessionAccessPort {
  return {
    async load(): Promise<Session | null> {
      try {
        const value = await Taro.getStorage<Session>({ key: sessionStorageKey })
        return isSession(value.data) ? value.data : null
      } catch (error) {
        if (isMissingStorageError(error)) return null
        throw error
      }
    },
    async save(session: Session): Promise<void> {
      await Taro.setStorage({ key: sessionStorageKey, data: session })
    },
    async clear(): Promise<void> {
      try {
        await Taro.removeStorage({ key: sessionStorageKey })
      } catch (error) {
        if (!isMissingStorageError(error)) throw error
      }
    },
  }
}

export function createWeappLogin() {
  return {
    async getCode(): Promise<string> {
      const result = await Taro.login()
      if (!result.code) {
        throw new Error('微信登录暂不可用，请稍后重试')
      }
      return result.code
    },
  }
}

export function createWeappNavigation(): PageNavigationPort & {
  replaceApp(destination: AppDestination): Promise<void>
} {
  return {
    async open(destination, parameters): Promise<void> {
      await Taro.navigateTo({ url: routeUrl(destination, parameters) })
    },
    async replace(destination, parameters): Promise<void> {
      await Taro.redirectTo({ url: routeUrl(destination, parameters) })
    },
    async back(): Promise<void> {
      await Taro.navigateBack()
    },
    async replaceApp(destination): Promise<void> {
      const page = destination === 'ONBOARDING'
        ? 'ONBOARDING'
        : destination === 'PLAN'
          ? 'PLAN'
          : 'HOME'
      await Taro.redirectTo({ url: pageRoutes[page] })
    },
  }
}

export function currentWeappRouteParameter(name: string): string | undefined {
  const value = Taro.getCurrentInstance().router?.params?.[name]
  return typeof value === 'string' && value.trim() ? value : undefined
}

function routeUrl(destination: PageDestination, parameters?: NavigationParameters): string {
  const entries = Object.entries(parameters ?? {})
  if (!entries.length) return pageRoutes[destination]
  const query = entries
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&')
  return `${pageRoutes[destination]}?${query}`
}

function isSession(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const session = value as Partial<Session>
  return typeof session.accessToken === 'string'
    && typeof session.refreshToken === 'string'
    && typeof session.expiresAt === 'string'
}

function isMissingStorageError(value: unknown): boolean {
  if (typeof value !== 'object' || value === null || !('errMsg' in value)) {
    return false
  }
  const errMsg = (value as { errMsg?: unknown }).errMsg
  return typeof errMsg === 'string' && /(?:data )?not found/i.test(errMsg)
}
