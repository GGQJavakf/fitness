import { Button, Input, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import {
  completedRampSets,
  isWorkoutPrescriptionFinished,
  type WorkoutFlowState,
  type WorkoutRir,
} from '../../../application/workoutFlow'
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
  const [recording, setRecording] = useState(false)
  const recordingRef = useRef(false)

  useWeappDidShow(() => {
    void application.workouts.load().then(async (loaded) => {
      if (!loaded) { setMessage('没有可恢复的训练，请从计划页开始。'); return }
      const resumed = await application.workouts.resume(loaded)
      setState(resumed.state)
      setRemaining(resumed.remainingSeconds)
      setWarmupRemaining(resumed.warmupRemainingSeconds)
      if (isWorkoutPrescriptionFinished(resumed.state)) {
        setMessage('本次训练已完成，正在整理训练总结。')
        await application.navigation.replace('WORKOUT_SUMMARY')
        return
      }
      setMessage(resumed.clockRollbackDetected
        ? '检测到设备时间回拨，计时已按最近可信时间校准。'
        : resumed.syncFailed
          ? '草稿已恢复；当前网络不可用，未同步记录仍安全保留。'
          : resumed.state.syncStatus === 'CONFLICT'
            ? '发现两份不同的训练记录，请稍后确认保留方式。'
            : '训练已恢复，可以从上次位置继续。')
      application.telemetry.track('workout_resumed', { source: 'foreground' })
    }).catch(() => setMessage('训练记录读取失败，请返回计划页重新开始。'))
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

  useEffect(() => {
    setWeight('')
    setReps('')
    setRir('UNKNOWN')
  }, [state?.currentExerciseIndex])

  async function recoverAfterRecordFailure(failedState: WorkoutFlowState): Promise<boolean> {
    const recovered = await application.workouts.load().catch(() => null)
    if (!recovered || recovered.clientSessionKey !== failedState.clientSessionKey) return false
    const resumed = await application.workouts.resume(recovered).catch(() => null)
    const restored = resumed?.state ?? recovered
    setState(restored)
    if (resumed) {
      setRemaining(resumed.remainingSeconds)
      setWarmupRemaining(resumed.warmupRemainingSeconds)
    }
    if (!isWorkoutPrescriptionFinished(restored)) return false
    setMessage('本次训练已完成，正在整理训练总结。')
    await application.navigation.replace('WORKOUT_SUMMARY')
    return true
  }

  async function record(status: 'COMPLETED' | 'FAILED' | 'SKIPPED', discomfort: 'NONE' | 'PAIN' = 'NONE'): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    try {
      const exerciseIndex = state.currentExerciseIndex
      const exercise = state.exercises[exerciseIndex]
      const isBodyweight = exercise.weightStatus === 'BODYWEIGHT'
      if (status === 'COMPLETED' && !isBodyweight && weight.trim().length === 0) {
        setMessage('请填写实际重量；系统不会把空值静默当成 0 KG。')
        return
      }
      const parsedWeight = isBodyweight ? 0 : weight.trim().length > 0 ? Number(weight) : undefined
      const parsedReps = Number(reps || exercise.targetReps)
      if (parsedWeight !== undefined && (!Number.isFinite(parsedWeight) || parsedWeight < 0)) {
        setMessage('实际重量必须是有效的非负 KG 数值。')
        return
      }
      if (!Number.isSafeInteger(parsedReps) || parsedReps < 0) {
        setMessage('实际次数必须是有效的非负整数。')
        return
      }
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
      const prescriptionFinished = isWorkoutPrescriptionFinished(resumed.state)
      if (resumed.state.syncStatus === 'CONFLICT') {
        setMessage('发现两份不同的训练记录，请在同步页确认后继续。')
        application.telemetry.track('sync_failed', { reason: 'conflict' })
      } else if (resumed.state.syncStatus === 'SYNC_REJECTED') {
        setMessage('这次记录未能同步，请检查输入后重试；已填写内容仍保留。')
        application.telemetry.track('sync_failed', { reason: 'rejected' })
      } else if (resumed.syncFailed) {
        setMessage('网络不可用，本组已保存在本地，稍后自动补传。')
        application.telemetry.track('sync_failed', { reason: 'network' })
      } else {
        setMessage(prescriptionFinished
          ? '本次训练已完成，正在整理训练总结。'
          : status === 'COMPLETED' ? '本组已记录。' : '已按你的实际完成情况记录。')
      }
      if (prescriptionFinished) await application.navigation.replace('WORKOUT_SUMMARY')
    } catch (error) {
      const finished = await recoverAfterRecordFailure(state)
      if (!finished) {
        setMessage(error instanceof Error && error.message === 'clientSetKey already identifies different workout facts'
          ? '训练进度已从本地记录刷新，请核对当前组后重新提交。'
          : '本组记录失败，请稍后重试；已填写内容仍保留。')
      }
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function recordRampSet(): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    let locallySaved = false
    try {
      const exerciseIndex = state.warmup.rampExerciseIndex
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
      locallySaved = true
      const synced = await application.workouts.flush(updated)
      setState(synced); setWeight(''); setReps('')
      setMessage('递增热身组已保存；它不会计入训练容量或重量进阶。')
    } catch (error) {
      const finished = await recoverAfterRecordFailure(state)
      if (!finished) {
        setMessage(error instanceof Error && error.message === 'clientSetKey already identifies different workout facts'
          ? '训练进度已从本地记录刷新，请核对当前热身组后重新提交。'
          : locallySaved
            ? '网络不可用，热身组已保存在本地，稍后自动补传。'
            : '热身组记录失败，请稍后重试；已填写内容仍保留。')
      }
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function finishGeneralWarmup(): Promise<void> {
    if (!state) return
    const updated = await application.workouts.completeGeneralWarmup(state)
    setState(updated)
    setMessage(updated.warmup.phase === 'WORK'
      ? '通用热身完成，自重动作无需额外加重，可以开始正式组。'
      : '通用热身完成。请为第一个动作逐级增加重量。')
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
      setMessage('已替换本次训练动作，长期计划保持不变。')
    } catch {
      setMessage('暂时无法替换动作，请稍后重试。')
    }
  }

  const exercise = state?.exercises[state.currentExerciseIndex]
  const rampExercise = state?.exercises[state.warmup.rampExerciseIndex]
  const isBodyweight = exercise?.weightStatus === 'BODYWEIGHT'
  if (state?.warmup.phase === 'GENERAL') return (
    <View className='screen workout-session-page workout-session-page--focus'>
      <View className='page-hero session-hero session-hero--warmup'>
        <Text className='page-hero__eyebrow'>WARM UP · 01</Text>
        <Text className='page-hero__title'>让身体逐渐进入状态</Text>
        <Text className='page-hero__description'>轻松活动，并熟悉今天的第一个动作：{rampExercise?.name}。</Text>
        <View className='session-timer'>
          <Text className='session-timer__value data-number'>
            {Math.floor(warmupRemaining / 60)}:{String(warmupRemaining % 60).padStart(2, '0')}
          </Text>
          <Text className='session-timer__label'>保持能正常说话的强度</Text>
        </View>
      </View>
      <View className='surface-card session-guide'>
        <View className='session-guide__step'>
          <Text className='session-guide__index data-number'>01</Text>
          <Text>进行轻松的全身活动</Text>
        </View>
        <View className='session-guide__step'>
          <Text className='session-guide__index data-number'>02</Text>
          <Text>练习首个动作的运动轨迹</Text>
        </View>
        <View className='session-guide__step'>
          <Text className='session-guide__index data-number'>03</Text>
          <Text>无疼痛、呼吸平稳后继续</Text>
        </View>
      </View>
      <Text className='session-passive-note'>切到后台也会继续计时</Text>
      <View className='action-row action-row--sticky session-focus-action'>
        <Button className='primary-action' onClick={() => void finishGeneralWarmup()}>
          {warmupRemaining === 0 ? '热身完成，继续' : '我已准备好'}
        </Button>
      </View>
    </View>
  )

  if (state?.warmup.phase === 'RAMP' && rampExercise) {
    const count = completedRampSets(state)
    return (
      <View className='screen workout-session-page'>
        <View className='page-hero session-hero'>
          <Text className='page-hero__eyebrow'>WARM UP · 02</Text>
          <Text className='page-hero__title'>{rampExercise.name}</Text>
          <Text className='page-hero__description'>
            {rampExercise.weightStatus === 'KNOWN' && rampExercise.targetWeightKg !== undefined
              ? `正式组约 ${rampExercise.targetWeightKg} KG，先从明显更轻的重量开始。`
              : '从轻重量开始，逐步找到能稳定完成目标次数的重量。'}
          </Text>
          <View className='session-progress'>
            <View
              className='session-progress__fill'
              style={{ width: `${Math.min(100, (count / state.warmup.maximumRampSets) * 100)}%` }}
            />
          </View>
          <Text className='session-progress__label'>{count} / {state.warmup.maximumRampSets} 个递增组</Text>
        </View>
        <View className='surface-card workout-entry'>
          <View className='session-metrics'>
            <View className='field-group'>
              <Text className='field-label'>本组重量</Text>
              <View className='metric-input-wrap'>
                <Input className='metric-input' type='digit' value={weight} placeholder='10' onInput={(event) => setWeight(event.detail.value)} />
                <Text className='metric-input-unit'>KG</Text>
              </View>
            </View>
            <View className='field-group'>
              <Text className='field-label'>完成次数</Text>
              <View className='metric-input-wrap'>
                <Input className='metric-input' type='number' value={reps} placeholder='8' onInput={(event) => setReps(event.detail.value)} />
                <Text className='metric-input-unit'>次</Text>
              </View>
            </View>
          </View>
          <View className='session-safety-note'>重量逐级增加；出现疼痛或明显不适请停止。</View>
          <View className='session-inline-message'>{message}</View>
          <Button className='primary-action' disabled={recording || count >= state.warmup.maximumRampSets} onClick={() => void recordRampSet()}>记录并继续加重</Button>
          <Button className='secondary-action' onClick={() => void enterWorkSets()}>{count === 0 ? '直接进入正式组' : '热身重量合适，开始正式组'}</Button>
        </View>
      </View>
    )
  }

  if (state?.restTimer?.timerStatus === 'RUNNING') return (
    <View className='screen workout-session-page workout-session-page--focus'>
      <View className='page-hero session-hero session-hero--rest'>
        <Text className='page-hero__eyebrow'>RECOVERY</Text>
        <Text className='page-hero__title'>让力量恢复</Text>
        <View className='session-timer'>
          <Text className='session-timer__value data-number'>{remaining}</Text>
          <Text className='session-timer__label'>秒后进入下一组</Text>
        </View>
      </View>
      <View className='surface-card rest-controls'>
        <Text className='rest-controls__next'>接下来 · {exercise?.name ?? '继续训练'}</Text>
        <Text className='subtitle'>{exercise ? `第 ${state.currentSetIndex + 1} 组，目标 ${exercise.targetReps} 次` : message}</Text>
        <View className='rest-controls__adjust'>
          <Button className='secondary-action' onClick={() => void adjust(-15)}>− 15 秒</Button>
          <Button className='secondary-action' onClick={() => void adjust(15)}>＋ 15 秒</Button>
        </View>
        <Button className='primary-action' onClick={() => void application.workouts.skipRest(state).then(setState)}>结束休息，继续训练</Button>
      </View>
    </View>
  )

  const setProgress = exercise && state
    ? Math.min(100, ((state.currentSetIndex + 1) / exercise.targetWorkSets) * 100)
    : 0

  return (
    <View className='screen workout-session-page'>
      <View className='page-hero session-hero'>
        <Text className='page-hero__eyebrow'>
          {exercise && state ? `SET ${state.currentSetIndex + 1} OF ${exercise.targetWorkSets}` : 'TRAINING'}
        </Text>
        <Text className='page-hero__title'>{exercise?.name ?? '恢复训练'}</Text>
        <Text className='page-hero__description'>
          {exercise ? `本组目标 ${exercise.targetReps} 次，按真实完成情况记录。` : message}
        </Text>
        {exercise && (
          <>
            <View className='session-progress'>
              <View className='session-progress__fill' style={{ width: `${setProgress}%` }} />
            </View>
            <Text className='session-progress__label'>当前动作进度</Text>
          </>
        )}
      </View>

      {state?.safetyNotice && <View className='error-box session-alert'>{state.safetyNotice}</View>}
      <View className='session-inline-message'>{message}</View>

      {exercise && (
        <View className='surface-card workout-entry'>
          {isBodyweight && (
            <View className='session-bodyweight-note'>
              自重动作无需填写重量，只记录这一组完成的次数。
            </View>
          )}
          <View className={isBodyweight ? 'session-metrics session-metrics--bodyweight' : 'session-metrics'}>
            {!isBodyweight && (
              <View className='field-group'>
                <Text className='field-label'>实际重量</Text>
                <Text className='field-helper'>填写这一组真实使用的重量</Text>
                <View className='metric-input-wrap'>
                  <Input className='metric-input' type='digit' value={weight} placeholder='12.5' onInput={(event) => setWeight(event.detail.value)} />
                  <Text className='metric-input-unit'>KG</Text>
                </View>
              </View>
            )}
            <View className='field-group'>
              <Text className='field-label'>实际次数</Text>
              <Text className='field-helper'>默认使用本组目标次数</Text>
              <View className='metric-input-wrap'>
                <Input className='metric-input' type='number' value={reps || String(exercise.targetReps)} onInput={(event) => setReps(event.detail.value)} />
                <Text className='metric-input-unit'>次</Text>
              </View>
            </View>
          </View>

          <View className='session-effort'>
            <Text className='field-label'>训练余力（可选）</Text>
            <Text className='field-helper'>选择最接近本组结束时的感受；不确定可以跳过。</Text>
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

          <Button className='session-replace-action' onClick={() => void showReplacements()}>这个动作今天不合适？更换动作</Button>
          {replacements.length > 0 && (
            <View className='session-replacements'>
              {replacements.map((candidate) => (
                <Button key={candidate.id} className='secondary-action' onClick={() => void replace(candidate)}>{candidate.name}</Button>
              ))}
            </View>
          )}

          <View className='action-row workout-actions'>
            <Button className='primary-action' disabled={recording} onClick={() => void record('COMPLETED')}>完成本组</Button>
            <View className='workout-actions__secondary'>
              <Button className='secondary-action' disabled={recording} onClick={() => void record('FAILED')}>未完成</Button>
              <Button className='secondary-action' disabled={recording} onClick={() => void record('SKIPPED')}>跳过</Button>
            </View>
            <Button className='workout-actions__pain' disabled={recording} onClick={() => void record('FAILED', 'PAIN')}>疼痛或明显不适</Button>
          </View>
        </View>
      )}

      <View className='workout-secondary-links'>
        <Button onClick={() => void application.navigation.open('WORKOUT_SUMMARY')}>结束或查看本次训练</Button>
        <Button onClick={() => void application.navigation.open('SYNC_CONFLICTS')}>记录同步帮助</Button>
      </View>
    </View>
  )
}
