import { Button, Input, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import { completedRampSets, type WorkoutFlowState, type WorkoutRir } from '../../../application/workoutFlow'
import type { ExerciseReplacementCandidate } from '../../../application/ports/WorkoutReplacementPort'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { useWeappDidHide, useWeappDidShow } from '../../../platform/weapp/lifecycle'

import './index.scss'

const application = getWeappApplication()
const rirOptions: ReadonlyArray<{ value: WorkoutRir; label: string }> = [
  { value: '0', label: '已到极限' },
  { value: '1', label: '还能 1 次' },
  { value: '2', label: '还能 2 次' },
  { value: '3_PLUS', label: '还能 3 次以上' },
  { value: 'UNKNOWN', label: '不确定或跳过' },
]

export default function WorkoutSessionPage() {
  const [state, setState] = useState<WorkoutFlowState | null>(null)
  const [weight, setWeight] = useState('')
  const [reps, setReps] = useState('')
  const [rir, setRir] = useState<WorkoutRir>('UNKNOWN')
  const [remaining, setRemaining] = useState(0)
  const [warmupRemaining, setWarmupRemaining] = useState(0)
  const [message, setMessage] = useState('正在恢复训练草稿…')
  const [replacements, setReplacements] = useState<readonly ExerciseReplacementCandidate[]>([])

  useWeappDidShow(() => {
    void application.workouts.load().then(async (loaded) => {
      if (!loaded) { setMessage('没有可恢复的训练，请从计划页开始。'); return }
      const resumed = await application.workouts.resume(loaded)
      setState(resumed.state)
      setRemaining(resumed.remainingSeconds)
      setWarmupRemaining(resumed.warmupRemainingSeconds)
      setMessage(resumed.clockRollbackDetected
        ? '检测到设备时间回拨，计时已按最近可信时间校准。'
        : resumed.syncFailed
          ? '草稿已恢复；当前网络不可用，未同步记录仍安全保留。'
          : resumed.state.syncStatus === 'CONFLICT'
            ? '草稿已恢复；发现同步冲突，请保留两份证据后处理。'
            : '草稿已恢复，待同步记录已自动补传。')
      application.telemetry.track('workout_resumed', { source: 'foreground' })
    }).catch(() => setMessage('训练草稿损坏或读取失败，未伪造任何完成记录。'))
  })

  useWeappDidHide(() => {
    if (state) application.telemetry.track('workout_paused', { reason: 'background' })
  })

  useEffect(() => {
    if (state?.warmup.phase !== 'GENERAL' || warmupRemaining <= 0) return undefined
    const timer = setInterval(() => setWarmupRemaining((value) => Math.max(0, value - 1)), 1000)
    return () => clearInterval(timer)
  }, [state?.warmup.phase, warmupRemaining <= 0])

  useEffect(() => {
    if (state?.restTimer?.timerStatus !== 'RUNNING' || remaining <= 0) return undefined
    const timer = setInterval(() => setRemaining((value) => Math.max(0, value - 1)), 1000)
    return () => clearInterval(timer)
  }, [state?.restTimer?.timerStatus, remaining <= 0])

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
      rir: status === 'COMPLETED' ? rir : 'UNKNOWN',
      discomfort,
    })
    setState(updated)
    setRir('UNKNOWN')
    application.telemetry.track('workout_set_completed', { status: status.toLowerCase() as 'completed' | 'failed' | 'skipped' })
    if (status === 'SKIPPED') application.telemetry.track('exercise_skipped', { reason: 'user' })
    const resumed = await application.workouts.resume(updated)
    setState(resumed.state)
    setRemaining(resumed.remainingSeconds)
    setMessage(status === 'COMPLETED' ? '本组已先保存到本地草稿。' : '本组已按真实状态记录，不计作正常完成。')
    void application.workouts.flush(resumed.state).then((synced) => {
      setState(synced)
      if (synced.syncStatus === 'CONFLICT') {
        setMessage('服务端检测到事实冲突，请在冲突页显式选择。')
        application.telemetry.track('sync_failed', { reason: 'conflict' })
      }
      if (synced.syncStatus === 'SYNC_REJECTED') {
        setMessage('服务端拒绝了该操作，本地事实仍保留，请检查输入。')
        application.telemetry.track('sync_failed', { reason: 'rejected' })
      }
    }).catch(() => {
      setMessage('网络不可用，本组已保存在本地，稍后自动补传。')
      application.telemetry.track('sync_failed', { reason: 'network' })
    })
  }

  async function recordRampSet(): Promise<void> {
    if (!state) return
    const exerciseIndex = state.warmup.rampExerciseIndex
    const exercise = state.exercises[exerciseIndex]
    const count = completedRampSets(state)
    if (count >= state.warmup.maximumRampSets) { setMessage('递增热身组已达到规则上限，请进入正式组。'); return }
    const parsedWeight = Number(weight)
    const parsedReps = Number(reps)
    if (weight.trim().length === 0 || !Number.isFinite(parsedWeight) || parsedWeight < 0) {
      setMessage('请填写本组实际使用的非负 KG 重量。'); return
    }
    if (!Number.isSafeInteger(parsedReps) || parsedReps <= 0) {
      setMessage('请填写本组实际完成的正整数次数。'); return
    }
    const updated = await application.workouts.recordSet(state, {
      clientSetKey: `${state.clientSessionKey}-warmup-${count + 1}`,
      exerciseIndex,
      setType: 'WARMUP',
      status: 'COMPLETED',
      actualWeightKg: parsedWeight,
      actualReps: parsedReps,
    })
    setState(updated); setWeight(''); setReps('')
    setMessage('递增热身组已保存；它不会计入训练容量或重量进阶。')
    void application.workouts.flush(updated).then(setState).catch(() => setMessage('网络不可用，热身组已保存在本地，稍后自动补传。'))
  }

  async function finishGeneralWarmup(): Promise<void> {
    if (!state) return
    const updated = await application.workouts.completeGeneralWarmup(state)
    setState(updated); setMessage('通用热身完成。请为第一个动作逐级增加重量。')
  }

  async function enterWorkSets(): Promise<void> {
    if (!state) return
    const updated = await application.workouts.beginWorkSets(state)
    setState(updated); setWeight(''); setReps('')
    setMessage('递增热身完成，开始记录正式组。')
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
      application.telemetry.track('exercise_replaced', { source: 'user' })
      setMessage('已仅替换本次训练动作；原计划版本没有改变。')
    } catch {
      setMessage('替换未生效；本地事实和原计划均未被静默覆盖。')
    }
  }

  const exercise = state?.exercises[state.currentExerciseIndex]
  const rampExercise = state?.exercises[state.warmup.rampExerciseIndex]
  if (state?.warmup.phase === 'GENERAL') return <View className='screen'>
    <View className='card warmup-card'>
      <Text className='eyebrow'>通用热身</Text>
      <Text className='title'>让身体逐渐热起来</Text>
      <Text className='subtitle'>进行轻松活动，并练习今天首个动作模式：{rampExercise?.name}。保持能正常说话的强度。</Text>
      <Text className='timer'>{Math.floor(warmupRemaining / 60)}:{String(warmupRemaining % 60).padStart(2, '0')}</Text>
      <View className='info-box'>计时按时间戳恢复，切到后台后仍会准确续算。</View>
      <Button className='primary-action' onClick={() => void finishGeneralWarmup()}>{warmupRemaining === 0 ? '热身完成，继续' : '提前完成'}</Button>
    </View>
  </View>
  if (state?.warmup.phase === 'RAMP' && rampExercise) {
    const count = completedRampSets(state)
    return <View className='screen'>
      <View className='card'>
        <Text className='eyebrow'>递增热身 · {count} / {state.warmup.maximumRampSets} 组</Text>
        <Text className='title'>{rampExercise.name}</Text>
        <Text className='subtitle'>{rampExercise.weightStatus === 'KNOWN' && rampExercise.targetWeightKg !== undefined
          ? `正式组目标约 ${rampExercise.targetWeightKg} KG，请从明显更轻的重量逐级接近。`
          : '当前没有可靠工作重量：从轻重量开始逐级尝试，找到能完成目标次数且还能做 2～3 次的重量。'}</Text>
        <View className='warning-box'>疼痛不是正常训练信号。出现疼痛或明显不适请停止。</View>
      </View>
      <View className='card workout-entry'>
        <View className='field-group'><Text className='field-label'>本组实际重量（KG）</Text><Input className='metric-input' type='digit' value={weight} placeholder='例如 10' onInput={(event) => setWeight(event.detail.value)} /></View>
        <View className='field-group'><Text className='field-label'>本组实际次数</Text><Input className='metric-input' type='number' value={reps} placeholder='例如 8' onInput={(event) => setReps(event.detail.value)} /></View>
        <View className='info-box'>{message}</View>
        <Button className='primary-action' disabled={count >= state.warmup.maximumRampSets} onClick={() => void recordRampSet()}>记录本组并继续加重</Button>
        <Button className='secondary-action' onClick={() => void enterWorkSets()}>{count === 0 ? '无需递增组，进入正式组' : '递增热身完成'}</Button>
      </View>
    </View>
  }
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
      <View className='field-group'>
        <Text className='field-label'>本组还能再做几次（可选）</Text>
        <Text className='field-helper'>这叫 RIR（剩余次数）。不确定就跳过，系统不会替你猜。</Text>
        <View className='rir-options'>
          {rirOptions.map((option) => (
            <Button
              key={option.value}
              size='mini'
              className={rir === option.value ? 'rir-option rir-option--selected' : 'rir-option'}
              onClick={() => setRir(option.value)}
            >{option.label}</Button>
          ))}
        </View>
      </View>
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
