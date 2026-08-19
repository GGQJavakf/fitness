import { Button, Text, View } from '@tarojs/components'
import { useCallback, useEffect, useRef, useState } from 'react'

import type { ActivePlanData, TrainingSplit } from '../../../application/models'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { resolveActivePlanLoadFailure } from '../../activePlanLoadFailure'
import MainNavigation from '../../components/main-navigation'
import { exerciseDisplayName, weightStatusDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

function trainingSplitLabel(split: TrainingSplit | undefined): string {
  if (split === 'UPPER_LOWER') return '上下肢'
  if (split === 'PUSH_PULL_LEGS') return '推拉腿'
  if (split === 'BODY_PART_FIVE_DAY') return '五分化'
  return '未记录'
}

export default function PlanPage() {
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
        if (!mounted.current) return
        try {
          await application.navigation.replace('HOME')
        } catch {
          if (mounted.current) setError('登录状态已失效，请返回首页重新登录')
        }
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
        </View>
        <MainNavigation current='PLAN' onNavigate={(destination) => void application.navigation.replace(destination)} />
      </View>
    )
  }

  const activeVersion = plan.activeVersion
  const aiPersonalized = activeVersion.plan.templateCode === 'AI_PERSONALIZED'
  const exerciseCount = activeVersion.plan.days.reduce(
    (total, day) => total + day.exercises.length,
    0,
  )

  async function editPlan(): Promise<void> {
    application.openPlanEditor()
    await application.navigation.open('PLAN_EDITOR')
  }

  return (
    <View className='plan-page screen screen--with-nav'>
      <View className='plan-overview'>
        <View className='plan-overview__brand'>
          <View className='plan-overview__status-dot' />
          <Text className='plan-overview__eyebrow'>
            {aiPersonalized ? 'AI 个性化计划 · 规则已校验' : '规则生成计划 · 已通过安全校验'}
            {' · 第 '}{activeVersion.versionNumber} 版
          </Text>
        </View>
        <Text className='plan-overview__title'>{activeVersion.plan.name}</Text>
        <Text className='plan-overview__subtitle'>
          {aiPersonalized
            ? '已结合你的档案与训练偏好生成；训练后的真实反馈会帮助下一次调整更准确。'
            : '已按你的档案、训练目标和可用器械由确定性规则引擎生成；训练后的真实反馈会帮助下一次调整更准确。'}
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
            <Text className='plan-overview__metric-value'>{trainingSplitLabel(activeVersion.plan.trainingSplit)}</Text>
            <Text className='plan-overview__metric-label'>训练分化</Text>
          </View>
        </View>
        <Button className='plan-overview__start' onClick={() => void application.navigation.open('WORKOUT_PREPARE')}>开始今日训练</Button>
        <Button className='plan-overview__edit' onClick={() => void editPlan()}>修改训练计划</Button>
      </View>

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
              <Text className='plan-day__count'>{day.exercises.length} 个动作 · 按顺序完成</Text>
            </View>
          </View>
          {day.exercises.map((exercise) => (
            <View key={exercise.exerciseCode} className='plan-exercise'>
              <View className='plan-exercise__topline'>
                <Text className='plan-exercise__name'>{exerciseDisplayName(exercise.exerciseCode)}</Text>
                <Text className='plan-exercise__prescription'>{exercise.workSets} 组 × {exercise.repMin}～{exercise.repMax} 次</Text>
              </View>
              <View className='plan-exercise__meta'>
                <Text className='plan-exercise__tag'>休息 {exercise.restSeconds} 秒</Text>
                <Text className='plan-exercise__tag'>{weightStatusDisplayName(exercise.weightStatus)}</Text>
              </View>
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
