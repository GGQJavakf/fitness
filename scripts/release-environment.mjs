import { existsSync, lstatSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const MAX_CONFIGURATION_BYTES = 64 * 1024
export const DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY = 'FITNESS_DEVICE_BUILD_API_BASE_URL'
export const DEFAULT_LOCAL_RELEASE_ENVIRONMENT_FILE = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '.env.staging-experience.local'
)

export const ALLOWED_RELEASE_ENVIRONMENT_KEYS = new Set([
  'SPRING_PROFILES_ACTIVE',
  'WECHAT_APP_ID',
  'WECHAT_APP_SECRET',
  'FITNESS_DB_URL',
  'FITNESS_DB_USERNAME',
  'FITNESS_DB_PASSWORD',
  'FITNESS_TRUST_CLOUDBASE_IDENTITY_HEADERS',
  'TARO_APP_API_BASE_URL',
  'TARO_APP_CLOUDBASE_ENV_ID',
  'TARO_APP_CLOUDBASE_SERVICE_NAME',
  'TARO_APP_CLOUDBASE_AI_ENABLED',
  'TARO_APP_CLOUDBASE_AI_APPROVED',
  'TARO_APP_CLOUDBASE_AI_ELIGIBLE',
  'TARO_APP_CLOUDBASE_AI_MODEL_READY',
  'TARO_APP_CLOUDBASE_AI_PROVIDER_GROUP',
  'TARO_APP_CLOUDBASE_AI_MODEL'
])

export function parseReleaseEnvironment(source) {
  const configured = {}
  const lines = String(source).replace(/^\uFEFF/, '').split(/\r?\n/)
  for (let index = 0; index < lines.length; index += 1) {
    const lineNumber = index + 1
    const line = lines[index].trim()
    if (!line || line.startsWith('#')) continue

    const separator = line.indexOf('=')
    if (separator <= 0) throw configurationError(lineNumber)
    const key = line.slice(0, separator).trim()
    if (!/^[A-Z][A-Z0-9_]*$/.test(key)) throw configurationError(lineNumber)
    if (Object.hasOwn(configured, key)) {
      throw new Error(`Local release configuration contains duplicate key ${key} at line ${lineNumber}`)
    }

    const rawValue = line.slice(separator + 1).trim()
    configured[key] = parseValue(rawValue, lineNumber)
  }
  return configured
}

export function loadReleaseEnvironmentFile(path) {
  const absolutePath = resolve(path)
  const stats = lstatSync(absolutePath)
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error('Local release configuration must be a regular non-symbolic-link file')
  }
  if (stats.size > MAX_CONFIGURATION_BYTES) {
    throw new Error('Local release configuration exceeds the 64 KiB safety limit')
  }
  const configured = parseReleaseEnvironment(readFileSync(absolutePath, 'utf8'))
  const unsupported = Object.keys(configured).filter(
    (key) => !ALLOWED_RELEASE_ENVIRONMENT_KEYS.has(key)
  )
  if (unsupported.length > 0) {
    throw new Error(`Unsupported local release configuration key: ${unsupported.join(', ')}`)
  }
  return configured
}

export function mergeReleaseEnvironment(baseEnvironment, configuredEnvironment) {
  return { ...baseEnvironment, ...configuredEnvironment }
}

export function loadMergedReleaseEnvironment(
  baseEnvironment,
  path = DEFAULT_LOCAL_RELEASE_ENVIRONMENT_FILE
) {
  if (!existsSync(path)) return { ...baseEnvironment }
  return mergeReleaseEnvironment(baseEnvironment, loadReleaseEnvironmentFile(path))
}

export function resolveTaroBuildEnvironment(
  baseEnvironment,
  configuredEnvironment,
  deviceApiBaseUrlOverride
) {
  const resolved = { ...baseEnvironment }
  for (const key of ALLOWED_RELEASE_ENVIRONMENT_KEYS) {
    if (!key.startsWith('TARO_APP_')) delete resolved[key]
  }
  delete resolved[DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY]

  for (const [key, value] of Object.entries(configuredEnvironment)) {
    if (key.startsWith('TARO_APP_')) resolved[key] = value
  }
  if (deviceApiBaseUrlOverride?.trim()) {
    resolved.TARO_APP_API_BASE_URL = deviceApiBaseUrlOverride.trim()
    // A device/local API override is a direct HTTP origin. Keeping the
    // CloudBase service name would make createWeappTransport ignore that
    // origin and continue calling the container service instead.
    resolved.TARO_APP_CLOUDBASE_SERVICE_NAME = ''
  }
  return resolved
}

function parseValue(rawValue, lineNumber) {
  if (!rawValue.startsWith('"')) {
    if (/[\u0000\r\n]/.test(rawValue)) throw configurationError(lineNumber)
    return rawValue
  }
  try {
    const parsed = JSON.parse(rawValue)
    if (typeof parsed !== 'string' || /[\u0000\r\n]/.test(parsed)) {
      throw configurationError(lineNumber)
    }
    return parsed
  } catch {
    throw configurationError(lineNumber)
  }
}

function configurationError(lineNumber) {
  return new Error(`Invalid local release configuration at line ${lineNumber}`)
}
