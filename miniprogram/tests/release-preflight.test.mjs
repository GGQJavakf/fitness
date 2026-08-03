import { describe, expect, it } from 'vitest'

import {
  inspectBackendEnvironment,
  inspectContentDocument,
  inspectExerciseAssets,
  inspectProjectConfiguration,
  inspectReleaseReadiness,
  parseReleaseTarget
} from '../scripts/release-preflight.mjs'

const configuredEnvironment = {
  WECHAT_APP_ID: 'wx1234567890abcdef',
  WECHAT_APP_SECRET: 'hidden-secret',
  FITNESS_DB_URL: 'hidden-database-url',
  FITNESS_DB_USERNAME: 'hidden-user',
  FITNESS_DB_PASSWORD: 'hidden-password',
  TARO_APP_CLOUDBASE_ENV_ID: 'hidden-environment',
  TARO_APP_CLOUDBASE_SERVICE_NAME: 'hidden-service'
}

function content(kind, status, environments) {
  return {
    metadata: {
      kind,
      status,
      activation: { enabled: true, environments }
    }
  }
}

describe('release preflight', () => {
  it('requires an explicit supported target', () => {
    expect(() => parseReleaseTarget([])).toThrow(/--target is required/)
    expect(() => parseReleaseTarget(['--target', 'production'])).toThrow(/staging-experience, public/)
    expect(parseReleaseTarget(['--target', 'public'])).toBe('public')
  })

  it('does not expose environment values when reporting missing keys', () => {
    const findings = inspectBackendEnvironment({
      WECHAT_APP_ID: configuredEnvironment.WECHAT_APP_ID,
      WECHAT_APP_SECRET: configuredEnvironment.WECHAT_APP_SECRET,
      FITNESS_DB_URL: '',
      FITNESS_DB_USERNAME: undefined,
      FITNESS_DB_PASSWORD: configuredEnvironment.FITNESS_DB_PASSWORD
    })
    expect(findings).toEqual([expect.objectContaining({
      level: 'BLOCKED',
      message: expect.stringContaining('FITNESS_DB_URL, FITNESS_DB_USERNAME')
    })])
    expect(findings[0].message).not.toContain(configuredEnvironment.WECHAT_APP_SECRET)
    expect(findings[0].message).not.toContain(configuredEnvironment.FITNESS_DB_PASSWORD)
  })

  it('blocks a mismatch between frontend and backend AppIDs without printing either value', () => {
    const findings = inspectProjectConfiguration({
      appid: 'wx1234567890abcdef',
      miniprogramRoot: 'miniprogram/dist/'
    }, {
      WECHAT_APP_ID: 'wxfedcba0987654321'
    })
    const mismatch = findings.find((finding) => finding.code === 'APP_ID_MISMATCH')
    expect(mismatch?.level).toBe('BLOCKED')
    expect(mismatch?.message).not.toContain('wx1234567890abcdef')
    expect(mismatch?.message).not.toContain('wxfedcba0987654321')
  })

  it('blocks AppID drift between the repository and Taro project configurations', () => {
    const findings = inspectProjectConfiguration({
      appid: 'wx1234567890abcdef',
      miniprogramRoot: 'miniprogram/dist/'
    }, {}, {
      appid: 'touristappid'
    })

    expect(findings).toContainEqual(expect.objectContaining({
      code: 'SOURCE_PROJECT_APP_ID_MISMATCH',
      level: 'BLOCKED'
    }))
  })

  it('allows AI validated content in staging but not in public', () => {
    const document = content('RULE_CONFIG', 'AI_VALIDATED', ['staging-experience'])
    expect(inspectContentDocument('训练规则', document, 'staging-experience'))
      .toEqual([expect.objectContaining({ level: 'PASS' }), expect.objectContaining({ level: 'PASS' })])
    expect(inspectContentDocument('训练规则', document, 'public'))
      .toEqual([expect.objectContaining({ level: 'BLOCKED' }), expect.objectContaining({ level: 'BLOCKED' })])
  })

  it('warns about placeholder exercise assets in staging and blocks them in public', () => {
    const exercises = {
      exercises: [
        { active: true, assetStatus: 'PLACEHOLDER_ONLY' },
        { active: false, assetStatus: 'PLACEHOLDER_ONLY' }
      ]
    }
    expect(inspectExerciseAssets(exercises, 'staging-experience')[0].level).toBe('WARN')
    expect(inspectExerciseAssets(exercises, 'public')[0].level).toBe('BLOCKED')
  })

  it('blocks a missing or empty active exercise catalog', () => {
    expect(inspectExerciseAssets(undefined, 'staging-experience'))
      .toEqual([expect.objectContaining({ code: 'EXERCISE_ASSETS', level: 'BLOCKED' })])
    expect(inspectExerciseAssets({ exercises: [] }, 'public'))
      .toEqual([expect.objectContaining({ code: 'EXERCISE_ASSETS', level: 'BLOCKED' })])
  })

  it('blocks unapproved exercise alternatives only for public release', () => {
    const exercises = {
      exercises: [{
        active: true,
        assetStatus: 'READY',
        alternatives: [{ reviewStatus: 'AI_VALIDATED' }]
      }]
    }
    expect(inspectExerciseAssets(exercises, 'staging-experience'))
      .toEqual([expect.objectContaining({ level: 'PASS' })])
    expect(inspectExerciseAssets(exercises, 'public'))
      .toEqual([
        expect.objectContaining({ level: 'PASS' }),
        expect.objectContaining({ code: 'EXERCISE_ALTERNATIVES', level: 'BLOCKED' })
      ])
  })

  it('passes a complete staging configuration', () => {
    const exerciseDocument = {
      ...content('EXERCISE_CONTENT', 'AI_VALIDATED', ['staging-experience']),
      exercises: [{ active: true, assetStatus: 'PLACEHOLDER_ONLY' }]
    }
    const findings = inspectReleaseReadiness({
      target: 'staging-experience',
      projectConfiguration: {
        appid: configuredEnvironment.WECHAT_APP_ID,
        miniprogramRoot: 'miniprogram/dist/'
      },
      environment: configuredEnvironment,
      contentDocuments: [
        ['动作内容', exerciseDocument],
        ['计划模板', content('PLAN_TEMPLATE', 'AI_VALIDATED', ['staging-experience'])],
        ['训练规则', content('RULE_CONFIG', 'AI_VALIDATED', ['staging-experience'])]
      ]
    })
    expect(findings.some((finding) => finding.level === 'BLOCKED')).toBe(false)
    expect(findings).toContainEqual(expect.objectContaining({
      code: 'EXERCISE_ASSETS',
      level: 'WARN'
    }))
  })
})
