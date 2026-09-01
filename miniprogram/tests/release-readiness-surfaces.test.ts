import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')

function source(path: string): string {
  return readFileSync(resolve(projectRoot, path), 'utf8')
}

describe('release readiness user surfaces', () => {
  it('registers exercise guidance and live workout routes in their owning feature packages', () => {
    const config = source('src/app.config.ts')

    expect(config).toContain("root: 'subpackages/exercise-guide'")
    expect(config).toContain("'pages/detail/index'")
    expect(config).toContain("root: 'subpackages/workout'")
    expect(config).toContain("'pages/workout-session/index'")
  })

  it('registers the post-onboarding exclusion preference route', () => {
    const config = source('src/app.config.ts')

    expect(config).toContain("root: 'subpackages/account'")
    expect(config).toContain("'pages/exercise-preferences/index'")
    expect(config).not.toContain('presentation/pages/exercise-preferences/index')
  })
})
