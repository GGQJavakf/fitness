import Taro from '@tarojs/taro'

import type { NavigationParameters, PageDestination, PageNavigationPort } from '../../application/navigation'
import type { AppDestination, Session } from '../../application/startup'
import type {
  SessionAccessPort,
  RequestGenerationFence,
  TransportPort,
  TransportRequest,
  TransportResponse,
} from '../../infrastructure/api/client'
import {
  WEAPP_SESSION_STORAGE_KEY,
  WEAPP_WORKOUT_DRAFT_STORAGE_PREFIX,
  createWeappUserScopedDataLifecycle,
  type WeappUserScopedDataLifecycle,
} from './WechatUserScopedDataLifecycle'

export const WEAPP_REQUEST_TIMEOUT_MS = 20_000

interface CloudContainerRuntime {
  loadSubpackage?(options: {
    name: string
    success: () => void
    fail: (error: unknown) => void
  }): unknown
  cloud?: {
    callContainer?<T>(request: {
      config: { env: string }
      path: string
      method: string
      header: Record<string, string>
      data?: unknown
    }): Promise<{ statusCode?: number; data: T; header?: Record<string, unknown> }>
  }
}

declare const wx: CloudContainerRuntime | undefined

interface WeappPageRoute {
  path: string
  subpackage?: string
}

const pageRoutes: Record<PageDestination, WeappPageRoute> = {
  HOME: { path: '/subpackages/startup/pages/home/index', subpackage: 'startup' },
  ONBOARDING: { path: '/subpackages/planning/pages/onboarding/index', subpackage: 'planning' },
  PLAN_CANDIDATES: { path: '/subpackages/planning/pages/plan-candidates/index', subpackage: 'planning' },
  PLAN_PRESETS: { path: '/subpackages/planning/pages/plan-presets/index', subpackage: 'planning' },
  PLAN: { path: '/subpackages/planning/pages/plan/index', subpackage: 'planning' },
  PLAN_EDITOR: { path: '/subpackages/planning/pages/plan-editor/index', subpackage: 'planning' },
  MY: { path: '/subpackages/account/pages/my/index', subpackage: 'account' },
  WORKOUT_PREPARE: { path: '/subpackages/workout/pages/workout-prepare/index', subpackage: 'workout' },
  WORKOUT_SESSION: { path: '/subpackages/workout/pages/workout-session/index', subpackage: 'workout' },
  WORKOUT_SUMMARY: { path: '/subpackages/workout/pages/workout-summary/index', subpackage: 'workout' },
  SYNC_CONFLICTS: { path: '/subpackages/progress/pages/sync-conflicts/index', subpackage: 'progress' },
  HISTORY: { path: '/subpackages/progress/pages/history/index', subpackage: 'progress' },
  EXERCISE_TREND: { path: '/subpackages/progress/pages/exercise-trend/index', subpackage: 'progress' },
  EXERCISE_DETAIL: { path: '/subpackages/exercise-guide/pages/detail/index', subpackage: 'exercise-guide' },
  EXERCISE_PREFERENCES: { path: '/subpackages/account/pages/exercise-preferences/index', subpackage: 'account' },
}

const appPageRoutes: Record<AppDestination, WeappPageRoute> = {
  LOGIN: { path: '/presentation/pages/home/index' },
  HOME: pageRoutes.HOME,
  ONBOARDING: pageRoutes.ONBOARDING,
  PLAN: pageRoutes.PLAN,
  WORKOUT_SESSION: pageRoutes.WORKOUT_SESSION,
}

