import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import {
  deriveMiniprogramProjectConfiguration,
  prepareWechatProjectConfiguration,
} from '../../scripts/prepare-wechat-project-config.mjs'

const rootConfiguration = {
  miniprogramRoot: 'miniprogram/dist/',
  projectname: 'ai-fitness-miniprogram',
  appid: 'wx1234567890abcdef',
  libVersion: '3.15.1',
  compileType: 'miniprogram',
  setting: { urlCheck: true },
}

describe('local WeChat project configuration derivation', () => {
  it('keeps one root configuration and derives the Taro-local output root', () => {
    expect(deriveMiniprogramProjectConfiguration(rootConfiguration)).toEqual({
      ...rootConfiguration,
      miniprogramRoot: './dist',
    })
  })

  it('rejects a missing AppID or a non-canonical root path', () => {
    expect(() => deriveMiniprogramProjectConfiguration({
      ...rootConfiguration,
      appid: '',
    })).toThrow(/appid must be configured/)
    expect(() => deriveMiniprogramProjectConfiguration({
      ...rootConfiguration,
      miniprogramRoot: './dist',
    })).toThrow(/must be miniprogram\/dist\//)
  })

  it('runs derivation before every build and release preflight command', () => {
    const packageJson = JSON.parse(
      readFileSync(new URL('../package.json', import.meta.url), 'utf8')
    )
    expect(packageJson.scripts['prepare:project-config'])
      .toBe('node ../scripts/prepare-wechat-project-config.mjs')
    for (const name of ['build:weapp', 'dev:weapp', 'preflight:staging', 'preflight:release']) {
      expect(packageJson.scripts[name], name).toContain('npm run prepare:project-config')
    }
  })

  it('writes the derived ignored configuration without printing secrets', () => {
    const directory = mkdtempSync(resolve(tmpdir(), 'fitness-project-config-'))
    const sourcePath = resolve(directory, 'project.config.json')
    const destinationPath = resolve(directory, 'miniprogram-project.config.json')
    try {
      writeFileSync(sourcePath, JSON.stringify(rootConfiguration))
      prepareWechatProjectConfiguration({ sourcePath, destinationPath })
      expect(JSON.parse(readFileSync(destinationPath, 'utf8'))).toEqual({
        ...rootConfiguration,
        miniprogramRoot: './dist',
      })
    } finally {
      rmSync(directory, { recursive: true, force: true })
    }
  })
})
