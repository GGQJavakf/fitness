import { readFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, resolve } from 'node:path'

import { loadMergedReleaseEnvironment } from '../../scripts/release-environment.mjs'

export const RELEASE_TARGETS = ['staging-experience', 'public']
export const SUPPORTED_SPRING_PROFILES = ['staging-experience']
export const MINIMUM_BASE_LIBRARY_VERSION = '3.7.1'

export const REQUIRED_BACKEND_ENVIRONMENT = [
  'WECHAT_APP_ID',
  'WECHAT_APP_SECRET',
  'FITNESS_DB_URL',
  'FITNESS_DB_USERNAME',
  'FITNESS_DB_PASSWORD'
]

export const REQUIRED_MINIPROGRAM_CLOUD_ENVIRONMENT = [
  'TARO_APP_CLOUDBASE_ENV_ID',
  'TARO_APP_CLOUDBASE_SERVICE_NAME'
]

const CONTENT_DOCUMENTS = [
  ['动作内容', 'exercises-v1.json'],
  ['计划模板', 'plan-templates-v1.json'],
  ['训练规则', 'rule-config-v1.json']
]

function result(level, code, message) {
  return { level, code, message }
}

function numericVersion(value) {
  if (typeof value !== 'string' || !/^\d+\.\d+\.\d+$/.test(value.trim())) return undefined
  return value.trim().split('.').map(Number)
}

export function inspectBaseLibraryVersion(projectConfiguration) {
  const configured = numericVersion(projectConfiguration?.libVersion)
  const minimum = numericVersion(MINIMUM_BASE_LIBRARY_VERSION)
  let supported = Boolean(configured && minimum)
  if (configured && minimum) {
    for (let index = 0; index < configured.length; index += 1) {
      if (configured[index] === minimum[index]) continue
      supported = configured[index] > minimum[index]
      break
    }
  }
  if (!supported) {
    return [result(
      'BLOCKED',
      'BASE_LIBRARY_VERSION',
      `微信基础库 libVersion 必须显式配置为 ${MINIMUM_BASE_LIBRARY_VERSION} 或更高版本`
    )]
  }
  return [result('PASS', 'BASE_LIBRARY_VERSION', '微信基础库版本满足发布要求')]
}

export function parseReleaseTarget(argumentsList) {
  let target
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index]
    if (argument !== '--target') throw new Error(`Unknown argument: ${argument}`)
    const value = argumentsList[index + 1]
    if (!value || value.startsWith('--')) throw new Error('--target requires a value')
    if (!RELEASE_TARGETS.includes(value)) {
      throw new Error(`--target must be one of: ${RELEASE_TARGETS.join(', ')}`)
    }
    if (target) throw new Error('--target may only be specified once')
    target = value
    index += 1
  }
  if (!target) throw new Error('--target is required')
  return target
}

export function inspectProjectConfiguration(
  projectConfiguration,
  environment,
  sourceProjectConfiguration
) {
  const findings = []
  const appId = projectConfiguration?.appid
  if (typeof appId !== 'string' || !/^wx[0-9a-zA-Z]{16}$/.test(appId)) {
    findings.push(result('BLOCKED', 'PROJECT_APP_ID', 'project.config.json 未配置有效的小程序 AppID'))
  } else {
    findings.push(result('PASS', 'PROJECT_APP_ID', '小程序 AppID 已配置'))
  }

  const environmentAppId = environment.WECHAT_APP_ID?.trim()
  if (environmentAppId && appId && environmentAppId !== appId) {
    findings.push(result(
      'BLOCKED',
      'APP_ID_MISMATCH',
      '后端 WECHAT_APP_ID 与 project.config.json 不一致（值已隐藏）'
    ))
  } else if (environmentAppId) {
    findings.push(result('PASS', 'APP_ID_MATCH', '前后端小程序 AppID 一致（值已隐藏）'))
  }

  if (sourceProjectConfiguration) {
    const sourceAppId = sourceProjectConfiguration.appid
    if (typeof sourceAppId !== 'string' || sourceAppId !== appId) {
      findings.push(result(
        'BLOCKED',
        'SOURCE_PROJECT_APP_ID_MISMATCH',
        'Taro project.config.json 与仓库根小程序 AppID 不一致（值已隐藏）'
      ))
    } else {
      findings.push(result(
        'PASS',
        'SOURCE_PROJECT_APP_ID_MATCH',
        'Taro 与仓库根小程序 AppID 一致（值已隐藏）'
      ))
    }
  }

  if (projectConfiguration?.miniprogramRoot !== 'miniprogram/dist/') {
    findings.push(result(
      'BLOCKED',
      'MINIPROGRAM_ROOT',
      'miniprogramRoot 必须指向 miniprogram/dist/'
    ))
  } else {
    findings.push(result('PASS', 'MINIPROGRAM_ROOT', '小程序构建目录配置正确'))
  }
  return findings
}

