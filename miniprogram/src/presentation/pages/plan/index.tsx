import { Button, Text, View } from '@tarojs/components'
import { useCallback, useEffect, useRef, useState } from 'react'

import type { ActivePlanData } from '../../../application/models'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import MainNavigation from '../../components/main-navigation'
import { exerciseDisplayName, weightStatusDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

export default function PlanPage() {
  const [plan, setPlan] = useState<ActivePlanData | null>(() => application.getActivePlan())
  const [loading, setLoading] = useState(() => application.getActivePlan() === null)
  const [error, setError] = useState('')
  const mounted = useRef(true)

  const loadPlan = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const nextPlan = await application.loadActivePlan()
      if (mounted.current) setPlan(nextPlan)
    } catch {
      if (mounted.current) setError('活动计划加载失败，请检查网络后重试')
    } finally {
      if (mounted.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void loadPlan()
    return () => {
      mounted.current = false
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
  const exerciseCount = activeVersion.plan.days.reduce(
    (total, day) => total + day.exercises.length,
    0,
  )

  return (
    <View className='plan-page screen screen--with-nav'>
      <View className='plan-overview'>
        <View className='plan-overview__brand'>
          <View className='plan-overview__status-dot' />
          <Text className='plan-overview__eyebrow'>当前科学计划 · 第 {activeVersion.versionNumber} 版</Text>
        </View>
        <Text className='plan-overview__title'>{activeVersion.plan.name}</Text>
        <Text className='plan-overview__subtitle'>按当前能力稳健开始，训练后的真实反馈会帮助下一次调整更准确。</Text>
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
            <Text className='plan-overview__metric-value'>规则</Text>
            <Text className='plan-overview__metric-label'>关键数值来源</Text>
          </View>
        </View>
        <Button className='plan-overview__start' onClick={() => void application.navigation.open('WORKOUT_PREPARE')}>开始今日训练</Button>
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
            </View>
          ))}
        </View>
      ))}

      <MainNavigation current='PLAN' onNavigate={(destination) => void application.navigation.replace(destination)} />
    </View>
  )
}
