import {
  DEFAULT_DENY_AI_CONSENT_PORT,
  createValidatedAiContentGenerator,
  createValidatedAiPlanGenerator,
  type AiTextGenerationPort,
} from '../../../application/cloudbaseAi'
import {
  createWeappCloudBaseAiTextProvider,
  initializeWeappCloudBase,
} from '../CloudBaseAiAdapter'
import type { UserGenerationLease } from '../sharedPlatformKernel'

declare const __FITNESS_CLOUDBASE_ENV_ID__: string
declare const __FITNESS_CLOUDBASE_AI_MODEL__: string
declare const __FITNESS_CLOUDBASE_AI_PROVIDER_GROUP__: string
declare const __FITNESS_CLOUDBASE_AI_ENABLED__: boolean
declare const __FITNESS_CLOUDBASE_AI_APPROVED__: boolean
declare const __FITNESS_CLOUDBASE_AI_ELIGIBLE__: boolean
declare const __FITNESS_CLOUDBASE_AI_MODEL_READY__: boolean

export function createWeappAiRuntime(userGeneration?: UserGenerationLease) {
  initializeWeappCloudBase(__FITNESS_CLOUDBASE_ENV_ID__)
  const consent = DEFAULT_DENY_AI_CONSENT_PORT
  const provider = createWeappCloudBaseAiTextProvider({
    model: __FITNESS_CLOUDBASE_AI_MODEL__,
    providerGroup: __FITNESS_CLOUDBASE_AI_PROVIDER_GROUP__,
    releaseEnabled: __FITNESS_CLOUDBASE_AI_ENABLED__,
    approvalGranted: __FITNESS_CLOUDBASE_AI_APPROVED__,
    billingEligible: __FITNESS_CLOUDBASE_AI_ELIGIBLE__,
    modelReady: __FITNESS_CLOUDBASE_AI_MODEL_READY__,
    consentPort: consent,
  })
  const textProvider = createGenerationFencedAiTextProvider(provider, userGeneration)
  const content = createValidatedAiContentGenerator(textProvider)
  const plan = createValidatedAiPlanGenerator(textProvider)
  const generationBoundContent: typeof content = userGeneration
    ? {
        generate: (purpose, facts, fallback) => runInUserGeneration(
          userGeneration,
          () => content.generate(purpose, facts, fallback),
        ),
      }
    : content
  const generationBoundPlan: typeof plan = userGeneration
    ? {
        generate: (context, options) => runInUserGeneration(
          userGeneration,
          () => plan.generate(context, options),
        ),
      }
    : plan
  return {
    consent,
    content: generationBoundContent,
    plan: generationBoundPlan,
    enabled: __FITNESS_CLOUDBASE_AI_ENABLED__,
    planGenerationAvailable: __FITNESS_CLOUDBASE_AI_ENABLED__
      && __FITNESS_CLOUDBASE_AI_APPROVED__
      && __FITNESS_CLOUDBASE_AI_ELIGIBLE__
      && __FITNESS_CLOUDBASE_AI_MODEL_READY__
      && __FITNESS_CLOUDBASE_ENV_ID__.trim().length > 0
      && __FITNESS_CLOUDBASE_AI_MODEL__.trim() === 'hy3'
      && __FITNESS_CLOUDBASE_AI_PROVIDER_GROUP__.trim() === 'cloudbase',
  }
}

export function createGenerationFencedAiTextProvider(
  provider: AiTextGenerationPort,
  userGeneration?: UserGenerationLease,
): AiTextGenerationPort {
  if (!userGeneration) return provider
  return {
    generate: (request) => runInUserGeneration(
      userGeneration,
      () => provider.generate(request),
    ),
  }
}

async function runInUserGeneration<T>(
  userGeneration: UserGenerationLease,
  operation: () => Promise<T>,
): Promise<T> {
  const generation = userGeneration.capture()
  try {
    const result = await operation()
    userGeneration.assertCurrent(generation)
    return result
  } catch (error) {
    userGeneration.assertCurrent(generation)
    throw error
  }
}
