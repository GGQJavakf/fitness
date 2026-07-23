import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')

describe('Taro weapp toolchain', () => {
  it('provides the application entry and a home page', () => {
    expect(existsSync(resolve(projectRoot, 'src/app.tsx'))).toBe(true)
    expect(existsSync(resolve(projectRoot, 'src/presentation/pages/home/index.tsx'))).toBe(true)
  })

  it('registers only the P0 home page in the initial skeleton', () => {
    const config = readFileSync(resolve(projectRoot, 'src/app.config.ts'), 'utf8')
    expect(config).toContain("presentation/pages/home/index")
    expect(config).not.toMatch(/android|ios|app-release/i)
  })
})
