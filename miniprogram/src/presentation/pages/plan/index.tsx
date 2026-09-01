import { Button, Text, View } from '@tarojs/components'
import { useCallback, useEffect, useRef, useState } from 'react'

import type { ActivePlanData } from '../../../application/models'
import { getPlanningApplication } from '../../../platform/weapp/featureRoots/planningCompositionRoot'
import { resolveActivePlanLoadFailure } from '../../activePlanLoadFailure'
import MainNavigation from '../../components/main-navigation'
import { exerciseDisplayName, trainingSplitDisplayName, weekdayDisplayName, weightStatusDisplayName } from '../../copy'

function planSourceEyebrow(aiPersonalized: boolean, systemPreset: boolean): string {
  if (aiPersonalized) return 'AI 个性化计划 · 规则已校验'
  if (systemPreset) return '系统个人预设 · 已按确认版本启用'
  return '规则生成计划 · 已通过安全校验'
}

function planSourceDescription(aiPersonalized: boolean, systemPreset: boolean): string {
  if (aiPersonalized) {
    return '已结合你的档案与训练偏好生成；训练后的真实反馈会帮助下一次调整更准确。'
  }
  if (systemPreset) {
    return '已按你确认的个人预设启用；热身、正式组、超级组、休息与进阶规则均随计划保存。'
  }
  return '已按你的档案、训练目标和可用器械由确定性规则引擎生成；训练后的真实反馈会帮助下一次调整更准确。'
}

