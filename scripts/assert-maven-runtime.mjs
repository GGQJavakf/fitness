import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const backendRoot = resolve(repositoryRoot, 'backend')
const wrapperPropertiesPath = resolve(backendRoot, '.mvn', 'wrapper', 'maven-wrapper.properties')

export const MAVEN_RUNTIME_TIMEOUT_MS = 180_000

export function expectedMavenVersion(source) {
  const match = String(source).match(
    /^distributionUrl=https:\/\/repo\.maven\.apache\.org\/maven2\/org\/apache\/maven\/apache-maven\/(\d+\.\d+\.\d+)\/apache-maven-\1-bin\.zip$/m,
  )
  if (!match) {
    throw new Error('Maven Wrapper must use an exact HTTPS Maven Central distribution URL')
  }
  return match[1]
}

export function mavenVersionInvocation(platform) {
  return platform === 'win32'
    ? { executable: 'mvnw.cmd', arguments: ['-version'] }
    : { executable: 'sh', arguments: ['./mvnw', '-version'] }
}

export function inspectMavenRuntime({
  platform = process.platform,
  spawn = spawnSync,
  environment = process.env,
  timeoutMs = MAVEN_RUNTIME_TIMEOUT_MS,
  propertiesSource = readFileSync(wrapperPropertiesPath, 'utf8'),
} = {}) {
  const requiredVersion = expectedMavenVersion(propertiesSource)
  const invocation = mavenVersionInvocation(platform)
  const outcome = spawn(invocation.executable, invocation.arguments, {
    cwd: backendRoot,
    env: { ...environment, MVNW_VERBOSE: 'true' },
    encoding: 'utf8',
    timeout: timeoutMs,
    maxBuffer: 2 * 1024 * 1024,
    shell: platform === 'win32',
  })
  if (outcome.error?.code === 'ETIMEDOUT' || outcome.signal === 'SIGTERM') {
    throw new Error(`Maven Wrapper bootstrap exceeded ${Math.ceil(timeoutMs / 1000)} seconds`)
  }
  if (outcome.error) throw new Error(`Maven runtime preflight could not start: ${outcome.error.message}`)
  if (outcome.status !== 0) {
    throw new Error(`Maven runtime preflight failed with exit code ${outcome.status ?? 'unknown'}`)
  }

  const output = `${outcome.stdout ?? ''}\n${outcome.stderr ?? ''}`
  const maven = output.match(/Apache Maven (\d+\.\d+\.\d+)/)?.[1]
  const java = output.match(/Java version:\s*(\d+)(?:\.|\s|,)/i)?.[1]
  if (maven !== requiredVersion) {
    throw new Error(`Maven runtime version must be ${requiredVersion}; received ${maven ?? 'unknown'}`)
  }
  if (java !== '21') {
    throw new Error(`Maven runtime must use Java 21; received ${java ?? 'unknown'}`)
  }
  return { mavenVersion: maven, javaMajorVersion: Number(java) }
}

function run() {
  try {
    const runtime = inspectMavenRuntime()
    console.log(
      `[verify] Maven runtime: Apache Maven ${runtime.mavenVersion} on Java ${runtime.javaMajorVersion}.`,
    )
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) run()