export function inspectBackendEnvironment(environment) {
  const missing = REQUIRED_BACKEND_ENVIRONMENT.filter((key) => !environment[key]?.trim())
  if (missing.length > 0) {
    return [result(
      'BLOCKED',
      'BACKEND_ENVIRONMENT',
      `缺少后端环境变量键：${missing.join(', ')}（值不会输出）`
    )]
  }
  return [result(
    'PASS',
    'BACKEND_ENVIRONMENT',
    '微信身份和数据库环境变量键均已配置（值已隐藏）'
  )]
}

export function inspectTrustedCloudBaseIdentityIngress(environment) {
  if (environment.FITNESS_TRUST_CLOUDBASE_IDENTITY_HEADERS?.trim().toLowerCase() !== 'true') {
    return [result(
      'BLOCKED',
      'TRUSTED_CLOUDBASE_IDENTITY_INGRESS',
      'CloudBase 发布必须显式确认 MINIAPP 私有入口后才能信任平台身份头'
    )]
  }
  return [result(
    'PASS',
    'TRUSTED_CLOUDBASE_IDENTITY_INGRESS',
    '已显式启用经 MINIAPP 私有入口注入的 CloudBase 身份头'
  )]
}

export function inspectSpringProfile(environment, target) {
  if (!RELEASE_TARGETS.includes(target)) throw new Error(`Unsupported target: ${target}`)
  const profile = environment.SPRING_PROFILES_ACTIVE?.trim()
  if (!profile || !SUPPORTED_SPRING_PROFILES.includes(profile)) {
    return [result(
      'BLOCKED',
      'SPRING_PROFILE',
      `SPRING_PROFILES_ACTIVE 必须显式配置为受支持的发布 profile：${SUPPORTED_SPRING_PROFILES.join(', ')}`
    )]
  }
  return [result('PASS', 'SPRING_PROFILE', `${target} 使用受支持的非本地 Spring profile`)]
}

export function inspectMiniProgramCloudEnvironment(environment) {
  const missing = REQUIRED_MINIPROGRAM_CLOUD_ENVIRONMENT.filter((key) => !environment[key]?.trim())
  if (missing.length > 0) {
    return [result(
      'BLOCKED',
      'MINIPROGRAM_CLOUD_ENVIRONMENT',
      `缺少小程序云托管环境变量键：${missing.join(', ')}（值不会输出）`
    )]
  }
  return [result(
    'PASS',
    'MINIPROGRAM_CLOUD_ENVIRONMENT',
    '小程序云环境和云托管服务名均已配置（值已隐藏）'
  )]
}

export function inspectMiniProgramAiEnvironment(environment) {
  if (environment.TARO_APP_CLOUDBASE_AI_ENABLED?.trim().toLowerCase() !== 'true') {
    return [result(
      'PASS',
      'MINIPROGRAM_AI_DISABLED',
      '小程序在线 AI 已关闭，使用确定性规则与模板路径'
    )]
  }

  const booleanReadiness = [
    'TARO_APP_CLOUDBASE_AI_APPROVED',
    'TARO_APP_CLOUDBASE_AI_ELIGIBLE',
    'TARO_APP_CLOUDBASE_AI_MODEL_READY'
  ].every((key) => environment[key]?.trim().toLowerCase() === 'true')
  const providerGroup = environment.TARO_APP_CLOUDBASE_AI_PROVIDER_GROUP?.trim()
  const validProviderGroup = providerGroup === 'cloudbase'
    || providerGroup === 'hunyuan-exp'
    || /^custom-[A-Za-z0-9][A-Za-z0-9_-]{0,62}$/.test(providerGroup ?? '')
  const modelConfigured = Boolean(environment.TARO_APP_CLOUDBASE_AI_MODEL?.trim())
  if (!booleanReadiness || !validProviderGroup || !modelConfigured) {
    return [result(
      'BLOCKED',
      'MINIPROGRAM_AI_READINESS',
      '在线 AI 已启用，但审批、计费资格、Provider Group 或模型就绪证明不完整（值已隐藏）'
    )]
  }
  return [result(
    'PASS',
    'MINIPROGRAM_AI_READINESS',
    '在线 AI 审批、计费资格、Provider Group 和模型就绪证明均已配置（值已隐藏）'
  )]
}

