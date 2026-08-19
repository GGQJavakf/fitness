import {
  AiDiagnosticError,
  isApprovedAiPurpose,
  type AiConsentPort,
  type AiTextGenerationPort,
  type AiTextGenerationRequest,
} from '../../application/cloudbaseAi'

interface WechatCloudAiResponse {
  choices?: Array<{ message?: { content?: string } }>
}

export type CloudBaseAiProviderGroup = 'cloudbase' | 'hunyuan-exp' | `custom-${string}`

interface WechatCloudRuntime {
  cloud?: {
    init(options: { env: string; traceUser: boolean }): void
    extend?: {
      AI?: {
        createModel(provider: CloudBaseAiProviderGroup): {
          generateText(request: {
            model: string
            messages: Array<{ role: 'system' | 'user'; content: string }>
          }): Promise<WechatCloudAiResponse>
        }
      }
    }
  }
}

export interface WeappCloudBaseAiOptions {
  model: string
  providerGroup: string
  releaseEnabled: boolean
  approvalGranted: boolean
  billingEligible: boolean
  modelReady: boolean
  consentPort: AiConsentPort
}

declare const wx: WechatCloudRuntime | undefined

let configuredEnvironment = ''
let initializedEnvironment = ''
let initializationFailed = false
export const WEAPP_CLOUDBASE_AI_TIMEOUT_MS = 8_000

export function initializeWeappCloudBase(environmentId: string): void {
  const normalized = environmentId.trim()
  if (normalized && initializedEnvironment === normalized) return
  configuredEnvironment = normalized
  initializedEnvironment = ''
  initializationFailed = false
  if (!normalized) return
  const cloud = runtime()?.cloud
  if (!cloud) return
  try {
    cloud.init({ env: normalized, traceUser: true })
    initializedEnvironment = normalized
  } catch {
    initializationFailed = true
  }
}

export function createWeappCloudBaseAiTextProvider(
  options: WeappCloudBaseAiOptions,
  timeoutMs = WEAPP_CLOUDBASE_AI_TIMEOUT_MS,
): AiTextGenerationPort {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
    throw new TypeError('CloudBase AI timeout must be a positive integer')
  }
  return {
    async generate(request: AiTextGenerationRequest): Promise<string> {
      assertApprovedPurpose(request.purpose)
      assertActivation(options)
      assertApprovedPlanGenerationModel(request.purpose, options)
      const consentGranted = request.purpose === 'PLAN_GENERATION'
        ? request.explicitUserConsent === true
        : await options.consentPort.hasConsent(request.purpose)
      if (!consentGranted) {
        throw new AiDiagnosticError(
          'ELIGIBILITY',
          'AI_CONSENT_REQUIRED',
          'Explicit user AI consent is required',
        )
      }

      if (!configuredEnvironment) {
        throw new AiDiagnosticError(
          'CONFIGURATION',
          'AI_ENVIRONMENT_MISSING',
          'CloudBase AI environment is not configured',
        )
      }
      const ai = runtime()?.cloud?.extend?.AI
      if (!ai || initializationFailed || initializedEnvironment !== configuredEnvironment) {
        throw new AiDiagnosticError(
          'SDK',
          'AI_SDK_UNAVAILABLE',
          'CloudBase AI SDK is not ready',
        )
      }

      const providerGroup = normalizeProviderGroup(options.providerGroup)
      try {
        const response = await withAiTimeout(
          ai.createModel(providerGroup).generateText({
            model: options.model.trim(),
            messages: [
              { role: 'system', content: request.systemPrompt },
              { role: 'user', content: request.factsJson },
            ],
          }),
          timeoutMs,
        )
        const content = response.choices && response.choices[0]
          && response.choices[0].message && response.choices[0].message.content
          ? response.choices[0].message.content.trim()
          : ''
        if (!content) {
          throw new AiDiagnosticError(
            'CONTRACT',
            'AI_RESPONSE_EMPTY',
            'CloudBase AI returned no assistant content',
          )
        }
        return content
      } catch (error) {
        throw classifySdkError(error)
      }
    },
  }
}

function assertApprovedPlanGenerationModel(
  purpose: AiTextGenerationRequest['purpose'],
  options: WeappCloudBaseAiOptions,
): void {
  if (purpose !== 'PLAN_GENERATION') return
  if (options.providerGroup.trim() !== 'cloudbase' || options.model.trim() !== 'hy3') {
    throw new AiDiagnosticError(
      'CONFIGURATION',
      'AI_PLAN_MODEL_NOT_APPROVED',
      'Plan generation must use the approved CloudBase hy3 model',
    )
  }
}

