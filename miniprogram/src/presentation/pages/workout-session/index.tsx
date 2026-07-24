import { Button, Input, Text, View } from '@tarojs/components'
import { useState } from 'react'

import type { WorkoutFlowState } from '../../../application/workoutFlow'
import type { ExerciseReplacementCandidate } from '../../../application/ports/WorkoutReplacementPort'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { useWeappDidShow } from '../../../platform/weapp/lifecycle'

import './index.scss'

const application = getWeappApplication()

export default function WorkoutSessionPage() {
  const [state, setState] = useState<WorkoutFlowState | null>(null)
  const [weight, setWeight] = useState('')
  const [reps, setReps] = useState('')
  const [remaining, setRemaining] = useState(0)
  const [message, setMessage] = useState('正在恢复训练草稿…')
  const [replacements, setReplacements] = useState<readonly ExerciseReplacementCandidate[]>([])

  useWeappDidShow(() => {
    void application.workouts.load().then(async (loaded) => {
      if (!loaded) { setMessage('没有可恢复的训练，请从计划页开始。'); return }
      const resumed = await application.workouts.resume(loaded)
      setState(resumed.state)
      setRemaining(resumed.remainingSeconds)
      setMessage(resumed.clockRollbackDetected ? '检测到设备时间回拨，计时已按最近可信时间校准。' : '草稿已恢复。')
    }).catch(() => setMessage('训练草稿损坏或读取失败，未伪造任何完成记录。'))
  })

  async function record(status: 'COMPLETED' | 'FAILED' | 'SKIPPED', discomfort: 'NONE' | 'PAIN' = 'NONE'): Promise<void> {
    if (!state) return
    if (status === 'COMPLETED' && weight.trim().length === 0) {
      setMessage('请填写实际重量；系统不会把空值静默当成 0 KG。')
      return
    }
    const parsedWeight = weight.trim().length > 0 ? Number(weight) : undefined
    const parsedReps = Number(reps || state.exercises[state.currentExerciseIndex].targetReps)
    if (parsedWeight !== undefined && (!Number.isFinite(parsedWeight) || parsedWeight < 0)) {
      setMessage('实际重量必须是有效的非负 KG 数值。')
      return
    }
    if (!Number.isSafeInteger(parsedReps) || parsedReps < 0) {
      setMessage('实际次数必须是有效的非负整数。')
      return
    }
    const exerciseIndex = state.currentExerciseIndex
    const exercise = state.exercises[exerciseIndex]
    const clientSetKey = `${state.clientSessionKey}-${exerciseIndex}-${state.currentSetIndex}`
    const updated = await application.workouts.recordSet(state, {
      clientSetKey,
      exerciseIndex,
      setType: 'WORK',
      status,
      actualWeightKg: status === 'SKIPPED' ? undefined : parsedWeight,
      actualReps: status === 'SKIPPED' ? undefined : parsedReps,
      discomfort,
    })
    setState(updated)
    const resumed = await application.workouts.resume(updated)
    setState(resumed.state)
    setRemaining(resumed.remainingSeconds)
    setMessage(status === 'COMPLETED' ? '本组已先保存到本地草稿。' : '本组已按真实状态记录，不计作正常完成。')
    void application.workouts.flush(resumed.state).then((synced) => {
      setState(synced)
      if (synced.syncStatus === 'CONFLICT') setMessage('服务端检测到事实冲突，请在冲突页显式选择。')
      if (synced.syncStatus === 'SYNC_REJECTED') setMessage('服务端拒绝了该操作，本地事实仍保留，请检查输入。')
    }).catch(() => setMessage('网络不可用，本组已保存在本地，稍后自动补传。'))
  }

  async function adjust(seconds: 15 | -15): Promise<void> {
    if (!state) return
    const updated = await application.workouts.adjustRest(state, seconds)
    const resumed = await application.workouts.resume(updated)
    setState(resumed.state); setRemaining(resumed.remainingSeconds)
  }

  async function showReplacements(): Promise<void> {
    if (!state) return
    try {
      const items = await application.workouts.replacementCandidates(state)
      setReplacements(items)
      setMessage(items.length ? '请选择仅用于本次训练的替代动作。' : '当前没有符合器械和安全条件的替代动作。')
    } catch {
      setMessage('替代动作暂时无法加载，请稍后重试。')
    }
  }

  async function replace(candidate: ExerciseReplacementCandidate): Promise<void> {
    if (!state) return
    try {
      const updated = await application.workouts.replaceCurrentExercise(state, candidate)
      setState(updated); setReplacements([])
      setMessage('已仅替换本次训练动作；原计划版本没有改变。')
    } catch {
      setMessage('替换未生效；本地事实和原计划均未被静默覆盖。')
    }
  }

  const exercise = state?.exercises[state.currentExerciseIndex]
  return <View className='screen'>
    <View className='card'>
      <Text className='title'>{exercise?.name ?? '训练恢复'}</Text>
      <Text className='subtitle'>{exercise ? `第 ${state!.currentSetIndex + 1} / ${exercise.targetWorkSets} 组 · 目标 ${exercise.targetReps} 次` : message}</Text>
      {state?.safetyNotice && <View className='error-box'>{state.safetyNotice}</View>}
      <View className='info-box'>{message}</View>
    </View>
    {exercise && <View className='card workout-entry'>
      <View className='field-group'><Text className='field-label'>实际重量（KG）</Text><Text className='field-helper'>填写本组真实使用的重量</Text>
      <Input className='metric-input' type='digit' value={weight} placeholder='例如 12.5' onInput={(event) => setWeight(event.detail.value)} /></View>
      <View className='field-group'><Text className='field-label'>实际次数</Text>
      <Input className='metric-input' type='number' value={reps || String(exercise.targetReps)} onInput={(event) => setReps(event.detail.value)} /></View>
      <Text className='subtitle'>“还能做几次”可暂不填写，系统会保持 UNKNOWN，不会替你猜测。</Text>
      <Button className='secondary-action' onClick={() => void showReplacements()}>需要替换动作</Button>
      {replacements.map((candidate) => <Button key={candidate.id} className='secondary-action' onClick={() => void replace(candidate)}>{candidate.name}</Button>)}
      <View className='action-row action-row--sticky workout-actions'>
        <Button className='primary-action' onClick={() => void record('COMPLETED')}>完成本组</Button>
        <Button className='secondary-action' onClick={() => void record('FAILED')}>未完成</Button>
        <Button className='secondary-action' onClick={() => void record('SKIPPED')}>跳过</Button>
        <Button className='danger-action' onClick={() => void record('FAILED', 'PAIN')}>疼痛或明显不适</Button>
      </View>
    </View>}
    {state?.restTimer?.timerStatus === 'RUNNING' && <View className='card'>
      <Text className='section-title'>休息计时</Text><Text className='timer'>{remaining}s</Text>
      <View className='action-row'><Button className='secondary-action' onClick={() => void adjust(-15)}>减少 15 秒</Button><Button className='secondary-action' onClick={() => void adjust(15)}>增加 15 秒</Button></View>
      <Button className='secondary-action' onClick={() => void application.workouts.skipRest(state).then(setState)}>跳过休息</Button>
    </View>}
    <View className='workout-secondary-links'><Button className='secondary-action' onClick={() => void application.navigation.open('WORKOUT_SUMMARY')}>查看当前总结</Button><Button className='secondary-action' onClick={() => void application.navigation.open('SYNC_CONFLICTS')}>处理同步冲突</Button></View>
  </View>
}