export function inspectContentDocument(label, document, target) {
  const findings = []
  const metadata = document?.metadata ?? {}
  const eligibleStatuses = target === 'public'
    ? ['PUBLIC_RELEASE_APPROVED']
    : ['AI_VALIDATED', 'PUBLIC_RELEASE_APPROVED']

  if (!eligibleStatuses.includes(metadata.status)) {
    findings.push(result(
      'BLOCKED',
      `CONTENT_STATUS_${metadata.kind ?? 'UNKNOWN'}`,
      `${label}状态 ${metadata.status ?? 'MISSING'} 不允许用于 ${target}`
    ))
  } else {
    findings.push(result(
      'PASS',
      `CONTENT_STATUS_${metadata.kind ?? 'UNKNOWN'}`,
      `${label}发布状态允许用于 ${target}`
    ))
  }

  const environments = metadata.activation?.environments
  if (metadata.activation?.enabled !== true || !Array.isArray(environments) || !environments.includes(target)) {
    findings.push(result(
      'BLOCKED',
      `CONTENT_ACTIVATION_${metadata.kind ?? 'UNKNOWN'}`,
      `${label}未启用 ${target} 环境`
    ))
  } else {
    findings.push(result(
      'PASS',
      `CONTENT_ACTIVATION_${metadata.kind ?? 'UNKNOWN'}`,
      `${label}已启用 ${target} 环境`
    ))
  }
  return findings
}

export function inspectExerciseAssets(exerciseDocument, target) {
  const activeExercises = Array.isArray(exerciseDocument?.exercises)
    ? exerciseDocument.exercises.filter((exercise) => exercise?.active === true)
    : []
  if (activeExercises.length === 0) {
    return [result('BLOCKED', 'EXERCISE_ASSETS', '未找到可发布的启用动作')]
  }
  const placeholderCount = activeExercises.filter(
    (exercise) => exercise.assetStatus === 'PLACEHOLDER_ONLY'
  ).length
  const unapprovedAlternatives = activeExercises.flatMap(
    (exercise) => Array.isArray(exercise.alternatives) ? exercise.alternatives : []
  ).filter((alternative) => alternative.reviewStatus !== 'PUBLIC_RELEASE_APPROVED').length

  const findings = []
  if (placeholderCount > 0) {
    findings.push(result(
      target === 'public' ? 'BLOCKED' : 'WARN',
      'EXERCISE_ASSETS',
      `${placeholderCount} 个启用动作仍使用占位资源`
    ))
  } else {
    findings.push(result('PASS', 'EXERCISE_ASSETS', '启用动作均已配置正式资源'))
  }

  if (target === 'public' && unapprovedAlternatives > 0) {
    findings.push(result(
      'BLOCKED',
      'EXERCISE_ALTERNATIVES',
      `${unapprovedAlternatives} 个动作替代关系尚未通过公开发布审核`
    ))
  }
  return findings
}

export function inspectReleaseReadiness({
  target,
  projectConfiguration,
  sourceProjectConfiguration,
  environment,
  contentDocuments
}) {
  if (!RELEASE_TARGETS.includes(target)) throw new Error(`Unsupported target: ${target}`)
  const findings = [
    ...inspectProjectConfiguration(projectConfiguration, environment, sourceProjectConfiguration),
    ...inspectBaseLibraryVersion(sourceProjectConfiguration ?? projectConfiguration),
    ...inspectBackendEnvironment(environment),
    ...inspectTrustedCloudBaseIdentityIngress(environment),
    ...inspectSpringProfile(environment, target),
    ...inspectMiniProgramCloudEnvironment(environment),
    ...inspectMiniProgramAiEnvironment(environment)
  ]
  for (const [label, document] of contentDocuments) {
    findings.push(...inspectContentDocument(label, document, target))
  }
  const exerciseDocument = contentDocuments.find(
    ([, document]) => document?.metadata?.kind === 'EXERCISE_CONTENT'
  )?.[1]
  findings.push(...inspectExerciseAssets(exerciseDocument, target))
  return findings
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'))
}

function loadWorkspaceInputs() {
  const scriptDirectory = dirname(fileURLToPath(import.meta.url))
  const repositoryRoot = resolve(scriptDirectory, '..', '..')
  const contentRoot = resolve(repositoryRoot, 'rule-config', 'validated')
  return {
    projectConfiguration: readJson(resolve(repositoryRoot, 'project.config.json')),
    sourceProjectConfiguration: readJson(resolve(repositoryRoot, 'miniprogram', 'project.config.json')),
    contentDocuments: CONTENT_DOCUMENTS.map(([label, file]) => [
      label,
      readJson(resolve(contentRoot, file))
    ])
  }
}

function run() {
  try {
    const target = parseReleaseTarget(process.argv.slice(2))
    const inputs = loadWorkspaceInputs()
    const findings = inspectReleaseReadiness({
      target,
      environment: loadMergedReleaseEnvironment(process.env),
      ...inputs
    })

    console.log(`Release preflight target: ${target}`)
    for (const finding of findings) {
      const output = `[${finding.level}] ${finding.message}`
      if (finding.level === 'BLOCKED') console.error(output)
      else console.log(output)
    }
    console.log('[ACTION] 真机恢复矩阵、完整测试和依赖审计证据需在发布候选 HEAD 上单独完成。')

    const blockers = findings.filter((finding) => finding.level === 'BLOCKED')
    if (blockers.length > 0) {
      console.error(`Release preflight blocked by ${blockers.length} item(s).`)
      process.exitCode = 2
      return
    }
    console.log('Release configuration preflight passed.')
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) run()
