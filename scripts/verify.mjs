import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import {
  ALLOWED_RELEASE_ENVIRONMENT_KEYS,
  DEFAULT_LOCAL_RELEASE_ENVIRONMENT_FILE,
  loadMergedReleaseEnvironment
} from './release-environment.mjs'

const RELEASE_TARGETS = ['staging-experience', 'public']
const RELEASE_ENVIRONMENT_KEYS = [...ALLOWED_RELEASE_ENVIRONMENT_KEYS]
const BACKEND_ONLY_RELEASE_ENVIRONMENT_KEYS = RELEASE_ENVIRONMENT_KEYS.filter(
  (key) => !key.startsWith('TARO_APP_')
)
const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

export function parseVerificationTarget(argumentsList) {
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

export function buildVerificationSteps(platform, target) {
  if (!RELEASE_TARGETS.includes(target)) throw new Error(`Unsupported target: ${target}`)
  const windows = platform === 'win32'
  return [
    {
      label: 'mini-program verification',
      executable: windows ? 'npm.cmd' : 'npm',
      arguments: ['run', 'verify'],
      cwd: resolve(repositoryRoot, 'miniprogram'),
      unsetEnvironment: BACKEND_ONLY_RELEASE_ENVIRONMENT_KEYS
    },
    {
      label: 'release configuration preflight',
      executable: windows ? 'npm.cmd' : 'npm',
      arguments: ['run', target === 'public' ? 'preflight:release' : 'preflight:staging'],
      cwd: resolve(repositoryRoot, 'miniprogram')
    },
    {
      label: 'backend verification runtime preflight',
      executable: process.execPath,
      arguments: [resolve(repositoryRoot, 'scripts', 'assert-backend-verification-runtime.mjs')],
      cwd: repositoryRoot,
      unsetEnvironment: RELEASE_ENVIRONMENT_KEYS
    },
    {
      label: 'backend verification',
      executable: windows ? 'mvnw.cmd' : 'sh',
      arguments: windows ? ['clean', 'verify'] : ['./mvnw', 'clean', 'verify'],
      cwd: resolve(repositoryRoot, 'backend'),
      unsetEnvironment: RELEASE_ENVIRONMENT_KEYS
    },
    {
      label: 'backend zero-skip report gate',
      executable: process.execPath,
      arguments: [resolve(repositoryRoot, 'scripts', 'assert-maven-test-reports.mjs')],
      cwd: repositoryRoot,
      unsetEnvironment: RELEASE_ENVIRONMENT_KEYS
    },
    {
      label: 'backend runtime dependency audit',
      executable: process.execPath,
      arguments: [resolve(repositoryRoot, 'scripts', 'audit-maven-runtime-osv.mjs')],
      cwd: repositoryRoot,
      unsetEnvironment: RELEASE_ENVIRONMENT_KEYS
    }
  ]
}

function run() {
  try {
    const target = parseVerificationTarget(process.argv.slice(2))
    const selectedConfigurationFile = DEFAULT_LOCAL_RELEASE_ENVIRONMENT_FILE
    const configurationExists = existsSync(selectedConfigurationFile)
    const baseEnvironment = configurationExists
      ? loadMergedReleaseEnvironment(process.env, selectedConfigurationFile)
      : { ...process.env }
    if (configurationExists) {
      console.log(`[verify] loaded local release configuration: ${selectedConfigurationFile}`)
    }
    for (const step of buildVerificationSteps(process.platform, target)) {
      console.log(`\n[verify] ${step.label}`)
      const environment = { ...baseEnvironment }
      for (const key of step.unsetEnvironment ?? []) delete environment[key]
      const outcome = spawnSync(step.executable, step.arguments, {
        cwd: step.cwd,
        env: environment,
        shell: process.platform === 'win32',
        stdio: 'inherit'
      })
      if (outcome.error) throw outcome.error
      if (outcome.status !== 0) process.exit(outcome.status ?? 1)
    }
    console.log('\nRepository verification passed. No deployment or upload was performed.')
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) run()
