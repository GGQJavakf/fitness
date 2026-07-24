import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import type { ActivePlanData } from '../../../application/models'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { exerciseDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

export default function WorkoutPreparePage() {
  const [plan, setPlan] = useState<ActivePlanData | null>(null)
  const [message, setMessage] = useState('正在读取活动计划…')
  const [clientSessionKey] = useState(() => `weapp-session-${Date.now()}`)

  useEffect(() => {
    void application.loadActivePlan().then((value) => {
      setPlan(value)
      setMessage(value ? '训练开始后将固定保存本次快照。' : '没有可用的活动计划。')
    }).catch(() => setMessage('活动计划读取失败，请检查网络后重试。'))
  }, [])

  async function start(): Promise<void> {
    const day = plan?.activeVersion.plan.days[0]
    if (!plan || !day) return
    setMessage('正在原子保存训练草稿…')
    try {
      const session = await application.startWorkoutSession({
        clientSessionKey,
        planId: plan.planId,
        planVersionNo: plan.activeVersion.versionNumber,
        planDayId: day.code,
      })
      await application.workouts.start({
        clientSessionKey,
        planVersionId: session.planVersionId,
        serverSessionId: session.id,
        serverVersion: session.version,
        exercises: session.exercises.map((exercise) => ({
          snapshotExerciseKey: exercise.id,
          exerciseCode: exercise.exerciseCode,
          name: exercise.exerciseName,
          targetWorkSets: exercise.prescription.workSets,
          targetReps: exercise.prescription.repMax,
          restSeconds: exercise.prescription.restSeconds,
        })),
      })
      application.telemetry.track('workout_started', { exerciseCount: session.exercises.length })
      await application.navigation.replace('WORKOUT_SESSION')
    } catch {
      setMessage('训练会话启动失败；再次点击会复用同一幂等键，不会创建重复会话。')
    }
  }

  const day = plan?.activeVersion.plan.days[0]
  return <View className='screen'>
    <View className='card'>
      <Text className='title'>{day?.name ?? '训练准备'}</Text>
      <Text className='subtitle'>{message}</Text>
      <View className='warning-box'>先完成通用热身和动作递增热身。热身组不会计入训练容量或重量进阶。</View>
    </View>
    <View className='card workout-list'>
      {day?.exercises.map((exercise) => <View className='workout-item' key={exercise.exerciseCode}>
        <View><Text>{exerciseDisplayName(exercise.exerciseCode)}</Text><Text className='code-label'>{exercise.exerciseCode}</Text></View>
        <Text className='subtitle'>{exercise.workSets} 组 × {exercise.repMin}～{exercise.repMax} 次 · 休息 {exercise.restSeconds} 秒</Text>
      </View>)}
    </View>
    <View className='action-row action-row--sticky'><Button className='primary-action' disabled={!day} onClick={() => void start()}>完成热身，进入训练</Button></View>
  </View>
}
