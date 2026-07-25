import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import type { ActivePlanData } from '../../../application/models'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { exerciseDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

export default function WorkoutPreparePage() {
  const [plan, setPlan] = useState<ActivePlanData | null>(null)
  const [selectedDayCode, setSelectedDayCode] = useState('')
  const [message, setMessage] = useState('正在读取活动计划…')
  const [clientSessionKey] = useState(() => `weapp-session-${Date.now()}`)
  const [warmupMinutes, setWarmupMinutes] = useState<3 | 5 | 8>(3)

  useEffect(() => {
    void application.loadActivePlan().then((value) => {
      setPlan(value)
      setSelectedDayCode(value?.activeVersion.plan.days[0]?.code ?? '')
      setMessage(value ? '训练开始后将固定保存本次快照。' : '没有可用的活动计划。')
    }).catch(() => setMessage('活动计划读取失败，请检查网络后重试。'))
  }, [])

  async function start(): Promise<void> {
    const day = plan?.activeVersion.plan.days.find((item) => item.code === selectedDayCode)
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
        warmupDurationSeconds: warmupMinutes * 60 as 180 | 300 | 480,
        exercises: session.exercises.map((exercise) => ({
          snapshotExerciseKey: exercise.id,
          exerciseCode: exercise.exerciseCode,
          name: exercise.exerciseName,
          targetWorkSets: exercise.prescription.workSets,
          targetReps: exercise.prescription.repMax,
          restSeconds: exercise.prescription.restSeconds,
          weightStatus: exercise.prescription.weightStatus,
          targetWeightKg: exercise.prescription.targetWeightKg,
        })),
      })
      application.telemetry.track('workout_started', { exerciseCount: session.exercises.length })
      await application.navigation.replace('WORKOUT_SESSION')
    } catch {
      setMessage('训练会话启动失败；再次点击会复用同一幂等键，不会创建重复会话。')
    }
  }

  const day = plan?.activeVersion.plan.days.find((item) => item.code === selectedDayCode)
  return <View className='screen'>
    <View className='card'>
      <Text className='title'>{day?.name ?? '训练准备'}</Text>
      <Text className='subtitle'>{message}</Text>
      <View className='warning-box'>先完成通用热身和动作递增热身。热身组不会计入训练容量或重量进阶。</View>
    </View>
    {plan && <View className='card'>
      <Text className='section-title'>通用热身时长</Text>
      <Text className='subtitle'>按今天状态选择，开始训练后会持续计时，切到后台也不会丢失。</Text>
      <View className='day-options warmup-options'>
        {([3, 5, 8] as const).map((minutes) => <Button key={minutes} className={minutes === warmupMinutes ? 'day-option day-option--selected' : 'day-option'} onClick={() => setWarmupMinutes(minutes)}>{minutes} 分钟</Button>)}
      </View>
    </View>}
    {plan && <View className='card'>
      <Text className='section-title'>选择今天训练哪一天</Text>
      <Text className='subtitle'>这里只选择本次训练快照，不会改动你的计划版本。</Text>
      <View className='day-options'>
        {plan.activeVersion.plan.days.map((option) => (
          <Button
            key={option.code}
            className={option.code === selectedDayCode ? 'day-option day-option--selected' : 'day-option'}
            onClick={() => setSelectedDayCode(option.code)}
          >
            <Text>{option.name}</Text>
            <Text className='code-label'>{option.exercises.length} 个动作</Text>
          </Button>
        ))}
      </View>
    </View>}
    <View className='card workout-list'>
      {day?.exercises.map((exercise) => <View className='workout-item' key={exercise.exerciseCode}>
        <View><Text>{exerciseDisplayName(exercise.exerciseCode)}</Text><Text className='code-label'>{exercise.exerciseCode}</Text></View>
        <Text className='subtitle'>{exercise.workSets} 组 × {exercise.repMin}～{exercise.repMax} 次 · 休息 {exercise.restSeconds} 秒</Text>
      </View>)}
    </View>
    <View className='action-row action-row--sticky'><Button className='primary-action' disabled={!day} onClick={() => void start()}>开始热身</Button></View>
  </View>
}
