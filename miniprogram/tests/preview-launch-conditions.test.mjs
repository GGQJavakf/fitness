import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

import {
  registeredMiniProgramPages,
  validatePreviewLaunchConditions,
} from '../scripts/check-preview-launch-conditions.mjs'

const appConfiguration = {
  pages: ['presentation/pages/home/index'],
  subPackages: [{
    root: 'subpackages/planning',
    pages: ['pages/plan/index'],
  }],
}

describe('WeChat preview launch conditions', () => {
  it('builds the registered route set from main pages and subpackages', () => {
    expect([...registeredMiniProgramPages(appConfiguration)].sort()).toEqual([
      'presentation/pages/home/index',
      'subpackages/planning/pages/plan/index',
    ])
  })

  it('blocks a stale private route and accepts the registered replacement', async () => {
    const directory = mkdtempSync(resolve(tmpdir(), 'fitness-preview-condition-'))
    const distDirectory = resolve(directory, 'dist')
    const appPath = resolve(distDirectory, 'app.json')
    const privatePath = resolve(directory, 'project.private.config.json')
    mkdirSync(distDirectory)
    writeFileSync(appPath, JSON.stringify(appConfiguration))
    try {
      writeFileSync(privatePath, JSON.stringify({
        condition: {
          miniprogram: {
            list: [{ name: 'plan-review', pathName: 'presentation/pages/plan/index' }],
          },
        },
      }))
      await expect(validatePreviewLaunchConditions({
        distAppConfigurationPath: appPath,
        privateConfigurationPaths: [privatePath],
      })).resolves.toEqual([
        expect.stringContaining(
          'plan-review -> presentation/pages/plan/index',
        ),
      ])

      writeFileSync(privatePath, JSON.stringify({
        condition: {
          miniprogram: {
            list: [{ name: 'plan-review', pathName: '/subpackages/planning/pages/plan/index/' }],
          },
        },
      }))
      await expect(validatePreviewLaunchConditions({
        distAppConfigurationPath: appPath,
        privateConfigurationPaths: [privatePath],
      })).resolves.toEqual([])
    } finally {
      rmSync(directory, { recursive: true, force: true })
    }
  })

  it('runs the guard in every WeChat build', () => {
    const packageJson = JSON.parse(
      readFileSync(fileURLToPath(new URL('../package.json', import.meta.url)), 'utf8'),
    )
    expect(packageJson.scripts['build:weapp'])
      .toContain('node scripts/check-preview-launch-conditions.mjs')
  })
})
