import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import type { ActivePlanData } from '../../../application/models'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'

import './index.scss'

const application = getWeappApplication()

export default function PlanPage() {
  const [plan, setPlan] = useState<ActivePlanData | null>(() => application.getActivePlan())
  const [error, setError] = useState('')

  useEffect(() => {
    void application.loadActivePlan()
      .then(setPlan)
      .catch(() => setError('活动计划加载失败，请检查网络后重试'))
  }, [])

  async function optimize(): Promise<void> {
    if (!application.openPlanEditor()) return
    await application.navigation.open('PLAN_EDITOR')
  }

  if (!plan) {
    return (
      <View className='screen'>
        <View className='card'>
          <Text className='title'>暂无活动计划</Text>
          <Text className='subtitle'>{error || '正在读取服务端活动版本…'}</Text>
          <Button className='primary-action' onClick={() => void application.navigation.replace('ONBOARDING')}>建立档案并生成计划</Button>
        </View>
      </View>
    )
  }

  return (
    <View className='screen'>
      <View className='card'>
        <Text className='title'>{plan.activeVersion.plan.name}</Text>
        <Text className='subtitle'>计划已生效 · 活动版本 v{plan.activeVersion.versionNumber}</Text>
        <View className='info-box'>今日训练执行将在 M2 接入；当前仅展示生效计划与周安排。</View>
      </View>

      {plan.activeVersion.plan.days.map((day) => (
        <View key={day.code} className='card plan-day'>
          <Text className='section-title'>{day.name}</Text>
          {day.exercises.map((exercise) => (
            <View key={exercise.exerciseCode} className='plan-exercise'>
              <Text>{exercise.exerciseCode}</Text>
              <Text>{exercise.workSets} × {exercise.repMin}～{exercise.repMax}</Text>
              <Text className='subtitle'>休息 {exercise.restSeconds} 秒</Text>
              <Text className='subtitle'>{exercise.weightStatus}</Text>
            </View>
          ))}
        </View>
      ))}

      <Button className='secondary-action' onClick={() => void optimize()}>重新优化（仅预览）</Button>
    </View>
  )
}
