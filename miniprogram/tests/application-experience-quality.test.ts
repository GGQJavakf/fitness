import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const projectRoot = resolve(import.meta.dirname, '..')

function source(path: string): string {
  return readFileSync(resolve(projectRoot, path), 'utf8')
}

describe('application-wide premium experience contract', () => {
  it('uses one restrained health-tech design system across the mini program', () => {
    const styles = source('src/app.scss')

    for (const token of ['#0b2f28', '#0f6b57', '#46c794', '#f5f7f3', '#151b18']) {
      expect(styles.toLowerCase()).toContain(token)
    }
    expect(styles).toContain("'PingFang SC'")
    expect(styles).toContain("'DIN Alternate'")
    expect(styles).not.toMatch(/-apple-system|system-ui|Arial|Roboto|Inter/)
    expect(styles).toContain('.surface-card')
    expect(styles).toContain('.page-hero')
  })

  it('uses the plan as the authenticated landing surface with a clear progress section', () => {
    const navigation = source('src/presentation/components/main-navigation/index.tsx')
    const home = source('src/presentation/pages/home/index.tsx')
    const appConfig = source('src/app.config.ts')

    for (const destination of ["'PLAN'", "'HISTORY'", "'MY'"]) {
      expect(navigation).toContain(destination)
    }
    expect(navigation).not.toContain("'HOME'")
    expect(navigation).toContain("label: '进展'")
    expect(home).not.toContain("current='HOME'")
    expect(home).not.toContain('screen--with-nav')
    expect(home).toContain('正在打开你的训练计划')
    expect(appConfig).toContain('presentation/pages/plan-editor/index')
  })

  it('turns workout preparation into a clear pre-flight checklist without engineering copy', () => {
    const page = source('src/presentation/pages/workout-prepare/index.tsx')

    for (const marker of [
      'prepare-hero',
      'prepare-readiness',
      'prepare-schedule',
      'prepare-exercises',
      '今天这样练',
      '开始热身',
    ]) {
      expect(page).toContain(marker)
    }
    for (const internalCopy of ['原子保存', '幂等键', '本次快照', "<Text className='code-label'>{exercise.exerciseCode}</Text>"]) {
      expect(page).not.toContain(internalCopy)
    }
  })

  it('keeps the live workout focused on one set and explains effort in plain language', () => {
    const page = source('src/presentation/pages/workout-session/index.tsx')

    for (const marker of [
      'session-hero',
      'session-progress',
      'session-recording-grid',
      '训练余力（可选）',
      "weightStatus === 'BODYWEIGHT'",
      '自重动作无需填写重量',
      "className='action-row action-row--sticky workout-actions'",
      'isWorkoutPrescriptionFinished(resumed.state)',
      '完成本组',
      '疼痛或明显不适',
    ]) {
      expect(page).toContain(marker)
    }
    for (const internalCopy of ['RIR（剩余次数）', '服务端检测', '本地事实', '保留两份证据']) {
      expect(page).not.toContain(internalCopy)
    }
  })

  it('presents summary, progress, and trend pages as readable coaching surfaces', () => {
    const summary = source('src/presentation/pages/workout-summary/index.tsx')
    const history = source('src/presentation/pages/history/index.tsx')
    const trend = source('src/presentation/pages/exercise-trend/index.tsx')
    const recommendation = source('src/presentation/components/progression-card/index.tsx')

    expect(summary).toContain('summary-hero')
    expect(summary).toContain('summary-metrics')
    expect(summary).toContain('summary-coach')
    expect(history).toContain('history-hero')
    expect(history).toContain('history-section')
    expect(history).toContain('训练进展')
    expect(trend).toContain('trend-hero')
    expect(trend).toContain('trend-timeline')
    expect(trend).not.toContain("<Text className='code-label'>{exerciseCode}</Text>")
    expect(recommendation).not.toContain("<Text className='code-label'>{card.exerciseCode}</Text>")
    expect(recommendation).not.toContain('重量由服务端规则计算')
    expect(summary).toContain('summary.usesExternalLoad')
    expect(summary).toContain('summary.completedReps')
    expect(history).not.toContain('由你决定是否采用')
  })

  it('keeps workout summary headlines aligned with actual completion state', () => {
    const summary = source('src/presentation/pages/workout-summary/index.tsx')

    expect(summary).toContain("summary.complete")
    expect(summary).toContain("'训练完成'")
    expect(summary).toContain("'本次训练已记录'")
    expect(summary).not.toContain('完成得很好')
  })

  it('automatically settles a fully completed workout and initializes each set independently', () => {
    const summary = source('src/presentation/pages/workout-summary/index.tsx')
    const session = source('src/presentation/pages/workout-session/index.tsx')

    expect(summary).toContain('autoSettlementStarted')
    expect(summary).toMatch(/if \(nextSummary\.complete\)[\s\S]*completeWorkout/)
    expect(session).toMatch(
      /useEffect\(\(\) => \{[\s\S]*setReps\(current \? String\(current\.targetReps\) : ''\)[\s\S]*currentSetIndex/
    )
    expect(session).toMatch(/setShowWeightEditor\(false\)[\s\S]*currentExerciseIndex/)
  })

  it('keeps profile and conflict recovery calm, progressive, and user-facing', () => {
    const profile = source('src/presentation/pages/my/index.tsx')
    const conflicts = source('src/presentation/pages/sync-conflicts/index.tsx')

    expect(profile).toContain('profile-hero')
    expect(profile).toContain('showDeletion')
    expect(profile).not.toContain('P0 仅面向')
    expect(conflicts).toContain('conflict-hero')
    expect(conflicts).toContain('设备中的记录')
    expect(conflicts).toContain('已同步的记录')
    expect(conflicts).not.toContain('服务端记录')
    expect(conflicts).not.toContain('两份证据')
  })

  it('keeps page bottoms reachable on compact and full-screen devices', () => {
    const globalStyles = source('src/app.scss')
    const navigationStyles = source('src/presentation/components/main-navigation/index.scss')
    const pages = [
      'workout-prepare',
      'workout-session',
      'workout-summary',
      'history',
      'exercise-trend',
      'my',
      'sync-conflicts',
    ]

    expect(globalStyles).toContain('env(safe-area-inset-bottom)')
    expect(navigationStyles).toContain('env(safe-area-inset-bottom)')
    expect(navigationStyles).toContain('grid-template-columns: repeat(3, 1fr)')
    expect(navigationStyles).not.toContain('grid-template-columns: repeat(4, 1fr)')
    for (const page of pages) {
      expect(source(`src/presentation/pages/${page}/index.scss`)).toMatch(/@media\s*\(max-width:\s*360px\)/)
    }
  })
})