export default function PlanPage() {
  const application = getPlanningApplication()
  const [plan, setPlan] = useState<ActivePlanData | null>(() => application.getActivePlan())
  const [loading, setLoading] = useState(() => application.getActivePlan() === null)
  const [error, setError] = useState('')
  const mounted = useRef(true)
  const loadInFlight = useRef(false)
  const loadRequestId = useRef(0)

  const loadPlan = useCallback(async () => {
    if (loadInFlight.current) return
    loadInFlight.current = true
    const requestId = ++loadRequestId.current
    setLoading(true)
    setError('')
    try {
      const nextPlan = await application.loadActivePlan()
      if (mounted.current && requestId === loadRequestId.current) setPlan(nextPlan)
    } catch (error: unknown) {
      const failure = resolveActivePlanLoadFailure(error)
      if (failure.kind === 'AUTHENTICATION_REQUIRED') {
        // The shared authentication handler already blocks, purges, and returns
        // to LOGIN. A page-level redirect would capture a newer account lease.
        return
      }
      if (mounted.current && requestId === loadRequestId.current) setError(failure.message)
    } finally {
      loadInFlight.current = false
      if (mounted.current && requestId === loadRequestId.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void loadPlan()
    return () => {
      mounted.current = false
      loadRequestId.current += 1
    }
  }, [loadPlan])

  if (!plan) {
    const title = loading ? '正在读取计划' : error ? '计划暂时不可用' : '暂无活动计划'
    const subtitle = loading
      ? '正在读取当前计划…'
      : error || '完成基础档案后，系统会直接给出科学训练建议。'
    const actionLabel = loading ? '读取中…' : error ? '重试加载' : '建立档案并生成计划'

    return (
      <View className='screen screen--with-nav'>
        <View className='card'>
          <Text className='title'>{title}</Text>
          <Text className='subtitle'>{subtitle}</Text>
          <Button
            className='primary-action'
            disabled={loading}
            onClick={() => {
              if (error) {
                void loadPlan()
                return
              }
              void application.navigation.replace('ONBOARDING')
            }}
          >
            {actionLabel}
          </Button>
          {!loading && !error && (
            <Button className='secondary-action' onClick={() => void application.navigation.open('PLAN_PRESETS')}>选择系统预设</Button>
          )}
        </View>
        <MainNavigation current='PLAN' onNavigate={(destination) => void application.navigation.replace(destination)} />
      </View>
    )
  }

  const activeVersion = plan.activeVersion
  const aiPersonalized = activeVersion.plan.templateCode === 'AI_PERSONALIZED'
  const systemPreset = Boolean(activeVersion.plan.presetCode)
  const exerciseCount = activeVersion.plan.days.reduce(
    (total, day) => total + day.exercises.length,
    0,
  )

  async function editPlan(): Promise<void> {
    application.openPlanEditor()
    await application.navigation.open('PLAN_EDITOR')
  }

  function executionGroupLabel(group: string): string {
    return `超级组 ${group.replace(/^SUPERSET_/, '')}`
  }

  function optionalSetRuleLabel(conditionCode: string): string {
    return conditionCode === 'TUESDAY_UNDER_42_GOOD_STATE'
      ? '当天用时在 42 分钟以内且状态良好，可在坐姿划船或哑铃弯举中任选一项增加 1 组；不要两项都加。'
      : '满足当天条件时可增加 1 个补充组。'
  }

  return (
    <View className='plan-page screen screen--with-nav'>
      <View className='plan-overview'>
        <View className='plan-overview__brand'>
          <View className='plan-overview__status-dot' />
          <Text className='plan-overview__eyebrow'>
            {planSourceEyebrow(aiPersonalized, systemPreset)}
            {' · 第 '}{activeVersion.versionNumber} 版
          </Text>
        </View>
        <Text className='plan-overview__title'>{activeVersion.plan.name}</Text>
        <Text className='plan-overview__subtitle'>
          {planSourceDescription(aiPersonalized, systemPreset)}
        </Text>
        <View className='plan-overview__metrics'>
          <View className='plan-overview__metric'>
            <Text className='plan-overview__metric-value'>{activeVersion.plan.days.length}</Text>
            <Text className='plan-overview__metric-label'>每周训练日</Text>
          </View>
          <View className='plan-overview__metric-divider' />
          <View className='plan-overview__metric'>
            <Text className='plan-overview__metric-value'>{exerciseCount}</Text>
            <Text className='plan-overview__metric-label'>计划动作</Text>
          </View>
          <View className='plan-overview__metric-divider' />
          <View className='plan-overview__metric'>
            <Text className='plan-overview__metric-value'>{trainingSplitDisplayName(activeVersion.plan.trainingSplit)}</Text>
            <Text className='plan-overview__metric-label'>训练分化</Text>
          </View>
        </View>
        <Button className='plan-overview__start' onClick={() => void application.navigation.open('WORKOUT_PREPARE')}>开始今日训练</Button>
        <Button className='plan-overview__edit' onClick={() => void editPlan()}>修改训练计划</Button>
        <Button className='plan-overview__preset' onClick={() => void application.navigation.open('PLAN_PRESETS')}>选择系统预设</Button>
      </View>

      {Boolean(activeVersion.plan.executionRules?.length || activeVersion.plan.progressionRules?.length) && (
        <View className='plan-rules'>
          <Text className='plan-rules__title'>执行与加重规则</Text>
          {activeVersion.plan.executionRules?.map((rule, index) => (
            <Text className='plan-rules__item' key={`execution-${index}`}>• {rule}</Text>
          ))}
          {activeVersion.plan.progressionRules?.map((rule, index) => (
            <Text className='plan-rules__item' key={`progression-${index}`}>• {rule}</Text>
          ))}
        </View>
      )}

      <View className='plan-feedback'>
        <View className='plan-feedback__mark'>
          <View className='plan-feedback__mark-line' />
          <View className='plan-feedback__mark-line plan-feedback__mark-line--short' />
          <View className='plan-feedback__mark-line plan-feedback__mark-line--medium' />
        </View>
        <View className='plan-feedback__copy'>
          <Text className='plan-feedback__title'>训练后再调整，更贴合你</Text>
          <Text className='plan-feedback__description'>系统会依据完成情况、余力和疼痛或不适记录生成进阶建议；信息不足时不会擅自改变计划。</Text>
          <Button className='plan-feedback__action' onClick={() => void application.navigation.replace('HISTORY')}>查看训练反馈与调整建议</Button>
        </View>
      </View>

      <View className='plan-section-heading'>
        <Text className='plan-section-heading__eyebrow'>WEEKLY ROUTINE</Text>
        <Text className='plan-section-heading__title'>本周训练安排</Text>
      </View>

      {activeVersion.plan.days.map((day, dayIndex) => (
        <View key={day.code} className='plan-day'>
          <View className='plan-day__heading'>
            <Text className='plan-day__index'>{String(dayIndex + 1).padStart(2, '0')}</Text>
            <View>
              <Text className='plan-day__title'>{day.name}</Text>
              <Text className='plan-day__count'>
                {[weekdayDisplayName(day.weekday), day.focus, day.estimatedMinutesMin && day.estimatedMinutesMax
                  ? `${day.estimatedMinutesMin}～${day.estimatedMinutesMax} 分钟`
                  : undefined].filter(Boolean).join(' · ') || `${day.exercises.length} 个动作 · 按顺序完成`}
              </Text>
            </View>
          </View>
          {day.warmup?.length ? (
            <View className='plan-warmup'>
              <Text className='plan-warmup__title'>热身</Text>
              {day.warmup.map((step, index) => (
                <Text className='plan-warmup__step' key={`${day.code}-warmup-${index}`}>
                  {index + 1}. {step.instruction}{step.prescription ? ` · ${step.prescription}` : ''}{step.optional ? '（可选）' : ''}
                </Text>
              ))}
            </View>
          ) : null}
          {day.notes?.length ? (
            <View className='plan-notes'>
              <Text className='plan-notes__title'>训练提示</Text>
              {day.notes.map((note, index) => (
                <Text className='plan-notes__item' key={`${day.code}-note-${index}`}>• {note}</Text>
              ))}
            </View>
          ) : null}
          {day.exercises.map((exercise) => (
            <View key={exercise.exerciseCode} className='plan-exercise'>
              <View className='plan-exercise__topline'>
                <Text className='plan-exercise__name'>{exerciseDisplayName(exercise.exerciseCode)}</Text>
                <Text className='plan-exercise__prescription'>{exercise.workSets} 组 × {exercise.repMin}～{exercise.repMax} 次</Text>
              </View>
              <View className='plan-exercise__meta'>
                <Text className='plan-exercise__tag'>休息 {exercise.restSeconds} 秒</Text>
                <Text className='plan-exercise__tag'>{weightStatusDisplayName(exercise.weightStatus)}</Text>
                {exercise.targetRirMin !== undefined && exercise.targetRirMax !== undefined && (
                  <Text className='plan-exercise__tag'>RIR {exercise.targetRirMin}～{exercise.targetRirMax}</Text>
                )}
                {exercise.eccentricSeconds && <Text className='plan-exercise__tag'>下放约 {exercise.eccentricSeconds} 秒</Text>}
                {exercise.perSide && <Text className='plan-exercise__tag'>每侧完成</Text>}
                {exercise.executionGroup && (
                  <Text className='plan-exercise__tag plan-exercise__tag--group'>
                    {executionGroupLabel(exercise.executionGroup)} · 第 {exercise.executionOrder} 个动作
                  </Text>
                )}
              </View>
              {exercise.optionalSetRule && (
                <Text className='plan-exercise__optional'>
                  {exercise.optionalSetRule.description ?? optionalSetRuleLabel(exercise.optionalSetRule.conditionCode)}
                </Text>
              )}
              {exercise.notes?.length ? (
                <View className='plan-exercise__notes'>
                  <Text className='plan-exercise__notes-title'>动作提示</Text>
                  {exercise.notes.map((note, index) => (
                    <Text className='plan-exercise__note' key={`${exercise.exerciseCode}-note-${index}`}>• {note}</Text>
                  ))}
                </View>
              ) : null}
              <Button
                className='plan-exercise__guide'
                onClick={() => void application.navigation.open('EXERCISE_DETAIL', {
                  exerciseCode: exercise.exerciseCode,
                })}
              >
                查看动作指导
              </Button>
            </View>
          ))}
        </View>
      ))}

      <MainNavigation current='PLAN' onNavigate={(destination) => void application.navigation.replace(destination)} />
    </View>
  )
}
