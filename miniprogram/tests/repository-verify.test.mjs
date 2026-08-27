import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

import {
  buildVerificationSteps,
  parseVerificationTarget
} from '../../scripts/verify.mjs'
import { ALLOWED_RELEASE_ENVIRONMENT_KEYS } from '../../scripts/release-environment.mjs'

describe('repository verification entry', () => {
  it('requires an explicit supported release target', () => {
    expect(() => parseVerificationTarget([])).toThrow(/--target is required/)
    expect(() => parseVerificationTarget(['--target', 'production'])).toThrow(/staging-experience, public/)
    expect(parseVerificationTarget(['--target', 'staging-experience'])).toBe('staging-experience')
  })

  it('composes frontend, release, Docker-or-MySQL, backend, zero-skip and audit gates', () => {
    const steps = buildVerificationSteps('linux', 'staging-experience')
    const releaseKeys = [...ALLOWED_RELEASE_ENVIRONMENT_KEYS]
    const backendOnlyKeys = releaseKeys.filter((key) => !key.startsWith('TARO_APP_'))

    expect(steps.map((step) => step.label)).toEqual([
      'mini-program verification',
      'release configuration preflight',
      'backend verification runtime preflight',
      'backend verification',
      'backend zero-skip report gate',
      'backend runtime dependency audit'
    ])
    expect(steps[0].arguments).toEqual(['run', 'verify'])
    expect(steps[0].unsetEnvironment).toEqual(backendOnlyKeys)
    expect(steps[1].arguments).toEqual(['run', 'preflight:staging'])
    expect(steps[1].unsetEnvironment).toBeUndefined()
    expect(steps[2].executable).toBe(process.execPath)
    expect(steps[2].arguments[0]).toMatch(/assert-backend-verification-runtime\.mjs$/)
    expect(steps[2].unsetEnvironment).toEqual(releaseKeys)
    expect(steps[3].executable).toBe('sh')
    expect(steps[3].arguments).toEqual(['./mvnw', 'clean', 'verify'])
    expect(steps[3].unsetEnvironment).toEqual(releaseKeys)
    expect(steps[4].executable).toBe(process.execPath)
    expect(steps[4].arguments[0]).toMatch(/assert-maven-test-reports\.mjs$/)
    expect(steps[4].unsetEnvironment).toEqual(releaseKeys)
    expect(steps[5].executable).toBe(process.execPath)
    expect(steps[5].arguments[0]).toMatch(/audit-maven-runtime-osv\.mjs$/)
    expect(steps[5].unsetEnvironment).toEqual(releaseKeys)
    expect(JSON.stringify(steps)).not.toMatch(/deploy|upload|release:publish/i)
  })

  it('documents one git-ignored local release configuration surface', () => {
    const example = readFileSync(new URL('../../.env.example', import.meta.url), 'utf8')
    const ignore = readFileSync(new URL('../../.gitignore', import.meta.url), 'utf8')
    for (const key of [
      'SPRING_PROFILES_ACTIVE',
      'WECHAT_APP_ID',
      'WECHAT_APP_SECRET',
      'FITNESS_DB_URL',
      'FITNESS_DB_USERNAME',
      'FITNESS_DB_PASSWORD',
      'FITNESS_TRUST_CLOUDBASE_IDENTITY_HEADERS',
      'TARO_APP_CLOUDBASE_ENV_ID',
      'TARO_APP_CLOUDBASE_SERVICE_NAME'
    ]) {
      expect(example).toMatch(new RegExp(`^${key}=`, 'm'))
    }
    expect(ignore).toMatch(/^\.env\.\*$/m)
    expect(ignore).toMatch(/^!\.env\.example$/m)
  })
})