export function createWeappTransport(options: {
  environmentId?: string
  serviceName?: string
  requestTimeoutMs?: number
} = {}): TransportPort {
  const environmentId = options.environmentId?.trim() ?? ''
  const serviceName = options.serviceName?.trim() ?? ''
  const requestTimeoutMs = options.requestTimeoutMs ?? WEAPP_REQUEST_TIMEOUT_MS
  if (!Number.isSafeInteger(requestTimeoutMs) || requestTimeoutMs <= 0) {
    throw new TypeError('requestTimeoutMs must be a positive integer')
  }
  return {
    async request<T>(request: TransportRequest): Promise<TransportResponse<T>> {
      if (serviceName) {
        if (!environmentId) throw new Error('CloudBase environment is required for container calls')
        const cloud = cloudRuntime()?.cloud
        if (!cloud?.callContainer) throw new Error('CloudBase container transport is not available')
        const response = await withCloudBaseTimeout(
          cloud.callContainer<T>({
            config: { env: environmentId },
            path: containerPath(request.url),
            method: request.method,
            header: {
              ...request.headers,
              'X-WX-SERVICE': serviceName,
            },
            ...(request.body === undefined ? {} : { data: request.body }),
          }),
          requestTimeoutMs,
        )
        if (
          !Number.isInteger(response.statusCode)
          || response.statusCode === undefined
          || response.statusCode < 100
          || response.statusCode > 599
        ) {
          throw new Error('CloudBase container returned an invalid HTTP status code')
        }
        return {
          statusCode: response.statusCode,
          data: response.data,
          headers: normalizeResponseHeaders(response.header),
        }
      }
      const response = await Taro.request<T>({
        url: request.url,
        method: request.method,
        header: request.headers,
        timeout: requestTimeoutMs,
        ...(request.body === undefined ? {} : { data: request.body }),
      })
      return {
        statusCode: response.statusCode,
        data: response.data,
        headers: normalizeResponseHeaders(response.header),
      }
    },
  }
}

function normalizeResponseHeaders(value: unknown): Readonly<Record<string, string>> {
  if (typeof value !== 'object' || value === null) return {}
  const normalized: Record<string, string> = {}
  for (const [name, headerValue] of Object.entries(value)) {
    if (typeof headerValue === 'string') normalized[name.toLowerCase()] = headerValue
  }
  return normalized
}

function withCloudBaseTimeout<T>(request: Promise<T>, timeoutMs: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error('CloudBase 请求超时，请检查网络后重试')),
      timeoutMs,
    )
    request.then(
      (value) => {
        clearTimeout(timeout)
        resolve(value)
      },
      (error: unknown) => {
        clearTimeout(timeout)
        reject(error)
      },
    )
  })
}

function cloudRuntime(): CloudContainerRuntime | undefined {
  return typeof wx === 'undefined' ? undefined : wx
}

function containerPath(url: string): string {
  const schemeSeparator = url.indexOf('://')
  const pathStart = schemeSeparator < 0 ? url.indexOf('/') : url.indexOf('/', schemeSeparator + 3)
  return pathStart < 0 ? '/' : url.slice(pathStart)
}

