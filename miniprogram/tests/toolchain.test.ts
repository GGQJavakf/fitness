import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')
const repositoryRoot = resolve(projectRoot, '..')

describe('Taro weapp toolchain', () => {
  it('provides the application entry and a home page', () => {
    expect(existsSync(resolve(projectRoot, 'src/app.tsx'))).toBe(true)
    expect(existsSync(resolve(projectRoot, 'src/presentation/pages/home/index.tsx'))).toBe(true)
  })

  it('registers the P0 onboarding and plan pages without App release targets', () => {
    const config = readFileSync(resolve(projectRoot, 'src/app.config.ts'), 'utf8')
    for (const page of ['home', 'onboarding', 'plan-candidates', 'plan-editor', 'plan']) {
      expect(config).toContain(`presentation/pages/${page}/index`)
    }
    expect(config).not.toMatch(/android|ios|app-release/i)
  })

  it('lets WeChat DevTools import the repository code directory directly', () => {
    const rootConfigPath = resolve(repositoryRoot, 'project.config.json')
    expect(existsSync(rootConfigPath)).toBe(true)

    const config = JSON.parse(readFileSync(rootConfigPath, 'utf8')) as {
      compileType?: string
      miniprogramRoot?: string
    }
    expect(config.compileType).toBe('miniprogram')
    expect(config.miniprogramRoot).toBe('miniprogram/dist/')
  })
})
