import type { IProjectConfig } from '@tarojs/taro/types/compile'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const apiBaseUrl = process.env.TARO_APP_API_BASE_URL?.trim() || 'http://127.0.0.1:8080'
const localCloudBase = readLocalCloudBaseConfig()
const cloudBaseEnvironmentId = process.env.TARO_APP_CLOUDBASE_ENV_ID?.trim()
  || localCloudBase.environmentId
const cloudBaseAiModel = process.env.TARO_APP_CLOUDBASE_AI_MODEL?.trim()
  || localCloudBase.model
  || ''
const cloudBaseAiProviderGroup = process.env.TARO_APP_CLOUDBASE_AI_PROVIDER_GROUP?.trim()
  || localCloudBase.providerGroup
  || ''
const cloudBaseAiEnabled = enabled(process.env.TARO_APP_CLOUDBASE_AI_ENABLED)
const cloudBaseAiApproved = enabled(process.env.TARO_APP_CLOUDBASE_AI_APPROVED)
const cloudBaseAiEligible = enabled(process.env.TARO_APP_CLOUDBASE_AI_ELIGIBLE)
const cloudBaseAiModelReady = enabled(process.env.TARO_APP_CLOUDBASE_AI_MODEL_READY)
const configuredCloudBaseServiceName = process.env.TARO_APP_CLOUDBASE_SERVICE_NAME
const cloudBaseServiceName = configuredCloudBaseServiceName === undefined
  ? localCloudBase.serviceName ?? ''
  : configuredCloudBaseServiceName.trim()

const config: IProjectConfig<'webpack5'> = {
  projectName: 'ai-fitness-miniprogram',
  date: '2026-07-23',
  designWidth: 750,
  sourceRoot: 'src',
  outputRoot: 'dist',
  framework: 'react',
  compiler: 'webpack5',
  cache: {
    enable: false
  },
  defineConstants: {
    __FITNESS_API_BASE_URL__: JSON.stringify(apiBaseUrl),
    __FITNESS_CLOUDBASE_ENV_ID__: JSON.stringify(cloudBaseEnvironmentId),
    __FITNESS_CLOUDBASE_AI_MODEL__: JSON.stringify(cloudBaseAiModel),
    __FITNESS_CLOUDBASE_AI_PROVIDER_GROUP__: JSON.stringify(cloudBaseAiProviderGroup),
    __FITNESS_CLOUDBASE_AI_ENABLED__: JSON.stringify(cloudBaseAiEnabled),
    __FITNESS_CLOUDBASE_AI_APPROVED__: JSON.stringify(cloudBaseAiApproved),
    __FITNESS_CLOUDBASE_AI_ELIGIBLE__: JSON.stringify(cloudBaseAiEligible),
    __FITNESS_CLOUDBASE_AI_MODEL_READY__: JSON.stringify(cloudBaseAiModelReady),
    __FITNESS_CLOUDBASE_SERVICE_NAME__: JSON.stringify(cloudBaseServiceName)
  },
  mini: {
    postcss: {
      pxtransform: {
        enable: true,
        config: {}
      },
      cssModules: {
        enable: false,
        config: {
          namingPattern: 'module',
          generateScopedName: '[name]__[local]___[hash:base64:5]'
        }
      }
    }
  }
}

export default config

interface LocalCloudBaseConfig {
  environmentId: string
  model?: string
  providerGroup?: string
  serviceName?: string
}

function readLocalCloudBaseConfig(): LocalCloudBaseConfig {
  const path = resolve(process.cwd(), 'config', 'cloudbase.json.local')
  if (!existsSync(path)) return { environmentId: '' }
  try {
    const parsed: unknown = JSON.parse(readFileSync(path, 'utf8'))
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return { environmentId: '' }
    }
    const value = parsed as Record<string, unknown>
    return {
      environmentId: typeof value.environmentId === 'string' ? value.environmentId.trim() : '',
      model: typeof value.model === 'string' ? value.model.trim() : undefined,
      providerGroup: typeof value.providerGroup === 'string'
        ? value.providerGroup.trim()
        : undefined,
      serviceName: typeof value.serviceName === 'string' ? value.serviceName.trim() : undefined,
    }
  } catch {
    return { environmentId: '' }
  }
}

function enabled(value: string | undefined): boolean {
  return value?.trim().toLowerCase() === 'true'
}
