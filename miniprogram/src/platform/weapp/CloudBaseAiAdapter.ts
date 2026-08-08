import type {
  AiTextGenerationPort,
  AiTextGenerationRequest,
} from '../../application/cloudbaseAi'

interface WechatCloudAiResponse {
  choices?: Array<{ message?: { content?: string } }>
}

interface WechatCloudRuntime {
  cloud?: {
    init(options: { env: string; traceUser: boolean }): void
    extend?: {
      AI?: {
        createModel(provider: 'cloudbase'): {
          generateText(request: {
            model: string
            messages: Array<{ role: 'system' | 'user'; content: string }>
          }): Promise<WechatCloudAiResponse>
        }
      }
    }
  }
}

declare const wx: WechatCloudRuntime | undefined

let initializedEnvironment = ''
export const WEAPP_CLOUDBASE_AI_TIMEOUT_MS = 15_000

export function initializeWeappCloudBase(environmentId: string): void {
  const normalized = environmentId.trim()
  if (!normalized || initializedEnvironment === normalized) return
  const cloud = runtime()?.cloud
  if (!cloud) return
  try {
    cloud.init({ env: normalized, traceUser: true })
    initializedEnvironment = normalized
  } catch {
    initializedEnvironment = ''
  }
}

export function createWeappCloudBaseAiTextProvider(
  model: string,
  timeoutMs = WEAPP_CLOUDBASE_AI_TIMEOUT_MS,
): AiTextGenerationPort {
  const normalizedModel = model.trim()
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
    throw new TypeError('CloudBase AI timeout must be a positive integer')
  }
  return {
    async generate(request: AiTextGenerationRequest): Promise<string> {
      const ai = runtime()?.cloud?.extend?.AI
      if (!initializedEnvironment || !normalizedModel || !ai) {
        throw new Error('CloudBase AI is not available')
      }
      const response = await withAiTimeout(
        ai.createModel('cloudbase').generateText({
          model: normalizedModel,
          messages: [
            { role: 'system', content: request.systemPrompt },
            { role: 'user', content: request.factsJson },
          ],
        }),
        timeoutMs,
      )
      const content = response.choices?.[0]?.message?.content?.trim()
      if (!content) throw new Error('CloudBase AI returned no content')
      return content
    },
  }
}

function withAiTimeout<T>(request: Promise<T>, timeoutMs: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error('CloudBase AI request timed out')),
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

export function resetWeappCloudBaseForTests(): void {
  initializedEnvironment = ''
}

function runtime(): WechatCloudRuntime | undefined {
  return typeof wx === 'undefined' ? undefined : wx
}
