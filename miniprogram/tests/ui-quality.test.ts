import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')

function source(path: string): string {
  return readFileSync(resolve(projectRoot, path), 'utf8')
}

describe('P0 page usability contract', () => {
  it('provides consistent touch targets, safe-area spacing, and button reset', () => {
    const styles = source('src/app.scss')
    expect(styles).toMatch(/min-height:\s*88px/)
    expect(styles).toContain('env(safe-area-inset-bottom)')
    expect(styles).toMatch(/button::after/)
    expect(styles).toContain('.action-row--sticky')
  })

  it('labels onboarding choice groups and keeps actions reachable', () => {
    const page = source('src/presentation/pages/onboarding/index.tsx')
    for (const label of ['你的目标', '训练经验', '每周训练几天', '每次训练多久', '主要训练场地']) {
      expect(page).toContain(label)
    }
    expect(page).toContain('action-row--sticky')
  })

  it('does not expose raw JSON evidence or nested generic cards in complex pages', () => {
    const conflicts = source('src/presentation/pages/sync-conflicts/index.tsx')
    const editor = source('src/presentation/pages/plan-editor/index.tsx')
    expect(conflicts).not.toContain('JSON.stringify')
    expect(editor).toContain("className='editor-exercise'")
  })

  it('keeps plan, history, and privacy pages one tap apart', () => {
    for (const page of ['plan', 'history', 'my']) {
      expect(source(`src/presentation/pages/${page}/index.tsx`)).toContain('MainNavigation')
    }
  })

  it('offers every optional RIR choice in plain language and submits the selection', () => {
    const workout = source('src/presentation/pages/workout-session/index.tsx')
    for (const label of ['本组还能再做几次（可选）', '已到极限', '还能 1 次', '还能 2 次', '还能 3 次以上', '不确定或跳过']) {
      expect(workout).toContain(label)
    }
    expect(workout).toContain('RIR（剩余次数）')
    expect(workout).toMatch(/rir:\s*status === 'COMPLETED'/)
  })
})