function assertApprovedPurpose(purpose: unknown): asserts purpose is AiTextGenerationRequest['purpose'] {
  if (!isApprovedAiPurpose(purpose)) {
    throw new AiDiagnosticError(
      'CONFIGURATION',
      'AI_PURPOSE_NOT_APPROVED',
      'AI purpose is outside the approved allowlist',
    )
  }
}

function assertActivation(options: WeappCloudBaseAiOptions): void {
  if (!options.releaseEnabled) {
    throw new AiDiagnosticError('CONFIGURATION', 'AI_RELEASE_DISABLED', 'Online AI is disabled')
  }
  if (!options.approvalGranted) {
    throw new AiDiagnosticError(
      'CONFIGURATION',
      'AI_APPROVAL_MISSING',
      'Online AI approval is missing',
    )
  }
  if (!options.billingEligible) {
    throw new AiDiagnosticError(
      'ELIGIBILITY',
      'AI_BILLING_INELIGIBLE',
      'CloudBase AI billing eligibility is not confirmed',
    )
  }
  if (!options.modelReady) {
    throw new AiDiagnosticError(
      'CONFIGURATION',
      'AI_MODEL_NOT_READY',
      'CloudBase AI model readiness is not confirmed',
    )
  }
  if (!options.model.trim()) {
    throw new AiDiagnosticError('CONFIGURATION', 'AI_MODEL_MISSING', 'AI model is not configured')
  }
  normalizeProviderGroup(options.providerGroup)
}

function normalizeProviderGroup(value: string): CloudBaseAiProviderGroup {
  const normalized = value.trim()
  if (normalized === 'cloudbase' || normalized === 'hunyuan-exp'
    || /^custom-[A-Za-z0-9][A-Za-z0-9_-]{0,62}$/.test(normalized)) {
    return normalized as CloudBaseAiProviderGroup
  }
  throw new AiDiagnosticError(
    'CONFIGURATION',
    'AI_PROVIDER_GROUP_INVALID',
    'CloudBase AI provider group is invalid',
  )
}

function withAiTimeout<T>(request: Promise<T>, timeoutMs: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new AiDiagnosticError(
        'TIMEOUT',
        'AI_TIMEOUT',
        'CloudBase AI request timed out',
      )),
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

function classifySdkError(error: unknown): AiDiagnosticError {
  if (error instanceof AiDiagnosticError) return error
  const details = errorDetails(error)
  if (details.status === 429 || details.code === '429'
    || /rate.?limit|throttl|too many requests/i.test(details.message)) {
    return new AiDiagnosticError('THROTTLING', 'AI_THROTTLED', 'CloudBase AI request was throttled')
  }
  if ((details.status !== undefined && details.status >= 500)
    || /network|temporar|unavailable|connection|econn|etimedout/i.test(details.message)) {
    return new AiDiagnosticError(
      'TRANSIENT',
      'AI_PROVIDER_TRANSIENT',
      'CloudBase AI provider is temporarily unavailable',
    )
  }
  if (/model.?not.?enabled|not.?enrolled|billing|resource.?pack|eligib/i.test(details.message)) {
    return new AiDiagnosticError(
      'ELIGIBILITY',
      'AI_PROVIDER_INELIGIBLE',
      'CloudBase AI eligibility or group readiness was rejected',
    )
  }
  return new AiDiagnosticError('SDK', 'AI_SDK_FAILURE', 'CloudBase AI SDK request failed')
}

function errorDetails(error: unknown): { status?: number; code?: string; message: string } {
  if (error instanceof Error) return { message: error.message }
  if (typeof error !== 'object' || error === null || Array.isArray(error)) return { message: '' }
  const record = error as Record<string, unknown>
  const statusValue = record.status ?? record.statusCode ?? record.errCode
  return {
    status: typeof statusValue === 'number' ? statusValue : undefined,
    code: typeof statusValue === 'string' ? statusValue : undefined,
    message: typeof record.message === 'string'
      ? record.message
      : typeof record.errMsg === 'string' ? record.errMsg : '',
  }
}

export function resetWeappCloudBaseForTests(): void {
  configuredEnvironment = ''
  initializedEnvironment = ''
  initializationFailed = false
}

function runtime(): WechatCloudRuntime | undefined {
  return typeof wx === 'undefined' ? undefined : wx
}
