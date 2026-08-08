import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')

function source(path: string): string {
  return readFileSync(resolve(projectRoot, path), 'utf8')
}

describe('release readiness user surfaces', () => {
  it('keeps full instructions in preparation and embeds the static breakdown in live training', () => {
    const config = source('src/app.config.ts')
    const navigation = source('src/application/navigation.ts')
    const prepare = source('src/presentation/pages/workout-prepare/index.tsx')
    const session = source('src/presentation/pages/workout-session/index.tsx')

    expect(config).toContain("root: 'subpackages/exercise-guide'")
    expect(config).toContain("'pages/detail/index'")
    expect(config).toContain("'pages/workout-session/index'")
    expect(navigation).toContain("'EXERCISE_DETAIL'")
    expect(prepare).toContain("application.navigation.open('EXERCISE_DETAIL'")
    expect(session).toContain("import ExerciseMotionGuide")
    expect(session).toContain('<ExerciseMotionGuide')
    expect(session).toContain('compact')
    expect(session).toContain('application.getExercise')
    expect(session).toContain('动作说明')
    expect(session).toContain('动作步骤')
    expect(session).toContain('呼吸提示')
    expect(session).toContain('常见错误')
    expect(session).toContain('安全提醒')
    expect(session).toContain('exerciseContent.instructions.map')
    expect(session).not.toContain("application.navigation.open('EXERCISE_DETAIL'")
  })

  it('provides a post-onboarding exclusion preference editor', () => {
    const config = source('src/app.config.ts')
    const profile = source('src/presentation/pages/my/index.tsx')
    const preferencePage = source('src/presentation/pages/exercise-preferences/index.tsx')

    expect(config).toContain('presentation/pages/exercise-preferences/index')
    expect(profile).toContain("application.navigation.open('EXERCISE_PREFERENCES')")
    expect(preferencePage).toContain('不推荐这些动作')
    expect(preferencePage).toContain('savePreferences')
  })

  it('shows exported records instead of only an aggregate count', () => {
    const profile = source('src/presentation/pages/my/index.tsx')

    expect(profile).toContain('exportedResources')
    expect(profile).toContain('resource.records.map')
  })
})
