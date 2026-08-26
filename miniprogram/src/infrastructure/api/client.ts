import { ApplicationError, applicationErrorMessage } from '../../application/errors'
import type {
  ActivePlanData,
  CreatePlanVersionRequest,
  PlanCandidateGenerationData,
  PlanGenerationContextData,
  PlanValidationData,
  PlanValidationDraft,
  PlanExerciseOption,
  PlanExerciseReplacementOption,
  PlanDayOption,
  PlanPresetSummary,
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
import type {
  ExerciseTrendData,
  ProgressionPort,
  ProgressionRecommendationData,
  ProgressionRecommendationPage,
  RecommendationStatus,
} from '../../application/progression'
import type { SyncWorkoutOperation, SyncWorkoutOperationResult, WorkoutOperationSyncPort } from '../../application/ports/WorkoutOperationSyncPort'
import type {
  DeletionRequestData,
  PrivacyExportData,
  PrivacyPort,
} from '../../application/privacy'
import type { ApiErrorResponse, ApiResponse } from './generated'
import type { WorkoutHistoryPage, WorkoutHistoryPort, WorkoutSessionSummary } from '../../application/history'
import type { WorkoutCompletionPort, WorkoutCompletionResult, WorkoutCompletionType } from '../../application/ports/WorkoutCompletionPort'
import type { ExerciseReplacementCandidate, ReplacedWorkoutSession, WorkoutReplacementPort } from '../../application/ports/WorkoutReplacementPort'
import type { components } from './schema.generated'
import type { AiGeneratedContent } from '../../application/ai'
import type { ExerciseContent, ExercisePreferenceProfile } from '../../application/content'
import type {
  WorkoutRecoveryAssessment,
  WorkoutRecoveryCheckRequest,
  WorkoutRecoveryPort,
} from '../../application/ports/WorkoutRecoveryPort'
import type {
  RecoverableActiveWorkout,
  RecoverableWorkoutSet,
  WorkoutSessionStartPort,
  StartWorkoutSessionRequest,
} from '../../application/ports/WorkoutSessionStartPort'
import {
  ActiveWorkoutExistsError,
  WorkoutStartTerminalReplayError,
  WorkoutRecoveryConfirmationRequiredError,
} from '../../application/errors'

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
  headers?: Readonly<Record<string, string>>
}

export interface TransportPort {
  request<T>(request: TransportRequest): Promise<TransportResponse<T>>
}

export interface SessionAccessPort {
  load(): Promise<Session | null>
  save(session: Session): Promise<void>
  clear(): Promise<void>
}

export type AuthenticationFailureCode = 'AUTHENTICATION_REQUIRED' | 'ACCESS_REVOKED'

type UserProfileResponse = components['schemas']['UserProfileResponse']
type EquipmentProfileResponse = components['schemas']['EquipmentProfileResponse']
type PreferenceProfileResponse = components['schemas']['PreferenceProfileResponse']
type CandidateResponse = components['schemas']['PlanCandidateGenerationResponse']
type PlanPresetListResponse = components['schemas']['PlanPresetListResponse']
type ValidationResponse = components['schemas']['PlanValidationResponse']
type ActivePlanResponse = components['schemas']['ActivePlanResponse']
type VersionResultResponse = components['schemas']['PlanVersionResultResponse']
type PlanExerciseOptionListResponse = components['schemas']['PlanExerciseOptionListResponse']
type PlanExerciseReplacementOptionListResponse = components['schemas']['PlanExerciseReplacementOptionListResponse']
type PlanDayOptionListResponse = components['schemas']['PlanDayOptionListResponse']
type PrivacyExportResponse = components['schemas']['PrivacyExportResponse']
type DeletionRequestResponse = components['schemas']['DeletionRequestResponse']
type ReauthenticationProofResponse = components['schemas']['ReauthenticationProofResponse']
type SyncConflictData = components['schemas']['SyncConflictData']
type SyncConflictListResponse = components['schemas']['SyncConflictListResponse']
type SyncConflictResolutionData = components['schemas']['SyncConflictResolutionData']
type SyncConflictResolutionResponse = components['schemas']['SyncConflictResolutionResponse']
type WorkoutSessionData = components['schemas']['WorkoutSessionData']
type WorkoutSessionResponse = components['schemas']['WorkoutSessionResponse']
type SyncWorkoutOperationsResponse = components['schemas']['SyncWorkoutOperationsResponse']
type WorkoutHistoryResponse = components['schemas']['WorkoutHistoryResponse']
type WorkoutSessionSummaryResponse = components['schemas']['WorkoutSessionSummaryResponse']
type WorkoutCompletionResponse = components['schemas']['WorkoutCompletionResponse']
type ExerciseReplacementResponse = components['schemas']['ExerciseReplacementResponse']
type ProgressionRecommendationListResponse = components['schemas']['ProgressionRecommendationListResponse']
type ProgressionRecommendationResponse = components['schemas']['ProgressionRecommendationResponse']
type ExerciseTrendResponse = components['schemas']['ExerciseTrendResponse']
type WorkoutRecoveryCheckResponse = components['schemas']['WorkoutRecoveryCheckResponse']
type AiGeneratedContentResponse = components['schemas']['AiGeneratedContentResponse']
type ContractPrivacyExportData = components['schemas']['PrivacyExportData']
type ContractDeletionRequestData = components['schemas']['DeletionRequestData']
type ExerciseListResponse = components['schemas']['ExerciseListResponse']
type ExerciseDetailResponse = components['schemas']['ExerciseDetailResponse']

