import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')

function source(path: string): string {
  return readFileSync(resolve(projectRoot, path), 'utf8')
}

describe('release readiness user surfaces', () => {
  it('registers the exercise guide subpackage and both user-facing routes', () => {
    const config = source('src/app.config.ts')

    expect(config).toContain("root: 'subpackages/exercise-guide'")
    expect(config).toContain("'pages/detail/index'")
    expect(config).toContain("'pages/workout-session/index'")
  })

  it('registers the post-onboarding exclusion preference route', () => {
    const config = source('src/app.config.ts')

    expect(config).toContain('presentation/pages/exercise-preferences/index')
  })
})
