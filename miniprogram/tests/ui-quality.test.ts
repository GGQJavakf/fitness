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
    for (const label of [
      '我已满 18 周岁，并理解这不是医疗或康复服务',
      '更多：5 天、6 天',
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
    expect(page).toMatch(/location === 'OTHER' && !otherEquipmentConfirmed[\s\S]*待选择器械范围/)
    expect(page).toContain('偏好动作（可选）')
    expect(page).toContain("setExercisePreference(exercise.id, 'PREFERRED')")
    expect(page).toContain("setExercisePreference(exercise.id, 'EXCLUDED')")
    expect(page).toContain('disabled={submitting}')
    expect(page).toContain('submittingRef.current')
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
    const home = source('src/presentation/pages/home/index.tsx')
    expect(navigation).not.toContain("{ destination: 'HOME', label: '首页' }")
    expect(home).not.toContain("current='HOME'")
    expect(home).not.toContain('MainNavigation')
  })

  it('offers every optional effort choice in plain language and submits the selection', () => {
    const workout = source('src/presentation/pages/workout-session/index.tsx')
    for (const label of ['训练余力（可选）', '已到极限', '还能 1 次', '还能 2 次', '还能 3 次以上', '不确定或跳过']) {
      expect(workout).toContain(label)
    }
    expect(workout).not.toContain('RIR（剩余次数）')
    expect(workout).toMatch(/rir:\s*status === 'COMPLETED'/)
  })

  it('keeps one-set recording compact, immediately acknowledged, and safely synchronized later', () => {
    const workout = source('src/presentation/pages/workout-session/index.tsx')
    const styles = source('src/presentation/pages/workout-session/index.scss')
    expect(workout).toContain('本组已保存在本地')
    expect(workout).toContain('syncRecordedSet')
    expect(workout).toContain('session-effort__toggle')
    expect(workout).toContain('session-recording-grid')
    expect(workout).toContain('action-row--sticky')
    expect(styles).toMatch(/session-recording-grid[\s\S]*grid-template-columns:\s*repeat\(2/)
    expect(styles).toMatch(/metric-input-wrap[\s\S]*min-height:\s*62px/)
    expect(styles).toMatch(/workout-actions \.secondary-action[\s\S]*min-height:\s*88px/)
    expect(styles).toMatch(/rir-options[\s\S]*grid-template-columns:\s*repeat\(3/)
    expect(styles).not.toMatch(/@media \(max-width: 360px\)[\s\S]*session-metrics[\s\S]*grid-template-columns:\s*1fr/)
  })

  it('starts every work set with its own editable actual-reps value', () => {
    const workout = source('src/presentation/pages/workout-session/index.tsx')

    expect(workout).toMatch(
      /setReps\(current \? String\(current\.targetReps\) : ''\)[\s\S]*state\?\.currentSetIndex/
    )
    expect(workout).toContain("value={reps}")
    expect(workout).not.toContain("value={reps || String(exercise.targetReps)}")
    expect(workout).not.toContain("Number(reps || exercise.targetReps)")
  })

  it('confirms formal weight once and generates warmup weights without repeated entry', () => {
    const workout = source('src/presentation/pages/workout-session/index.tsx')
    expect(workout).toContain('targetWeightKg')
    expect(workout).toContain('sessionWeightKg')
    expect(workout).toContain('buildRemainingRampWarmupSets')
    expect(workout).toContain('getExerciseTrend')
    expect(workout).toContain('最近有效重量')
    expect(workout).toContain('确认正式组重量')
    expect(workout).toContain('仅本次调整')
    expect(workout).not.toContain('记录并继续加重')
    expect(workout).toContain('weightDirtyRef.current')
    expect(workout).toContain('requestId !== weightSuggestionRequestRef.current')
    expect(workout).toContain("inputError?.field === 'weight'")
    expect(workout).toContain("inputError?.field === 'reps'")
    expect(workout).toContain("inputError?.field === 'action'")
    expect(workout).toMatch(/status === 'COMPLETED' && parsedReps === 0/)
    expect(workout).toMatch(/timerStatus === 'RUNNING'[\s\S]*setRemaining\(1\)/)
  })

  it('shows a compact per-exercise original cat static breakdown with one-tap switching', () => {
    const detail = source('src/subpackages/exercise-guide/pages/detail/index.tsx')
    const guide = source('src/subpackages/exercise-guide/components/exercise-motion-guide/index.tsx')
    const guidance = source('src/subpackages/exercise-guide/exercise-guidance.ts')
    const styles = source('src/subpackages/exercise-guide/components/exercise-motion-guide/index.scss')
    const workout = source('src/presentation/pages/workout-session/index.tsx')
    const plan = source('src/presentation/pages/plan/index.tsx')
    const candidate = source('src/presentation/pages/plan-candidates/index.tsx')
    expect(detail).toContain('ExerciseMotionGuide')
    expect(detail).toContain('exerciseCode={exercise.code}')
    expect(detail).toContain('primaryRef={exercise.image.primaryRef}')
    expect(guide).toContain('静态动作分解')
    expect(guide).toContain('motion-guide__stage-tab')
    expect(guide).toContain('onClick={() => setActiveStageIndex(index)}')
    expect(guide).toContain("id={`motion-guide-stage-${index + 1}`}")
    expect(guide).not.toMatch(/GIF|WebP|Video|animated/i)
    expect(styles).toMatch(/motion-guide--compact \.motion-guide__stage[\s\S]*height:\s*236px/)
    expect(styles).toMatch(/@media \(max-width: 360px\)[\s\S]*height:\s*208px/)
    expect(workout).toContain('<ExerciseMotionGuide')
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

  it('keeps plan number edits visible while users clear and retype values', () => {
    const editor = source('src/presentation/pages/plan-editor/index.tsx')
    expect(editor).toContain('rawValues')
    expect(editor).toContain('editableValueError')
    expect(editor).toContain('editor-field__error')
    expect(editor).not.toContain('if (event.detail.value.trim()) edit')
  })

  it('lets users choose any active-plan training day before starting', () => {
    const prepare = source('src/presentation/pages/workout-prepare/index.tsx')
    expect(prepare).toContain('今天这样练')
    expect(prepare).toMatch(/plan\.activeVersion\.plan\.days\.map/)
    expect(prepare).toContain('setSelectedDayCode')
    expect(prepare).toMatch(/days\.find\(\(item\) => item\.code === selectedDayCode\)/)
    expect(prepare).toContain('planDayId: day.code')
    expect(prepare).toContain('暂时无法判断上次完成到哪一天')
    expect(prepare).toContain('请手动确认')
  })

  it('restores interrupted workouts and lets history retry an AI summary by session id', () => {
    const home = source('src/presentation/pages/home/index.tsx')
    const history = source('src/presentation/pages/history/index.tsx')
    const summary = source('src/presentation/pages/workout-summary/index.tsx')
    expect(home).toContain('hasActiveWorkout')
    expect(home).toContain('继续本次训练')
    expect(history).toContain("{ sessionId: item.id }")
    expect(summary).toContain("routeParameter('sessionId')")
    expect(summary).toContain('重新生成 AI 总结')
  })
})