export function createWeappSessionStore(
  lifecycle: WeappUserScopedDataLifecycle = createWeappUserScopedDataLifecycle(),
): SessionAccessPort {
  const store: SessionAccessPort = {
    async load(): Promise<Session | null> {
      try {
        const value = await Taro.getStorage<Session>({ key: WEAPP_SESSION_STORAGE_KEY })
        return isSession(value.data) ? value.data : null
      } catch (error) {
        if (isMissingStorageError(error)) return null
        throw error
      }
    },
    async save(session: Session): Promise<void> {
      await Taro.setStorage({ key: WEAPP_SESSION_STORAGE_KEY, data: session })
    },
    async clear(): Promise<void> {
      try {
        await Taro.removeStorage({ key: WEAPP_SESSION_STORAGE_KEY })
      } catch (error) {
        if (!isMissingStorageError(error)) throw error
      }
    },
  }
  return {
    load: () => lifecycle.runClearedSessionRead(() => store.load()),
    loadImmediately: () => {
      const value = Taro.getStorageSync<unknown>(WEAPP_SESSION_STORAGE_KEY)
      return isSession(value) ? value : null
    },
    save: (session) => lifecycle.runUserOperation(() => store.save(session)),
    clear: () => lifecycle.runUserOperation(() => store.clear()),
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

const nextTrainingDayStorageKey = `${WEAPP_WORKOUT_DRAFT_STORAGE_PREFIX}next-training-day.v1`

export function createWechatNextTrainingDaySelection(
  lifecycle: WeappUserScopedDataLifecycle = createWeappUserScopedDataLifecycle(),
) {
  return {
    remember: (trainingDayCode: string) => lifecycle.runUserOperation(async () => {
      const normalized = trainingDayCode.trim()
      if (!normalized || normalized.length > 128) {
        throw new TypeError('next training day code is invalid')
      }
      await Taro.setStorage({ key: nextTrainingDayStorageKey, data: normalized })
    }),

    consume: () => lifecycle.runUserOperation(async (): Promise<string | undefined> => {
      let value: unknown
      try {
        value = (await Taro.getStorage<unknown>({ key: nextTrainingDayStorageKey })).data
      } catch (error) {
        if (isMissingStorageError(error)) return undefined
        throw error
      }
      await Taro.removeStorage({ key: nextTrainingDayStorageKey })
      if (typeof value !== 'string') return undefined
      const normalized = value.trim()
      return normalized && normalized.length <= 128 ? normalized : undefined
    }),
  }
}

export function createWeappNavigation(
  userGeneration?: RequestGenerationFence,
): PageNavigationPort & {
  replaceApp(destination: AppDestination): Promise<void>
} {
  async function awaitCurrent<T>(
    generation: number | undefined,
    operation: () => Promise<T>,
  ): Promise<T> {
    if (generation !== undefined) userGeneration?.assertCurrent(generation)
    try {
      const result = await operation()
      if (generation !== undefined) userGeneration?.assertCurrent(generation)
      return result
    } catch (error) {
      if (generation !== undefined) userGeneration?.assertCurrent(generation)
      throw error
    }
  }

  return {
    async open(destination, parameters): Promise<void> {
      const generation = userGeneration?.capture()
      const route = pageRoutes[destination]
      await awaitCurrent(generation, () => loadRouteSubpackage(route))
      await awaitCurrent(
        generation,
        () => Taro.navigateTo({ url: routeUrl(route, parameters) }),
      )
    },
    async replace(destination, parameters): Promise<void> {
      const generation = userGeneration?.capture()
      const route = pageRoutes[destination]
      await awaitCurrent(generation, () => loadRouteSubpackage(route))
      await awaitCurrent(
        generation,
        () => Taro.redirectTo({ url: routeUrl(route, parameters) }),
      )
    },
    async back(): Promise<void> {
      const generation = userGeneration?.capture()
      await awaitCurrent(generation, () => Taro.navigateBack())
    },
    async replaceApp(destination): Promise<void> {
      const generation = userGeneration?.capture()
      const route = appPageRoutes[destination]
      await awaitCurrent(generation, () => loadRouteSubpackage(route))
      await awaitCurrent(generation, () => Taro.reLaunch({ url: route.path }))
    },
  }
}

export function currentWeappRouteParameter(name: string): string | undefined {
  const value = Taro.getCurrentInstance().router?.params?.[name]
  return typeof value === 'string' && value.trim() ? value : undefined
}

async function loadRouteSubpackage(route: WeappPageRoute): Promise<void> {
  const subpackage = route.subpackage
  if (!subpackage) return
  const runtime = cloudRuntime()
  const loadSubpackage = runtime?.loadSubpackage
  if (!loadSubpackage) return
  await new Promise<void>((resolve, reject) => {
    loadSubpackage.call(runtime, {
      name: subpackage,
      success: resolve,
      fail: reject,
    })
  })
}

function routeUrl(route: WeappPageRoute, parameters?: NavigationParameters): string {
  const entries = Object.entries(parameters ?? {})
  if (!entries.length) return route.path
  const query = entries
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&')
  return `${route.path}?${query}`
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
  const message = value instanceof Error
    ? value.message
    : typeof value === 'object' && value !== null && 'errMsg' in value
      ? String((value as { errMsg?: unknown }).errMsg ?? '')
      : ''
  return /(?:data )?not found/i.test(message)
}
