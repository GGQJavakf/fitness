import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { describe, expect, it } from 'vitest'

import {
  DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY,
  loadMergedReleaseEnvironment,
  mergeReleaseEnvironment,
  parseReleaseEnvironment,
  resolveTaroBuildEnvironment
} from '../../scripts/release-environment.mjs'

describe('local release environment', () => {
  it('parses one key per line and preserves quoted special characters', () => {
    expect(parseReleaseEnvironment(`
# local staging release configuration
SPRING_PROFILES_ACTIVE="staging-experience"
FITNESS_DB_PASSWORD="p#ss=word with spaces"
TARO_APP_CLOUDBASE_AI_ENABLED=false
`)).toEqual({
      SPRING_PROFILES_ACTIVE: 'staging-experience',
      FITNESS_DB_PASSWORD: 'p#ss=word with spaces',
      TARO_APP_CLOUDBASE_AI_ENABLED: 'false'
    })
  })

  it('rejects malformed and duplicate keys without including their values in errors', () => {
    expect(() => parseReleaseEnvironment('WECHAT_APP_SECRET=first\nWECHAT_APP_SECRET=second'))
      .toThrow(/duplicate key WECHAT_APP_SECRET/i)
    expect(() => parseReleaseEnvironment('invalid-key=do-not-echo-this'))
      .toThrow(/line 1/i)
    try {
      parseReleaseEnvironment('WECHAT_APP_SECRET="unterminated-secret')
      throw new Error('expected parse failure')
    } catch (error) {
      expect(String(error)).not.toContain('unterminated-secret')
    }
  })

  it('uses the local file as the authoritative source for configured keys', () => {
    expect(mergeReleaseEnvironment(
      { SPRING_PROFILES_ACTIVE: 'local', UNRELATED: 'preserved' },
      { SPRING_PROFILES_ACTIVE: 'staging-experience', WECHAT_APP_ID: 'configured' }
    )).toEqual({
      SPRING_PROFILES_ACTIVE: 'staging-experience',
      UNRELATED: 'preserved',
      WECHAT_APP_ID: 'configured'
    })
  })

  it('uses the local file for Taro keys and permits only the explicit device URL override', () => {
    const resolved = resolveTaroBuildEnvironment({
      PATH: 'preserved',
      WECHAT_APP_SECRET: 'must-not-reach-taro',
      TARO_APP_CLOUDBASE_ENV_ID: 'stale-shell-environment',
      TARO_APP_API_BASE_URL: 'https://stale-shell.example',
      [DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY]: 'https://10.0.0.8:8443'
    }, {
      TARO_APP_CLOUDBASE_ENV_ID: 'configured-environment',
      TARO_APP_CLOUDBASE_SERVICE_NAME: 'fitness-api',
      TARO_APP_API_BASE_URL: 'https://configured.example'
    }, 'https://10.0.0.8:8443')

    expect(resolved).toMatchObject({
      PATH: 'preserved',
      TARO_APP_CLOUDBASE_ENV_ID: 'configured-environment',
      TARO_APP_CLOUDBASE_SERVICE_NAME: '',
      TARO_APP_API_BASE_URL: 'https://10.0.0.8:8443'
    })
    expect(resolved).not.toHaveProperty('WECHAT_APP_SECRET')
    expect(resolved).not.toHaveProperty(DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY)
  })

  it('loads an existing local file and rejects environment injection keys', () => {
    const directory = mkdtempSync(join(tmpdir(), 'fitness-release-environment-'))
    try {
      const valid = join(directory, 'valid.local')
      writeFileSync(valid, 'SPRING_PROFILES_ACTIVE="staging-experience"\n', 'utf8')
      expect(loadMergedReleaseEnvironment({ UNRELATED: 'preserved' }, valid)).toEqual({
        SPRING_PROFILES_ACTIVE: 'staging-experience',
        UNRELATED: 'preserved'
      })

      const unsafe = join(directory, 'unsafe.local')
      writeFileSync(unsafe, 'NODE_OPTIONS=--require=unexpected.js\n', 'utf8')
      expect(() => loadMergedReleaseEnvironment({}, unsafe)).toThrow(/unsupported.*NODE_OPTIONS/i)
    } finally {
      rmSync(directory, { recursive: true, force: true })
    }
  })
})
