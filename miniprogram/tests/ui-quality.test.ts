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
    for (const label of ['你的目标', '训练经验', '训练分化', '每周训练几天', '每次训练多久', '主要训练场地']) {
      expect(page).toContain(label)
    }
    for (const label of [
      '我已满 18 周岁，并理解这不是医疗或康复服务',
      '全身训练',
      '2 分化',
      '3 分化',
      '5 分化',
      '更多：75 分钟、90 分钟',
      '修改训练方向',
      '修改训练安排',
      '待选择器械范围',
      '生成我的计划',
    ]) {
      expect(page).toContain(label)
    }
    for (const internalCopy of ['KNOWN / NEEDS_CALIBRATION / BODYWEIGHT', '当前 P0', '规则引擎']) {
      expect(page).not.toContain(internalCopy)
    }
    expect(page).toContain('偏好动作（可选）')
    expect(page).toContain('action-row--sticky')
    expect(page).toMatch(/'HYPERTROPHY'[\s\S]*'FAT_LOSS'[\s\S]*'GENERAL_FITNESS'/)
    expect(page).not.toMatch(/\['STRENGTH',/)
  })

  it('does not expose raw JSON evidence or nested generic cards in complex pages', () => {
    const conflicts = source('src/presentation/pages/sync-conflicts/index.tsx')
    const editor = source('src/presentation/pages/plan-editor/index.tsx')
    expect(conflicts).not.toContain('JSON.stringify')
    expect(editor).toContain("className='editor-exercise'")
  })

  it('avoids an untyped pseudo selector rejected by the WXSS compiler', () => {
    const styles = source('src/presentation/pages/plan-editor/index.scss')
    expect(styles).not.toMatch(/>\s+:[\w-]+/)
  })

  it('keeps plan, history, and privacy pages one tap apart', () => {
    for (const page of ['plan', 'history', 'my']) {
      expect(source(`src/presentation/pages/${page}/index.tsx`)).toContain('MainNavigation')
    }
  })

  it('uses the plan as the authenticated landing navigation instead of a duplicate home tab', () => {
    const navigation = source('src/presentation/components/main-navigation/index.tsx')
    const bootstrap = source('src/presentation/pages/home/index.tsx')
    const home = source('src/subpackages/startup/pages/home/index.tsx')
    expect(navigation).not.toContain("{ destination: 'HOME', label: '首页' }")
    expect(bootstrap).not.toContain('MainNavigation')
    expect(home).not.toContain("current='HOME'")
    expect(home).not.toContain('MainNavigation')
  })

  it('keeps one-set recording controls reachable on compact screens', () => {
    const styles = source('src/presentation/pages/workout-session/index.scss')
    expect(styles).toMatch(/session-recording-grid[\s\S]*grid-template-columns:\s*repeat\(2/)
    expect(styles).toMatch(/metric-input-wrap[\s\S]*min-height:\s*88px/)
    expect(styles).toMatch(/workout-actions \.secondary-action[\s\S]*min-height:\s*88px/)
    expect(styles).toMatch(/rir-options[\s\S]*grid-template-columns:\s*repeat\(3/)
    expect(styles).not.toMatch(/@media \(max-width: 360px\)[\s\S]*session-metrics[\s\S]*grid-template-columns:\s*1fr/)
  })

  it('keeps recovery content clear of the hero and summary actions compact', () => {
    const sessionStyles = source('src/presentation/pages/workout-session/index.scss')
    const summaryStyles = source('src/presentation/pages/workout-summary/index.scss')

    expect(sessionStyles).toMatch(/\.rest-controls\s*\{[^}]*margin-top:\s*0/)
    expect(sessionStyles).not.toMatch(/\.rest-controls\s*\{[^}]*margin-top:\s*-/)
    expect(summaryStyles).toMatch(/\.summary-actions\s*>\s*button\s*\{[^}]*flex:\s*0\s+0\s+auto/)
    expect(summaryStyles).toMatch(/\.summary-actions\s*\{[^}]*padding-bottom:\s*calc\(12px\s*\+\s*env\(safe-area-inset-bottom\)\)/)
  })

  it('keeps warmup prescription server-authoritative and guards asynchronous suggestions', () => {
    const workout = source('src/presentation/pages/workout-session/index.tsx')
    expect(workout).toContain('remainingRampWarmupSets')
    expect(workout).not.toContain('buildRampWarmupSets')
    expect(workout).not.toContain('记录并继续加重')
    expect(workout).toContain('weightDirtyRef.current')
    expect(workout).toContain('requestId !== weightSuggestionRequestRef.current')
  })

  it('shows a compact per-exercise original cat static breakdown with one-tap switching', () => {
    const detail = source('src/subpackages/exercise-guide/pages/detail/index.tsx')
    const guide = source('src/subpackages/exercise-guide/components/exercise-motion-guide/index.tsx')
    const guidance = source('src/subpackages/exercise-guide/exercise-guidance.ts')
    const styles = source('src/subpackages/exercise-guide/components/exercise-motion-guide/index.scss')
    const workout = source('src/presentation/pages/workout-session/index.tsx')
    const plan = source('src/presentation/pages/plan/index.tsx')
    const candidate = source('src/presentation/pages/plan-candidates/index.tsx')
    expect(guide).toContain('动作步骤插画')
    expect(guide).toContain('motion-guide__stage-tab')
    expect(guide).toContain('onClick={() => setActiveStageIndex(index)}')
    expect(guide).toContain("id={`motion-guide-stage-${index + 1}`}")
    expect(guide).not.toMatch(/GIF|WebP|Video|animated/i)
    expect(styles).toMatch(/motion-guide--compact \.motion-guide__stage[\s\S]*height:\s*236px/)
    expect(styles).toMatch(/@media \(max-width: 360px\)[\s\S]*height:\s*208px/)
    expect(workout).toContain('<WorkoutExerciseMotionGuide')
    expect(workout).toContain('动作示例按需加载')
    expect(workout).toContain('showMotionGuide ?')
    expect(workout).toContain('compact')
    expect(workout).toContain('session-exercise-guidance')
    expect(workout).toContain('exerciseContent?.plainLanguage')
    expect(workout).toContain('exerciseContent.instructions.map')
    expect(workout).toContain('exerciseContent.breathingCues.map')
    expect(workout).toContain('exerciseContent.commonMistakes.map')
    expect(workout).toContain('exerciseContent.safetyCues.map')
    expect(workout).not.toContain("application.navigation.open('EXERCISE_DETAIL'")
    expect(guidance).toContain("rule-config/validated/exercises-v1.json")
    expect(workout).toContain("resolveExerciseGuidance(currentExerciseCode)")
    expect(plan).toMatch(/EXERCISE_DETAIL[\s\S]*exerciseCode:\s*exercise\.exerciseCode/)
    expect(candidate).toMatch(/EXERCISE_DETAIL[\s\S]*exerciseCode:\s*exercise\.exerciseCode/)
  })

})
