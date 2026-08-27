import { spawnSync } from 'node:child_process'
import { pathToFileURL } from 'node:url'

const EXTERNAL_DATABASE_GROUPS = [
  ['FITNESS_TEST_MYSQL_JDBC_URL', 'FITNESS_TEST_MYSQL_USERNAME'],
  ['FITNESS_SMOKE_MYSQL_JDBC_URL', 'FITNESS_SMOKE_MYSQL_USERNAME'],
]

export function externalDatabaseVerificationConfigured(environment) {
  return EXTERNAL_DATABASE_GROUPS.every((keys) => (
    keys.every((key) => typeof environment[key] === 'string' && environment[key].trim().length > 0)
  ))
}

export function inspectDockerServer(spawn = spawnSync) {
  const outcome = spawn(
    'docker',
    ['info', '--format', '{{.ServerVersion}}'],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  )
  const version = typeof outcome.stdout === 'string' ? outcome.stdout.trim() : ''
  return {
    available: !outcome.error && outcome.status === 0 && version.length > 0,
    version,
  }
}

export function assertBackendVerificationRuntime(
  environment = process.env,
  spawn = spawnSync,
) {
  if (externalDatabaseVerificationConfigured(environment)) {
    return { mode: 'EXTERNAL_MYSQL' }
  }
  const docker = inspectDockerServer(spawn)
  if (!docker.available) {
    throw new Error(
      'Backend verification requires a reachable Docker server or both approved external '
      + 'MySQL test and packaged-smoke configurations; values are intentionally hidden.',
    )
  }
  return { mode: 'DOCKER', version: docker.version }
}

function run() {
  try {
    const runtime = assertBackendVerificationRuntime()
    if (runtime.mode === 'DOCKER') {
      console.log(`[verify] backend database runtime: Docker server ${runtime.version} is available.`)
    } else {
      console.log('[verify] backend database runtime: approved external MySQL configuration detected (values hidden).')
    }
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) run()