export class FitnessApiClient implements OnboardingPersistencePort, PlanPersistencePort, PrivacyPort, WorkoutOperationSyncPort, WorkoutHistoryPort, WorkoutCompletionPort, WorkoutReplacementPort, ProgressionPort, WorkoutRecoveryPort, WorkoutSessionStartPort {
  private readonly baseUrl: string
  private refreshInFlight: Promise<Session> | null = null
  private readonly rotatedSessions = new Map<string, Session>()

  constructor(
    baseUrl: string,
    private readonly transport: TransportPort,
    private readonly sessions: SessionAccessPort,
    private readonly onAuthenticationFailure?: (
      code: AuthenticationFailureCode,
    ) => Promise<void> | void,
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
    const session = requireData(response.data)
    this.rotatedSessions.clear()
    return session
  }

  async logout(): Promise<void> {
    await this.request<ApiResponse<Record<string, never>>>(
      '/api/v1/auth/logout',
      'POST',
    )
    this.rotatedSessions.clear()
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

  async getPreferences(): Promise<ExercisePreferenceProfile> {
    const response = await this.request<PreferenceProfileResponse>(
      '/api/v1/profile/preferences',
      'GET',
    )
    return requireData(response.data)
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

  async listExercises(): Promise<readonly ExerciseContent[]> {
    const response = await this.request<ExerciseListResponse>('/api/v1/exercises', 'GET')
    return requireData(response.data).items
  }

  async getExercise(idOrCode: string): Promise<ExerciseContent> {
    const response = await this.request<ExerciseDetailResponse>(
      `/api/v1/exercises/${encodeURIComponent(idOrCode)}`,
      'GET',
    )
    return requireData(response.data)
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

  async listPlanPresets(): Promise<readonly PlanPresetSummary[]> {
    const response = await this.request<PlanPresetListResponse>('/api/v1/plans/presets', 'GET')
    return requireData(response.data).items
  }

  async getPlanGenerationContext(
    profileVersion: number,
  ): Promise<PlanGenerationContextData> {
    const response = await this.request<ApiResponse<PlanGenerationContextData>>(
      `/api/v1/plans/generation-context?profileVersion=${encodeURIComponent(String(profileVersion))}`,
      'GET',
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

  async listExerciseOptions(planId: string, dayCode: string): Promise<readonly PlanExerciseOption[]> {
    const response = await this.request<PlanExerciseOptionListResponse>(
      `/api/v1/plans/${encodeURIComponent(planId)}/exercise-options?dayCode=${encodeURIComponent(dayCode)}`,
      'GET',
    )
    return requireData(response.data).items
  }

  async listPlanExerciseReplacements(
    planId: string,
    dayCode: string,
    sourceExerciseCode: string,
  ): Promise<readonly PlanExerciseReplacementOption[]> {
    const response = await this.request<PlanExerciseReplacementOptionListResponse>(
      `/api/v1/plans/${encodeURIComponent(planId)}/exercise-replacements`
        + `?dayCode=${encodeURIComponent(dayCode)}`
        + `&sourceExerciseCode=${encodeURIComponent(sourceExerciseCode)}`,
      'GET',
    )
    return requireData(response.data).items
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

  async listDayOptions(planId: string): Promise<readonly PlanDayOption[]> {
    const response = await this.request<PlanDayOptionListResponse>(
      `/api/v1/plans/${encodeURIComponent(planId)}/day-options`,
      'GET',
    )
    return requireData(response.data).items
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

  async getWorkoutSessionSummary(sessionId: string): Promise<WorkoutSessionSummary> {
    const response = await this.request<WorkoutSessionSummaryResponse>(
      `/api/v1/workout-sessions/${encodeURIComponent(sessionId)}/summary`,
      'GET',
    )
    return requireData(response.data)
  }

  async listRecommendations(
    status?: RecommendationStatus,
    cursor?: string,
    limit = 20,
  ): Promise<ProgressionRecommendationPage> {
    const parameters: string[] = []
    if (status) parameters.push(`status=${encodeURIComponent(status)}`)
    if (cursor) parameters.push(`cursor=${encodeURIComponent(cursor)}`)
    parameters.push(`limit=${encodeURIComponent(String(limit))}`)
    const response = await this.requestWithMetadata<ProgressionRecommendationListResponse>(
      `/api/v1/progression-recommendations?${parameters.join('&')}`,
      'GET',
    )
    const items = requireData(response.data.data)
    const hasMoreHeader = response.headers?.['x-has-more']
    if (hasMoreHeader !== 'true' && hasMoreHeader !== 'false') {
      throw new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
    }
    const nextCursor = response.headers?.['x-next-cursor']
    if ((hasMoreHeader === 'true' && !nextCursor)
      || (hasMoreHeader === 'false' && nextCursor !== undefined)) {
      throw new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
    }
    return {
      items,
      hasMore: hasMoreHeader === 'true',
      ...(nextCursor ? { nextCursor } : {}),
    }
  }

  async applyRecommendation(
    id: string,
    expectedVersion: number,
    acceptedWeightKg: number,
    idempotencyKey: string,
  ): Promise<ProgressionRecommendationData> {
    const response = await this.request<ProgressionRecommendationResponse>(
      `/api/v1/progression-recommendations/${encodeURIComponent(id)}/apply`,
      'POST',
      { expectedVersion, acceptedWeight: { value: acceptedWeightKg, unit: 'KG' } },
      true,
      { 'Idempotency-Key': idempotencyKey },
    )
    return requireData(response.data)
  }

  async dismissRecommendation(id: string, reasonCode = 'USER_DISMISSED'): Promise<ProgressionRecommendationData> {
    const response = await this.request<ProgressionRecommendationResponse>(
      `/api/v1/progression-recommendations/${encodeURIComponent(id)}/dismiss`,
      'POST',
      { reasonCode },
    )
    return requireData(response.data)
  }

  async getExerciseTrend(exerciseCode: string): Promise<ExerciseTrendData> {
    const response = await this.request<ExerciseTrendResponse>(
      `/api/v1/progress/exercises/${encodeURIComponent(exerciseCode)}`,
      'GET',
    )
    return requireData(response.data)
  }

  async requestPlanExplanation(candidateId: string): Promise<AiGeneratedContent> {
    const response = await this.request<AiGeneratedContentResponse>(
      '/api/v1/ai/plan-explanations',
      'POST',
      { candidateId } satisfies components['schemas']['AiPlanExplanationRequest'],
    )
    return requireData(response.data)
  }

  async requestWorkoutSummary(workoutSessionId: string): Promise<AiGeneratedContent> {
    const response = await this.request<AiGeneratedContentResponse>(
      '/api/v1/ai/workout-summaries',
      'POST',
      { workoutSessionId } satisfies components['schemas']['AiWorkoutSummaryRequest'],
    )
    return requireData(response.data)
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

  async listExerciseReplacements(
    sessionId: string, snapshotId: string, sourceCode: string,
  ): Promise<readonly ExerciseReplacementCandidate[]> {
    const response = await this.request<ExerciseReplacementResponse>(
      `/api/v1/workout-sessions/${encodeURIComponent(sessionId)}`
        + `/exercises/${encodeURIComponent(snapshotId)}/replacements`,
      'GET',
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
    const session = parseWorkoutSession(response.data)
    if (!session) {
      throw new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
    }
    return session
  }

  async startWorkoutSession(
    request: StartWorkoutSessionRequest,
  ): Promise<WorkoutSessionData> {
    const response = await this.request<WorkoutSessionResponse>(
      '/api/v1/workout-sessions',
      'POST',
      request,
      true,
      { 'Idempotency-Key': request.clientSessionKey },
    )
    const session = response.data
    if (session.status === 'IN_PROGRESS') return session
    if (session.status !== 'CREATED') {
      throw new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
    }
    const activated = await this.request<WorkoutSessionResponse>(
      `/api/v1/workout-sessions/${encodeURIComponent(session.id)}/status`,
      'PUT',
      { status: 'IN_PROGRESS', expectedVersion: session.version },
    )
    return activated.data
  }

  async activateWorkoutSession(
    sessionId: string,
    expectedVersion: number,
  ): Promise<WorkoutSessionData> {
    const activated = await this.request<WorkoutSessionResponse>(
      `/api/v1/workout-sessions/${encodeURIComponent(sessionId)}/status`,
      'PUT',
      { status: 'IN_PROGRESS', expectedVersion },
    )
    return activated.data
  }

  async checkWorkoutRecovery(
    request: WorkoutRecoveryCheckRequest,
  ): Promise<WorkoutRecoveryAssessment> {
    const query = `planId=${encodeURIComponent(request.planId)}`
      + `&planVersionNo=${encodeURIComponent(String(request.planVersionNo))}`
      + `&trainingDayCode=${encodeURIComponent(request.trainingDayCode)}`
    const response = await this.request<WorkoutRecoveryCheckResponse>(
      `/api/v1/workout-recovery-checks?${query}`,
      'GET',
    )
    return requireData(response.data)
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
  ): Promise<SyncConflictResolutionData> {
    const response = await this.request<SyncConflictResolutionResponse>(
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
    return (await this.requestWithMetadata<Response>(
      path,
      method,
      body,
      authenticated,
      extraHeaders,
    )).data
  }

  private async requestWithMetadata<Response>(
    path: string,
    method: HttpMethod,
    body?: unknown,
    authenticated = true,
    extraHeaders: Record<string, string> = {},
  ): Promise<TransportResponse<Response>> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...extraHeaders,
    }
    let session: Session | null = null
    if (authenticated) {
      session = await this.sessions.load()
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

    if (authenticated && response.statusCode === 401 && session) {
      if (terminalAuthenticationCode(response.data)) {
        throw await this.mapError(response.statusCode, response.data)
      }
      const recovered = await this.recoverSession(session)
      headers.Authorization = `Bearer ${recovered.accessToken}`
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
    }

    if (response.statusCode >= 200 && response.statusCode < 300) {
      return response as TransportResponse<Response>
    }
    throw await this.mapError(response.statusCode, response.data)
  }

  private async recoverSession(failedSession: Session): Promise<Session> {
    const rotatedSession = this.rotatedSessions.get(failedSession.refreshToken)
    if (rotatedSession) return rotatedSession
    return this.refreshSession(failedSession)
  }

  private async refreshSession(session: Session): Promise<Session> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.performRefresh(session)
        .finally(() => { this.refreshInFlight = null })
    }
    return this.refreshInFlight
  }

  private async performRefresh(session: Session): Promise<Session> {
    const sourceRefreshToken = session.refreshToken
    let response: TransportResponse<unknown>
    try {
      response = await this.transport.request({
        url: `${this.baseUrl}/api/v1/auth/refresh`,
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: { refreshToken: session.refreshToken },
      })
    } catch {
      throw new ApplicationError('NETWORK_ERROR', applicationErrorMessage('NETWORK_ERROR'), {
        retryable: true,
      })
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw await this.mapError(response.statusCode, response.data)
    }
    const refreshed = isSessionResponse(response.data)
    if (!refreshed) {
      throw new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
    }
    await this.sessions.save(refreshed)
    for (const previousRefreshToken of this.rotatedSessions.keys()) {
      this.rotatedSessions.set(previousRefreshToken, refreshed)
    }
    this.rotatedSessions.set(sourceRefreshToken, refreshed)
    while (this.rotatedSessions.size > 8) {
      const oldestRefreshToken = this.rotatedSessions.keys().next().value
      if (typeof oldestRefreshToken !== 'string') break
      this.rotatedSessions.delete(oldestRefreshToken)
    }
    return refreshed
  }

  private async mapError(statusCode: number, payload: unknown): Promise<ApplicationError> {
    const apiError = isApiErrorResponse(payload) ? payload.error : undefined
    const serverCode: string | undefined = apiError?.code
    if (statusCode === 409 && apiError && serverCode === 'RECOVERY_CONFIRMATION_REQUIRED') {
      const challenge = parseRecoveryConfirmationChallenge(apiError.details)
      if (!challenge) {
        return new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
      }
      return new WorkoutRecoveryConfirmationRequiredError(
        challenge.assessment,
        challenge.confirmationToken,
        challenge.confirmationExpiresAt,
      )
    }
    if (statusCode === 409 && apiError && serverCode === 'ACTIVE_WORKOUT_EXISTS') {
      const activeWorkout = parseActiveWorkout(apiError.details)
      if (!activeWorkout) {
        return new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
      }
      return new ActiveWorkoutExistsError(activeWorkout)
    }
    if (statusCode === 409 && apiError && serverCode === 'WORKOUT_START_ALREADY_TERMINAL') {
      const terminalSession = parseTerminalWorkout(apiError.details)
      if (!terminalSession) {
        return new ApplicationError('INVALID_RESPONSE', applicationErrorMessage('INVALID_RESPONSE'))
      }
      return new WorkoutStartTerminalReplayError(terminalSession)
    }
    const code = mapErrorCode(statusCode, serverCode)
    if (code === 'AUTHENTICATION_REQUIRED') {
      this.rotatedSessions.clear()
      await this.sessions.clear()
      await this.onAuthenticationFailure?.(code)
    } else if (code === 'ACCESS_REVOKED') {
      this.rotatedSessions.clear()
      if (this.onAuthenticationFailure) {
        await this.onAuthenticationFailure(code)
      } else {
        await this.sessions.clear()
      }
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

function terminalAuthenticationCode(value: unknown): 'ACCESS_REVOKED' | undefined {
  if (!isApiErrorResponse(value)) return undefined
  return value.error.code === 'ACCESS_REVOKED' ? value.error.code : undefined
}

function isSessionResponse(value: unknown): Session | null {
  if (typeof value !== 'object' || value === null || !('data' in value)) return null
  const data = (value as { data?: unknown }).data
  if (typeof data !== 'object' || data === null) return null
  const session = data as Partial<Session>
  return typeof session.accessToken === 'string'
    && typeof session.refreshToken === 'string'
    && typeof session.expiresAt === 'string'
    ? session as Session
    : null
}

function mapErrorCode(
  statusCode: number,
  serverCode?: string,
): ApplicationError['code'] {
  switch (serverCode) {
    case 'AUTHENTICATION_REQUIRED': return 'AUTHENTICATION_REQUIRED'
    case 'ACCESS_REVOKED': return 'ACCESS_REVOKED'
    case 'REAUTHENTICATION_REQUIRED': return 'REAUTHENTICATION_REQUIRED'
    case 'ACCESS_DENIED': return 'ACCESS_DENIED'
    case 'RESOURCE_NOT_FOUND': return 'RESOURCE_NOT_FOUND'
    case 'VERSION_CONFLICT': return 'VERSION_CONFLICT'
    case 'VALIDATION_FAILED': return 'VALIDATION_FAILED'
    case 'PLAN_VALIDATION_FAILED': return 'PLAN_VALIDATION_FAILED'
    case 'INSUFFICIENT_REPLACEMENTS': return 'INSUFFICIENT_REPLACEMENTS'
    case 'RATE_LIMITED': return 'RATE_LIMITED'
    case 'ACTIVE_WORKOUT_EXISTS': return 'ACTIVE_WORKOUT_EXISTS'
    case 'WORKOUT_START_ALREADY_TERMINAL': return 'WORKOUT_START_ALREADY_TERMINAL'
    case 'WORKOUT_NOT_TERMINAL': return 'WORKOUT_NOT_TERMINAL'
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

function parseRecoveryConfirmationChallenge(value: unknown): {
  assessment: WorkoutRecoveryAssessment
  confirmationToken: string
  confirmationExpiresAt: string
} | null {
  if (!isRecord(value)) return null
  const assessment = value.assessment
  const confirmationToken = value.confirmationToken
  const confirmationExpiresAt = value.confirmationExpiresAt
  if (!isWorkoutRecoveryAssessment(assessment)
    || typeof confirmationToken !== 'string' || confirmationToken.length === 0
    || typeof confirmationExpiresAt !== 'string' || confirmationExpiresAt.length === 0) {
    return null
  }
  return { assessment, confirmationToken, confirmationExpiresAt }
}

function parseActiveWorkout(value: unknown): RecoverableActiveWorkout | null {
  if (!isRecord(value) || !isRecord(value.activeSession) || !Array.isArray(value.sets)) return null
  const session = parseWorkoutSession(value.activeSession)
  if (!session) return null
  const exerciseIds = new Set(session.exercises.map((exercise) => exercise.id))
  const setsValid = value.sets.every((set) => isRecord(set)
    && isUuid(set.setId)
    && isUuid(set.sessionExerciseId) && exerciseIds.has(set.sessionExerciseId)
    && isBoundedText(set.clientSetKey, 8, 128)
    && isPositiveInteger(set.clientOperationSeq)
    && ['WARMUP', 'WORK', 'EXTRA'].includes(String(set.setType))
    && isPositiveInteger(set.setOrder)
    && isRecord(set.target) && isRecord(set.target.weight)
    && isNonNegativeNumber(set.target.weight.value) && set.target.weight.unit === 'KG'
    && isPositiveInteger(set.target.reps)
    && isRecord(set.actual) && isRecord(set.actual.weight)
    && isNonNegativeNumber(set.actual.weight.value) && set.actual.weight.unit === 'KG'
    && isNonNegativeInteger(set.actual.reps)
    && (set.remainingReps == null || isNonNegativeInteger(set.remainingReps))
    && ['COMPLETED', 'FAILED', 'SKIPPED'].includes(String(set.completionStatus))
    && (set.completionStatus === 'COMPLETED'
      ? isUtcDateTime(set.completedAt)
      : set.completedAt == null)
    && isNonNegativeInteger(set.serverRevision)
    && isNonNegativeInteger(set.sessionVersion) && Number(set.sessionVersion) <= Number(session.version)
    && (set.safetyFlag == null || ['PAIN', 'INJURY', 'CHEST_DISCOMFORT', 'DIZZINESS', 'SEVERE_UNWELL']
      .includes(String(set.safetyFlag)))
    && (set.anomalyStatus == null || set.anomalyStatus === 'CONFIRMED_EXCLUDED')
    && set.syncStatus === 'APPLIED')
  if (exerciseIds.size !== session.exercises.length || !setsValid) return null
  const sets = value.sets.map((set) => {
    const source = set as Record<string, unknown>
    return {
      ...source,
      remainingReps: source.remainingReps ?? undefined,
      completedAt: source.completedAt ?? undefined,
      safetyFlag: source.safetyFlag ?? undefined,
      anomalyStatus: source.anomalyStatus ?? undefined,
    } as unknown as RecoverableWorkoutSet
  })
  return {
    session: session as RecoverableActiveWorkout['session'],
    sets,
  }
}

function parseWorkoutSession(value: unknown): WorkoutSessionData | null {
  if (!isRecord(value)) return null
  const session = value
  if (!isUuid(session.id)
    || !isUuid(session.planId)
    || !isUuid(session.planVersionId)
    || !isBoundedText(session.clientSessionKey, 8, 128)
    || !isPositiveInteger(session.planVersionNo)
    || !isBoundedText(session.planDayId, 1, 128)
    || !isBoundedText(session.trainingDayCode, 1, 128)
    || !isUtcDateTime(session.startedAt)
    || !isNonNegativeInteger(session.version)
    || !['CREATED', 'IN_PROGRESS', 'PAUSED', 'COMPLETING'].includes(String(session.status))
    || !Array.isArray(session.exercises) || session.exercises.length === 0) return null
  const exercisesValid = session.exercises.every((exercise) => isRecord(exercise)
    && isUuid(exercise.id)
    && isPositiveInteger(exercise.order)
    && isBoundedText(exercise.exerciseCode, 1, 128)
    && isBoundedText(exercise.exerciseName, 1, 256)
    && isBoundedText(exercise.contentVersion, 1, 128)
    && Array.isArray(exercise.equipment)
    && exercise.equipment.every((item) => isBoundedText(item, 1, 128))
    && ['PENDING', 'ACTIVE', 'COMPLETED', 'SKIPPED', 'ABORTED', 'REPLACED']
      .includes(String(exercise.status))
    && isRecord(exercise.prescription)
    && isNonNegativeInteger(exercise.prescription.workSets)
    && isNonNegativeInteger(exercise.prescription.repMin)
    && isNonNegativeInteger(exercise.prescription.repMax)
    && Number(exercise.prescription.repMax) >= Number(exercise.prescription.repMin)
    && isNonNegativeInteger(exercise.prescription.restSeconds)
    && ['KNOWN', 'NEEDS_CALIBRATION', 'BODYWEIGHT'].includes(String(exercise.prescription.weightStatus))
    && isOptionalNonNegativeNumber(exercise.prescription.targetWeightKg)
    && exercise.prescription.unit === 'KG')
  if (!exercisesValid) return null
  const exercises = session.exercises.map((exercise) => {
    const source = exercise as Record<string, unknown>
    const prescription = source.prescription as Record<string, unknown>
    return {
      ...source,
      prescription: {
        ...prescription,
        targetWeightKg: prescription.targetWeightKg ?? undefined,
      },
    }
  })
  return { ...session, exercises } as unknown as WorkoutSessionData
}

function parseTerminalWorkout(value: unknown): {
  id: string
  clientSessionKey: string
  status: 'COMPLETED' | 'ABORTED'
  version: number
} | null {
  if (!isRecord(value) || !isRecord(value.terminalSession)) return null
  const session = value.terminalSession
  if (typeof session.id !== 'string'
    || typeof session.clientSessionKey !== 'string' || session.clientSessionKey.length < 8
    || !['COMPLETED', 'ABORTED'].includes(String(session.status))
    || !Number.isSafeInteger(session.version) || Number(session.version) < 0) return null
  return {
    id: session.id,
    clientSessionKey: session.clientSessionKey,
    status: session.status as 'COMPLETED' | 'ABORTED',
    version: Number(session.version),
  }
}

function isWorkoutRecoveryAssessment(value: unknown): value is WorkoutRecoveryAssessment {
  if (!isRecord(value)
    || value.decision !== 'CONFIRMATION_REQUIRED'
    || typeof value.policyVersion !== 'string'
    || typeof value.checkedAt !== 'string'
    || !Number.isInteger(value.minimumRecoveryHours)
    || !Array.isArray(value.affectedMuscles)) return false
  return value.affectedMuscles.every((affected) => isRecord(affected)
    && typeof affected.muscleGroup === 'string'
    && Number.isInteger(affected.elapsedHours)
    && Number.isInteger(affected.minimumRecoveryHours)
    && typeof affected.lastCompletedAt === 'string')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
}

function isBoundedText(value: unknown, minimum: number, maximum: number): value is string {
  return typeof value === 'string' && value.trim().length >= minimum && value.length <= maximum
}

function isPositiveInteger(value: unknown): boolean {
  return Number.isSafeInteger(value) && Number(value) > 0
}

function isNonNegativeInteger(value: unknown): boolean {
  return Number.isSafeInteger(value) && Number(value) >= 0
}

function isNonNegativeNumber(value: unknown): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

function isOptionalNonNegativeNumber(value: unknown): boolean {
  return value == null || isNonNegativeNumber(value)
}

function isUtcDateTime(value: unknown): value is string {
  return typeof value === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value)
    && Number.isFinite(Date.parse(value))
}
