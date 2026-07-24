import { ApplicationError, applicationErrorMessage } from '../../application/errors'
import type {
  ActivePlanData,
  CreatePlanVersionRequest,
  PlanCandidateGenerationData,
  PlanValidationData,
  PlanValidationDraft,
  PlanVersionResultData,
  RuleReference,
  UpdateEquipmentRequest,
  UpdatePreferencesRequest,
  UpdateProfileRequest,
} from '../../application/models'
import type {
  OnboardingPersistencePort,
  Session,
  VersionedResource,
} from '../../application/onboarding'
import type { PlanPersistencePort } from '../../application/ports'
import type { ApiErrorResponse, ApiResponse } from './generated'
import type { components } from './schema.generated'

type HttpMethod = 'GET' | 'POST' | 'PUT'

export interface TransportRequest {
  url: string
  method: HttpMethod
  headers: Record<string, string>
  body?: unknown
}

export interface TransportResponse<T> {
  statusCode: number
  data: T
}

export interface TransportPort {
  request<T>(request: TransportRequest): Promise<TransportResponse<T>>
}

export interface SessionAccessPort {
  load(): Promise<Session | null>
  save(session: Session): Promise<void>
  clear(): Promise<void>
}

type UserProfileResponse = components['schemas']['UserProfileResponse']
type EquipmentProfileResponse = components['schemas']['EquipmentProfileResponse']
type PreferenceProfileResponse = components['schemas']['PreferenceProfileResponse']
type CandidateResponse = components['schemas']['PlanCandidateGenerationResponse']
type ValidationResponse = components['schemas']['PlanValidationResponse']
type ActivePlanResponse = components['schemas']['ActivePlanResponse']
type VersionResultResponse = components['schemas']['PlanVersionResultResponse']

export class FitnessApiClient implements OnboardingPersistencePort, PlanPersistencePort {
  private readonly baseUrl: string

  constructor(
    baseUrl: string,
    private readonly transport: TransportPort,
    private readonly sessions: SessionAccessPort,
    private readonly onAuthenticationExpired?: () => Promise<void> | void,
  ) {
    this.baseUrl = normalizeBaseUrl(baseUrl)
  }

  async login(code: string): Promise<Session> {
    const response = await this.request<ApiResponse<Session>>(
      '/api/v1/auth/wechat/login',
      'POST',
      { code } satisfies components['schemas']['WechatLoginRequest'],
      false,
    )
    return requireData(response.data)
  }

  async getProfileVersion(): Promise<number | null> {
    return this.getVersionOrNull<UserProfileResponse>('/api/v1/profile')
  }

  async getEquipmentVersion(): Promise<number | null> {
    return this.getVersionOrNull<EquipmentProfileResponse>('/api/v1/profile/equipment')
  }

  async getPreferencesVersion(): Promise<number | null> {
    return this.getVersionOrNull<PreferenceProfileResponse>('/api/v1/profile/preferences')
  }

  async profileExists(): Promise<boolean> {
    return (await this.getProfileVersion()) !== null
  }

  async saveProfile(request: UpdateProfileRequest): Promise<VersionedResource> {
    const response = await this.request<UserProfileResponse>(
      '/api/v1/profile',
      'PUT',
      request satisfies components['schemas']['UpdateProfileRequest'],
    )
    return { version: requireData(response.data).version }
  }

  async saveEquipment(request: UpdateEquipmentRequest): Promise<VersionedResource> {
    const response = await this.request<EquipmentProfileResponse>(
      '/api/v1/profile/equipment',
      'PUT',
      request satisfies components['schemas']['UpdateEquipmentRequest'],
    )
    return { version: requireData(response.data).version }
  }

  async savePreferences(request: UpdatePreferencesRequest): Promise<VersionedResource> {
    const response = await this.request<PreferenceProfileResponse>(
      '/api/v1/profile/preferences',
      'PUT',
      request satisfies components['schemas']['UpdatePreferencesRequest'],
    )
    return { version: requireData(response.data).version }
  }

  async generateCandidate(
    request: components['schemas']['PlanCandidateRequest'],
  ): Promise<PlanCandidateGenerationData> {
    const response = await this.request<CandidateResponse>(
      '/api/v1/plans/candidates',
      'POST',
      request,
    )
    return requireData(response.data)
  }

  async validatePlan(
    plan: PlanValidationDraft,
    ruleReference: RuleReference,
  ): Promise<PlanValidationData> {
    const body = {
      plan,
      ruleReference,
    } satisfies components['schemas']['ValidatePlanRequest']
    const response = await this.request<ValidationResponse>('/api/v1/plans/validate', 'POST', body)
    return requireData(response.data)
  }

  async createInitialPlan(candidateId: string): Promise<ActivePlanData> {
    const response = await this.request<ActivePlanResponse>(
      '/api/v1/plans',
      'POST',
      { candidateId } satisfies components['schemas']['CreatePlanRequest'],
    )
    return requireData(response.data)
  }

