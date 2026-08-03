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

export function createWeappCloudBaseAiTextProvider(model: string): AiTextGenerationPort {
  const normalizedModel = model.trim()
  return {
    async generate(request: AiTextGenerationRequest): Promise<string> {
      const ai = runtime()?.cloud?.extend?.AI
      if (!initializedEnvironment || !normalizedModel || !ai) {
        throw new Error('CloudBase AI is not available')
      }
      const response = await ai.createModel('cloudbase').generateText({
        model: normalizedModel,
        messages: [
          { role: 'system', content: request.systemPrompt },
          { role: 'user', content: request.factsJson },
        ],
      })
      const content = response.choices?.[0]?.message?.content?.trim()
      if (!content) throw new Error('CloudBase AI returned no content')
      return content
    },
  }
}

export function resetWeappCloudBaseForTests(): void {
  initializedEnvironment = ''
}

function runtime(): WechatCloudRuntime | undefined {
  return typeof wx === 'undefined' ? undefined : wx
}
