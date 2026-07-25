import { networkInterfaces } from 'node:os'
import { spawnSync } from 'node:child_process'
import { pathToFileURL } from 'node:url'

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

export function buildDeviceApiBaseUrl(host, port = 8080) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('--port must be an integer between 1 and 65535')
  }
  return `http://${host}:${port}`
}

export function miniprogramBuildEnvironment(environment, apiBaseUrl) {
  const buildEnvironment = { ...environment, TARO_APP_API_BASE_URL: apiBaseUrl }
  for (const key of REQUIRED_ENVIRONMENT) delete buildEnvironment[key]
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
  const options = { build: false, host: undefined, port: 8080 }
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index]
    if (argument === '--build') {
      options.build = true
      continue
    }
    if (argument === '--host' || argument === '--port') {
      const value = argumentsList[index + 1]
      if (!value || value.startsWith('--')) throw new Error(`${argument} requires a value`)
      if (argument === '--host') options.host = value
      if (argument === '--port') options.port = Number(value)
      index += 1
      continue
    }
    throw new Error(`Unknown argument: ${argument}`)
  }
  return options
}

function run() {
  try {
    const options = parseArguments(process.argv.slice(2))
    const host = selectDeviceHost(options.host, flattenNetworkInterfaces())
    const apiBaseUrl = buildDeviceApiBaseUrl(host, options.port)
    const missing = missingRequiredEnvironment(process.env)

    console.log(`Device API base URL: ${apiBaseUrl}`)
    if (missing.length > 0) {
      console.error(`Missing backend environment keys: ${missing.join(', ')}`)
      console.error('Values are intentionally not printed. Configure them in the backend process environment.')
      process.exitCode = 2
      return
    }

    console.log('Backend credential and database environment keys are present (values hidden).')
    console.log(`Start backend with profile staging-experience and --server.address=${host} --server.port=${options.port}.`)

    if (!options.build) return
    const npmInvocation = resolveNpmInvocation(process.env, process.execPath)
    const result = spawnSync(npmInvocation.command, npmInvocation.arguments, {
      env: miniprogramBuildEnvironment(process.env, apiBaseUrl),
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
