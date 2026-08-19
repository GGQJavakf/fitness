import { networkInterfaces } from 'node:os'
import { spawnSync } from 'node:child_process'
import { pathToFileURL } from 'node:url'

import {
  ALLOWED_RELEASE_ENVIRONMENT_KEYS,
  DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY,
  loadMergedReleaseEnvironment
} from '../../scripts/release-environment.mjs'

const REQUIRED_ENVIRONMENT = [
  'WECHAT_APP_ID',
  'WECHAT_APP_SECRET',
  'FITNESS_DB_URL',
  'FITNESS_DB_USERNAME',
  'FITNESS_DB_PASSWORD'
]

function ipv4Octets(address) {
  const parts = address.split('.').map(Number)
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return undefined
  }
  return parts
}

export function isPrivateIpv4(address) {
  const parts = ipv4Octets(address)
  if (!parts) return false
  return parts[0] === 10
    || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
    || (parts[0] === 192 && parts[1] === 168)
}

export function selectDeviceHost(explicitHost, interfaces) {
  const candidates = [...new Set(interfaces
    .filter((entry) => (entry.family === 'IPv4' || entry.family === 4)
      && !entry.internal
      && isPrivateIpv4(entry.address))
    .map((entry) => entry.address))]

  if (explicitHost) {
    if (!isPrivateIpv4(explicitHost)) {
      throw new Error('--host must be a private LAN IPv4 address, not loopback or public network')
    }
    if (!candidates.includes(explicitHost)) {
      throw new Error(`--host ${explicitHost} is not assigned to this computer; detected: ${candidates.join(', ') || 'none'}`)
    }
    return explicitHost
  }

  if (candidates.length === 1) return candidates[0]
  if (candidates.length === 0) {
    throw new Error('No private LAN IPv4 address detected; connect the computer and phone to the same LAN')
  }
  throw new Error(`Multiple private LAN addresses detected (${candidates.join(', ')}); select one with --host`)
}

export function missingRequiredEnvironment(environment) {
  return REQUIRED_ENVIRONMENT.filter((key) => !environment[key]?.trim())
}

export function buildDeviceApiBaseUrl(host, port = 8443) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('--port must be an integer between 1 and 65535')
  }
  return `https://${host}:${port}`
}

export function normalizeDeviceApiBaseUrl(value) {
  let parsed
  try {
    parsed = new URL(value)
  } catch {
    throw new Error('--api-base-url must be an absolute HTTPS URL')
  }
  if (parsed.protocol !== 'https:') {
    throw new Error('--api-base-url must use HTTPS')
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('--api-base-url must not contain credentials, query parameters, or fragments')
  }
  if (parsed.pathname !== '/' && parsed.pathname !== '') {
    throw new Error('--api-base-url must be an origin without a path')
  }
  return parsed.origin
}

export function miniprogramBuildEnvironment(environment, apiBaseUrl) {
  const buildEnvironment = {
    ...environment,
    TARO_APP_API_BASE_URL: apiBaseUrl,
    [DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY]: apiBaseUrl
  }
  for (const key of ALLOWED_RELEASE_ENVIRONMENT_KEYS) {
    if (!key.startsWith('TARO_APP_')) delete buildEnvironment[key]
  }
  return buildEnvironment
}

export function resolveNpmInvocation(environment, nodeExecutable, platform = process.platform) {
  if (environment.npm_execpath) {
    return {
      command: nodeExecutable,
      arguments: [environment.npm_execpath, 'run', 'build:weapp']
    }
  }
  if (platform === 'win32') {
    throw new Error('Run device builds through npm run build:weapp:device so npm_execpath is available')
  }
  return { command: 'npm', arguments: ['run', 'build:weapp'] }
}

function flattenNetworkInterfaces() {
  return Object.values(networkInterfaces()).flatMap((entries) => entries ?? [])
}

function parseArguments(argumentsList) {
  const options = { build: false, host: undefined, port: 8443, apiBaseUrl: undefined }
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index]
    if (argument === '--build') {
      options.build = true
      continue
    }
    if (argument === '--host' || argument === '--port' || argument === '--api-base-url') {
      const value = argumentsList[index + 1]
      if (!value || value.startsWith('--')) throw new Error(`${argument} requires a value`)
      if (argument === '--host') options.host = value
      if (argument === '--port') options.port = Number(value)
      if (argument === '--api-base-url') options.apiBaseUrl = value
      index += 1
      continue
    }
    throw new Error(`Unknown argument: ${argument}`)
  }
  if (options.apiBaseUrl && (options.host || options.port !== 8443)) {
    throw new Error('--api-base-url cannot be combined with --host or --port')
  }
  return options
}

function run() {
  try {
    const environment = loadMergedReleaseEnvironment(process.env)
    const options = parseArguments(process.argv.slice(2))
    const host = options.apiBaseUrl
      ? undefined
      : selectDeviceHost(options.host, flattenNetworkInterfaces())
    const apiBaseUrl = options.apiBaseUrl
      ? normalizeDeviceApiBaseUrl(options.apiBaseUrl)
      : buildDeviceApiBaseUrl(host, options.port)
    const missing = options.apiBaseUrl ? [] : missingRequiredEnvironment(environment)

    console.log(`Device API base URL: ${apiBaseUrl}`)
    if (missing.length > 0) {
      console.error(`Missing backend environment keys: ${missing.join(', ')}`)
      console.error('Values are intentionally not printed. Configure them in the backend process environment.')
      process.exitCode = 2
      return
    }

    if (host) {
      console.log('Backend credential and database environment keys are present (values hidden).')
      console.log(`Expose the staging-experience backend through a trusted HTTPS endpoint at ${apiBaseUrl}.`)
      console.log('The TLS endpoint may be a local reverse proxy; do not expose the Spring HTTP port directly to the phone.')
    } else {
      console.log('Using an explicit HTTPS API endpoint; local backend credential checks are not required.')
    }

    if (!options.build) return
    const npmInvocation = resolveNpmInvocation(environment, process.execPath)
    const result = spawnSync(npmInvocation.command, npmInvocation.arguments, {
      env: miniprogramBuildEnvironment(environment, apiBaseUrl),
      stdio: 'inherit'
    })
    if (result.error) throw result.error
    process.exitCode = result.status ?? 1
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) run()
