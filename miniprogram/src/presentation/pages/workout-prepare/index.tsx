import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import type { ActivePlanData } from '../../../application/models'
import { selectNextTrainingDayCode } from '../../../application/selectNextTrainingDay'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { exerciseDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

export default function WorkoutPreparePage() {
  const [plan, setPlan] = useState<ActivePlanData | null>(null)
  const [selectedDayCode, setSelectedDayCode] = useState('')
  const [message, setMessage] = useState('正在准备今天的训练…')
  const [clientSessionKey] = useState(() => `weapp-session-${Date.now()}`)
  const [warmupMinutes, setWarmupMinutes] = useState<3 | 5 | 8>(3)
  const [loading, setLoading] = useState(true)
  const [starting, setStarting] = useState(false)

  async function loadPlan(): Promise<void> {
    setLoading(true)
    setMessage('正在准备今天的训练…')
    try {
      let historyUnavailable = false
      const [value, history] = await Promise.all([
        application.loadActivePlan(),
        application.listWorkoutHistory(undefined, 50).catch(() => {
          historyUnavailable = true
          return { items: [] }
        }),
      ])
      setPlan(value)
      setSelectedDayCode(value
        ? selectNextTrainingDayCode(value.activeVersion.plan.days, history.items)
        : '')
      setMessage(value
        ? historyUnavailable
          ? '计划已加载，但暂时无法判断上次完成到哪一天；当前临时选择第一训练日，请手动确认。'
          : '计划已就绪，确认今天的训练安排即可开始。'
        : '暂时没有可开始的训练计划。')
    } catch {
      setMessage('计划暂时无法读取，请检查网络后重试。')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadPlan()
  }, [])

  async function start(): Promise<void> {
    const day = plan?.activeVersion.plan.days.find((item) => item.code === selectedDayCode)
    if (!plan || !day || starting) return
    setStarting(true)
    setMessage('正在创建本次训练…')
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
      setMessage('本次训练暂未开始，请稍后重试；重复点击不会生成两次训练。')
      setStarting(false)
    }
  }

  const day = plan?.activeVersion.plan.days.find((item) => item.code === selectedDayCode)
  const totalSets = day?.exercises.reduce((sum, exercise) => sum + exercise.workSets, 0) ?? 0

  return (
    <View className='screen workout-prepare-page'>
      <View className='page-hero prepare-hero'>
        <Text className='page-hero__eyebrow'>TODAY&apos;S TRAINING</Text>
        <Text className='page-hero__title'>{day?.name ?? '训练准备'}</Text>
        <Text className='page-hero__description'>{message}</Text>
        {day && (
          <View className='prepare-hero__metrics'>
            <View className='prepare-hero__metric'>
              <Text className='prepare-hero__metric-value data-number'>{day.exercises.length}</Text>
              <Text className='prepare-hero__metric-label'>个动作</Text>
            </View>
            <View className='prepare-hero__divider' />
            <View className='prepare-hero__metric'>
              <Text className='prepare-hero__metric-value data-number'>{totalSets}</Text>
              <Text className='prepare-hero__metric-label'>个正式组</Text>
            </View>
            <View className='prepare-hero__divider' />
            <View className='prepare-hero__metric'>
              <Text className='prepare-hero__metric-value data-number'>{warmupMinutes}</Text>
              <Text className='prepare-hero__metric-label'>分钟热身</Text>
            </View>
          </View>
        )}
      </View>

      {!loading && !plan && (
        <View className='surface-card empty-state'>
          <Text className='section-title'>还没有可开始的计划</Text>
          <Text className='subtitle'>先生成科学训练方案，再回来开始训练。</Text>
          <Button className='primary-action' onClick={() => void application.navigation.replace('PLAN')}>查看我的计划</Button>
          <Button className='secondary-action' onClick={() => void loadPlan()}>重新读取</Button>
        </View>
      )}

      {plan && (
        <>
          <View className='section-heading'>
            <Text className='section-heading__title'>今天这样练</Text>
            <Text className='section-heading__meta'>选择训练内容</Text>
          </View>
          <View className='surface-card prepare-schedule'>
            <View className='prepare-readiness'>
              <View className='prepare-readiness__mark'>
                <View className='prepare-readiness__check' />
              </View>
              <View className='prepare-readiness__copy'>
                <Text className='prepare-readiness__title'>训练条件已匹配</Text>
                <Text className='prepare-readiness__description'>今天的动作、组数和休息时间已经按你的档案安排。</Text>
              </View>
            </View>
            <View className='day-options'>
              {plan.activeVersion.plan.days.map((option, index) => (
                <Button
                  key={option.code}
                  className={option.code === selectedDayCode ? 'day-option day-option--selected' : 'day-option'}
                  onClick={() => setSelectedDayCode(option.code)}
                >
                  <Text className='day-option__index data-number'>0{index + 1}</Text>
                  <View className='day-option__copy'>
                    <Text className='day-option__name'>{option.name}</Text>
                    <Text className='day-option__meta'>{option.exercises.length} 个动作</Text>
                  </View>
                  <View className='day-option__selected-mark' />
                </Button>
              ))}
            </View>
          </View>

          <View className='surface-card prepare-warmup'>
            <View>
              <Text className='section-title'>通用热身</Text>
              <Text className='subtitle'>按今天的身体状态选择时长，训练中会自动计时。</Text>
            </View>
            <View className='warmup-options'>
              {([3, 5, 8] as const).map((minutes) => (
                <Button
                  key={minutes}
                  className={minutes === warmupMinutes ? 'warmup-option warmup-option--selected' : 'warmup-option'}
                  onClick={() => setWarmupMinutes(minutes)}
                >
                  <Text className='warmup-option__value data-number'>{minutes}</Text>
                  <Text className='warmup-option__unit'>分钟</Text>
                </Button>
              ))}
            </View>
          </View>

          <View className='section-heading'>
            <Text className='section-heading__title'>训练顺序</Text>
            <Text className='section-heading__meta'>{day?.exercises.length ?? 0} 个动作</Text>
          </View>
          <View className='surface-card prepare-exercises'>
            {day?.exercises.map((exercise, index) => (
              <View className='prepare-exercise' key={exercise.exerciseCode}>
                <View className='prepare-exercise__index data-number'>{String(index + 1).padStart(2, '0')}</View>
                <View className='prepare-exercise__copy'>
                  <Text className='prepare-exercise__name'>{exerciseDisplayName(exercise.exerciseCode)}</Text>
                  <Text className='prepare-exercise__prescription'>
                    {exercise.workSets} 组 × {exercise.repMin}～{exercise.repMax} 次
                  </Text>
                  <Button
                    className='prepare-exercise__guide'
                    onClick={() => void application.navigation.open('EXERCISE_DETAIL', { exerciseCode: exercise.exerciseCode })}
                  >
                    查看动作说明
                  </Button>
                </View>
                <Text className='prepare-exercise__rest'>休 {exercise.restSeconds} 秒</Text>
              </View>
            ))}
          </View>

          <View className='prepare-note'>
            <View className='prepare-note__line' />
            <Text>先热身，再逐步接近正式训练重量。出现疼痛或明显不适时请停止。</Text>
          </View>
        </>
      )}

      <View className='action-row action-row--sticky prepare-actions'>
        <Button
          className='primary-action'
          loading={starting}
          disabled={!day || starting}
          onClick={() => void start()}
        >
          {starting ? '正在开始训练' : '开始热身'}
        </Button>
      </View>
    </View>
  )
}