  async getActivePlan(): Promise<ActivePlanData | null> {
    try {
      const response = await this.request<ActivePlanResponse>('/api/v1/plans/active', 'GET')
      return requireData(response.data)
    } catch (error) {
      if (error instanceof ApplicationError && error.code === 'RESOURCE_NOT_FOUND') {
        return null
      }
      throw error
    }
  }

  async createPlanVersion(
    planId: string,
    request: CreatePlanVersionRequest,
  ): Promise<PlanVersionResultData> {
    const response = await this.request<VersionResultResponse>(
      `/api/v1/plans/${encodeURIComponent(planId)}/versions`,
      'POST',
      request satisfies components['schemas']['CreatePlanVersionRequest'],
    )
    return requireData(response.data)
  }

  async previewRebalance(
    planId: string,
    request: Omit<CreatePlanVersionRequest, 'warningConfirmationToken'>,
  ): Promise<PlanVersionResultData> {
    const response = await this.request<VersionResultResponse>(
      `/api/v1/plans/${encodeURIComponent(planId)}/rebalance`,
      'POST',
      request satisfies components['schemas']['RebalancePlanRequest'],
    )
    return requireData(response.data)
  }

  private async getVersionOrNull<Response extends { data: { version: number } }>(
    path: string,
  ): Promise<number | null> {
    try {
      const response = await this.request<Response>(path, 'GET')
      return requireData(response.data).version
    } catch (error) {
      if (error instanceof ApplicationError && error.code === 'RESOURCE_NOT_FOUND') {
        return null
      }
      throw error
    }
  }

  private async request<Response>(
    path: string,
    method: HttpMethod,
    body?: unknown,
    authenticated = true,
  ): Promise<Response> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (authenticated) {
      const session = await this.sessions.load()
      if (!session) {
        throw new ApplicationError(
          'AUTHENTICATION_REQUIRED',
          applicationErrorMessage('AUTHENTICATION_REQUIRED'),
        )
      }
      headers.Authorization = `Bearer ${session.accessToken}`
    }

    let response: TransportResponse<unknown>
    try {
      response = await this.transport.request({
        url: `${this.baseUrl}${path}`,
        method,
        headers,
        ...(body === undefined ? {} : { body }),
      })
    } catch {
      throw new ApplicationError('NETWORK_ERROR', applicationErrorMessage('NETWORK_ERROR'), {
        retryable: true,
      })
    }

    if (response.statusCode >= 200 && response.statusCode < 300) {
      return response.data as Response
    }
    throw await this.mapError(response.statusCode, response.data)
  }

  private async mapError(statusCode: number, payload: unknown): Promise<ApplicationError> {
    const apiError = isApiErrorResponse(payload) ? payload.error : undefined
    const code = mapErrorCode(statusCode, apiError?.code)
    if (code === 'AUTHENTICATION_REQUIRED') {
      await this.sessions.clear()
      await this.onAuthenticationExpired?.()
    }
    return new ApplicationError(code, applicationErrorMessage(code), {
      retryable: apiError?.retryable ?? statusCode >= 500,
      fieldPaths: apiError?.fieldErrors?.map((field) => field.path) ?? [],
    })
  }
}

function normalizeBaseUrl(baseUrl: string): string {
  const normalized = baseUrl.trim().replace(/\/+$/, '')
  if (!/^https?:\/\//.test(normalized)) {
    throw new Error('API base URL must use http or https')
  }
  return normalized
}

function requireData<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
  }
  return value
}

function isApiErrorResponse(value: unknown): value is ApiErrorResponse {
  return typeof value === 'object'
    && value !== null
    && 'error' in value
    && typeof (value as { error?: unknown }).error === 'object'
}

function mapErrorCode(
  statusCode: number,
  serverCode?: components['schemas']['ErrorCode'],
): ApplicationError['code'] {
  switch (serverCode) {
    case 'AUTHENTICATION_REQUIRED': return 'AUTHENTICATION_REQUIRED'
    case 'ACCESS_DENIED': return 'ACCESS_DENIED'
    case 'RESOURCE_NOT_FOUND': return 'RESOURCE_NOT_FOUND'
    case 'VERSION_CONFLICT': return 'VERSION_CONFLICT'
    case 'VALIDATION_FAILED': return 'VALIDATION_FAILED'
    case 'PLAN_VALIDATION_FAILED': return 'PLAN_VALIDATION_FAILED'
    case 'RATE_LIMITED': return 'RATE_LIMITED'
    case 'INTERNAL_ERROR': return 'INTERNAL_ERROR'
    default:
      if (statusCode === 401) return 'AUTHENTICATION_REQUIRED'
      if (statusCode === 403) return 'ACCESS_DENIED'
      if (statusCode === 404) return 'RESOURCE_NOT_FOUND'
      if (statusCode === 409) return 'VERSION_CONFLICT'
      if (statusCode === 429) return 'RATE_LIMITED'
      if (statusCode >= 400 && statusCode < 500) return 'VALIDATION_FAILED'
      return 'INTERNAL_ERROR'
  }
}
