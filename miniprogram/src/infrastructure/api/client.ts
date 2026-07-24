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
import type { SyncWorkoutOperation, SyncWorkoutOperationResult, WorkoutOperationSyncPort } from '../../application/ports/WorkoutOperationSyncPort'
import type {
  DeletionRequestData,
  PrivacyExportData,
  PrivacyPort,
} from '../../application/privacy'
import type { ApiErrorResponse, ApiResponse } from './generated'
import type { WorkoutHistoryPage, WorkoutHistoryPort } from '../../application/history'
import type { WorkoutCompletionPort, WorkoutCompletionResult, WorkoutCompletionType } from '../../application/ports/WorkoutCompletionPort'
import type { ExerciseReplacementCandidate, ReplacedWorkoutSession, WorkoutReplacementPort } from '../../application/ports/WorkoutReplacementPort'
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
type PrivacyExportResponse = components['schemas']['PrivacyExportResponse']
type DeletionRequestResponse = components['schemas']['DeletionRequestResponse']
type ReauthenticationProofResponse = components['schemas']['ReauthenticationProofResponse']
type SyncConflictData = components['schemas']['SyncConflictData']
type SyncConflictListResponse = components['schemas']['SyncConflictListResponse']
type SyncConflictResponse = components['schemas']['SyncConflictResponse']
type WorkoutSessionData = components['schemas']['WorkoutSessionData']
type WorkoutSessionResponse = components['schemas']['WorkoutSessionResponse']
type SyncWorkoutOperationsResponse = components['schemas']['SyncWorkoutOperationsResponse']
type WorkoutHistoryResponse = components['schemas']['WorkoutHistoryResponse']
type WorkoutCompletionResponse = components['schemas']['WorkoutCompletionResponse']
type ExerciseReplacementResponse = components['schemas']['ExerciseReplacementResponse']
type ContractPrivacyExportData = components['schemas']['PrivacyExportData']
type ContractDeletionRequestData = components['schemas']['DeletionRequestData']

export class FitnessApiClient implements OnboardingPersistencePort, PlanPersistencePort, PrivacyPort, WorkoutOperationSyncPort, WorkoutHistoryPort, WorkoutCompletionPort, WorkoutReplacementPort {
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

  async exportData(reauthenticationProof: string): Promise<PrivacyExportData> {
    const response = await this.request<PrivacyExportResponse>(
      '/api/v1/privacy/export',
      'GET',
      undefined,
      true,
      { 'X-Reauthentication-Proof': reauthenticationProof },
    )
    return toPrivacyExportData(requireData(response.data))
  }

  async issueReauthenticationProof(code: string): Promise<string> {
    const response = await this.request<ReauthenticationProofResponse>(
      '/api/v1/privacy/reauthentication-proofs',
      'POST',
      { code },
    )
    return requireData(response.data).proof
  }

  async requestDeletion(request: {
    reauthenticationProof: string
    confirmationText: 'DELETE'
  }): Promise<DeletionRequestData> {
    const response = await this.request<DeletionRequestResponse>(
      '/api/v1/privacy/deletion-requests',
      'POST',
      request satisfies components['schemas']['CreateDeletionRequest'],
    )
    return toDeletionRequestData(requireData(response.data))
  }

  async getDeletionRequest(requestId: string): Promise<DeletionRequestData> {
    const response = await this.request<DeletionRequestResponse>(
      `/api/v1/privacy/deletion-requests/${encodeURIComponent(requestId)}`,
      'GET',
    )
    return toDeletionRequestData(requireData(response.data))
  }

  async listSyncConflicts(): Promise<SyncConflictData[]> {
    const response = await this.request<SyncConflictListResponse>('/api/v1/sync/conflicts', 'GET')
    return response.data.items
  }

  async listHistory(cursor?: string, limit = 20): Promise<WorkoutHistoryPage> {
    const query = cursor
      ? `cursor=${encodeURIComponent(cursor)}&limit=${encodeURIComponent(String(limit))}`
      : `limit=${encodeURIComponent(String(limit))}`
    const response = await this.request<WorkoutHistoryResponse>(
      `/api/v1/workout-sessions?${query}`,
      'GET',
    )
    return response.data
  }

  async completeWorkout(
    sessionId: string,
    request: { expectedVersion: number; completionType: WorkoutCompletionType },
    idempotencyKey: string,
  ): Promise<WorkoutCompletionResult> {
    const response = await this.request<WorkoutCompletionResponse>(
      `/api/v1/workout-sessions/${encodeURIComponent(sessionId)}/complete`,
      'POST', request, true, { 'Idempotency-Key': idempotencyKey },
    )
    return response.data as WorkoutCompletionResult
  }

  async listExerciseReplacements(sourceCode: string): Promise<readonly ExerciseReplacementCandidate[]> {
    const response = await this.request<ExerciseReplacementResponse>(
      `/api/v1/exercises/${encodeURIComponent(sourceCode)}/replacements`, 'GET',
    )
    return response.data.items
  }

  async replaceWorkoutExercise(
    sessionId: string, snapshotId: string, replacementCode: string, expectedVersion: number,
  ): Promise<ReplacedWorkoutSession> {
    const response = await this.request<WorkoutSessionResponse>(
      `/api/v1/workout-sessions/${encodeURIComponent(sessionId)}/exercises/${encodeURIComponent(snapshotId)}`,
      'PUT', { action: 'REPLACE', replacementExerciseId: replacementCode, expectedVersion },
    )
    return response.data
  }

  async startWorkoutSession(
    request: components['schemas']['StartWorkoutSessionRequest'],
  ): Promise<WorkoutSessionData> {
    const response = await this.request<WorkoutSessionResponse>(
      '/api/v1/workout-sessions',
      'POST',
      request,
      true,
      { 'Idempotency-Key': request.clientSessionKey },
    )
    return response.data
  }

  async syncWorkoutOperations(operations: readonly SyncWorkoutOperation[]): Promise<readonly SyncWorkoutOperationResult[]> {
    const response = await this.request<SyncWorkoutOperationsResponse>(
      '/api/v1/sync/workout-operations',
      'POST',
      { operations },
    )
    return response.data.results
  }

  async resolveSyncConflict(
    conflictId: string,
    request: components['schemas']['ResolveSyncConflictRequest'],
  ): Promise<SyncConflictData> {
    const response = await this.request<SyncConflictResponse>(
      `/api/v1/sync/conflicts/${encodeURIComponent(conflictId)}/resolve`,
      'POST',
      request,
    )
    return response.data
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
    extraHeaders: Record<string, string> = {},
  ): Promise<Response> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...extraHeaders,
    }
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
  let parsed: URL
  try {
    parsed = new URL(normalized)
  } catch {
    throw new Error('API base URL must use http or https')
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('API base URL must use http or https')
  }
  const loopbackHosts = new Set(['localhost', '127.0.0.1', '::1', '[::1]'])
  if (parsed.protocol === 'http:' && !loopbackHosts.has(parsed.hostname.toLowerCase())) {
    throw new Error('API base URL must use HTTPS for non-loopback hosts')
  }
  return normalized
}

function requireData<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
  }
  return value
}

function toPrivacyExportData(data: ContractPrivacyExportData): PrivacyExportData {
  return data
}

function toDeletionRequestData(data: ContractDeletionRequestData): DeletionRequestData {
  return data
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
    case 'REAUTHENTICATION_REQUIRED': return 'REAUTHENTICATION_REQUIRED'
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
