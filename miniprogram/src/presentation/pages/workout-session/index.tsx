import { Button, Input, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import {
  buildRemainingRampWarmupSets,
  completedRampSets,
  isWorkoutPrescriptionFinished,
  type WorkoutFlowState,
  type WorkoutRir,
} from '../../../application/workoutFlow'
import type { ExerciseReplacementCandidate } from '../../../application/ports/WorkoutReplacementPort'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { useWeappDidHide, useWeappDidShow } from '../../../platform/weapp/lifecycle'
import ExerciseMotionGuide from '../../../subpackages/exercise-guide/components/exercise-motion-guide'
import {
  resolveExerciseGuidance,
  toExerciseGuidance,
  type ExerciseGuidance,
} from '../../../subpackages/exercise-guide/exercise-guidance'
import { toWeightInputValue } from '../../workoutWeightInput'

import './index.scss'

const application = getWeappApplication()
const rirOptions: ReadonlyArray<{ value: WorkoutRir; label: string }> = [
  { value: '0', label: '已到极限' },
  { value: '1', label: '还能 1 次' },
  { value: '2', label: '还能 2 次' },
  { value: '3_PLUS', label: '还能 3 次以上' },
  { value: 'UNKNOWN', label: '不确定或跳过' },
]
type WorkoutInputError = {
  readonly field: 'weight' | 'reps' | 'action'
  readonly message: string
}

export default function WorkoutSessionPage() {
  const [state, setState] = useState<WorkoutFlowState | null>(null)
  const [weight, setWeight] = useState('')
  const [weightHint, setWeightHint] = useState('')
  const [reps, setReps] = useState('')
  const [rir, setRir] = useState<WorkoutRir>('UNKNOWN')
  const [remaining, setRemaining] = useState(0)
  const [warmupRemaining, setWarmupRemaining] = useState(0)
  const [message, setMessage] = useState('正在恢复训练草稿…')
  const [replacements, setReplacements] = useState<readonly ExerciseReplacementCandidate[]>([])
  const [recording, setRecording] = useState(false)
  const [showEffort, setShowEffort] = useState(false)
  const [showWeightEditor, setShowWeightEditor] = useState(false)
  const [inputError, setInputError] = useState<WorkoutInputError | null>(null)
  const [exerciseContent, setExerciseContent] = useState<ExerciseGuidance | null>(null)
  const [exerciseContentStatus, setExerciseContentStatus] = useState<'IDLE' | 'LOADING' | 'LOCAL' | 'FAILED'>('IDLE')
  const recordingRef = useRef(false)
  const syncAttemptRef = useRef(0)
  const weightDirtyRef = useRef(false)
  const weightSuggestionRequestRef = useRef(0)

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
    if (!state || state.restTimer?.timerStatus !== 'RUNNING' || remaining !== 0) return undefined
    let active = true
    void application.workouts.finishRest(state)
      .then((updated) => {
        if (!active) return
        if (updated.restTimer?.timerStatus === 'RUNNING') {
          setRemaining(1)
          return
        }
        setState(updated)
        setMessage('休息结束，可以开始下一组。')
      })
      .catch(() => {
        if (active) setMessage('休息已结束；计时状态保存失败，请点击结束休息继续。')
      })
    return () => { active = false }
  }, [remaining, state?.restTimer?.sourceSetKey, state?.restTimer?.timerStatus])

  useEffect(() => {
    const current = state?.exercises[state.currentExerciseIndex]
    setReps(current ? String(current.targetReps) : '')
    setRir('UNKNOWN')
    setShowEffort(false)
    setInputError(null)
  }, [
    state?.clientSessionKey,
    state?.currentExerciseIndex,
    state?.currentSetIndex,
    state?.exercises[state?.currentExerciseIndex ?? 0]?.exerciseCode,
    state?.exercises[state?.currentExerciseIndex ?? 0]?.targetReps,
    state?.warmup.phase,
  ])

  useEffect(() => {
    let active = true
    const requestId = ++weightSuggestionRequestRef.current
    const current = state?.exercises[state.currentExerciseIndex]
    weightDirtyRef.current = false
    setShowWeightEditor(false)
    if (!current || current.weightStatus === 'BODYWEIGHT') {
      setWeight('')
      setWeightHint('')
      return () => { active = false }
    }
    const confirmedWeight = toWeightInputValue(current.sessionWeightKg)
    if (confirmedWeight !== null) {
      setWeight(confirmedWeight)
      setWeightHint(current.targetWeightKg === current.sessionWeightKg
        ? '已沿用计划重量，本次后续正式组自动复用'
        : '本次训练已确认，后续正式组自动复用')
      return () => { active = false }
    }
    setWeight('')
    setWeightHint('')
    const plannedWeight = toWeightInputValue(current.targetWeightKg)
    if (plannedWeight !== null) {
      setWeight(plannedWeight)
      setWeightHint('已带入计划目标重量，确认一次即可')
      return () => { active = false }
    }
    void application.getExerciseTrend(current.exerciseCode)
      .then((trend) => trend.points
        .filter((point) => Number.isFinite(point.topWeightKg) && point.topWeightKg > 0)
       .sort((left, right) => Date.parse(right.completedAt) - Date.parse(left.completedAt))[0])
      .then((latest) => {
        if (!active
          || requestId !== weightSuggestionRequestRef.current
          || weightDirtyRef.current
          || !latest) return
        setWeight(String(latest.topWeightKg))
        setWeightHint('已带入最近有效重量，确认一次即可')
      })
      .catch(() => { /* No history is a valid calibration state. */ })
    return () => { active = false }
  }, [
    state?.clientSessionKey,
    state?.currentExerciseIndex,
    state?.exercises[state?.currentExerciseIndex ?? 0]?.exerciseCode,
    state?.exercises[state?.currentExerciseIndex ?? 0]?.sessionWeightKg,
    state?.warmup.phase,
  ])

  useEffect(() => {
    let active = true
    const currentExerciseCode = state?.exercises[state.currentExerciseIndex]?.exerciseCode
    if (!currentExerciseCode) {
      setExerciseContent(null)
      setExerciseContentStatus('IDLE')
      return () => { active = false }
    }
    const localGuidance = resolveExerciseGuidance(currentExerciseCode)
    setExerciseContent(localGuidance ?? null)
    setExerciseContentStatus('LOADING')
    void application.getExercise(currentExerciseCode)
      .then((content) => {
        if (!active) return
        setExerciseContent(toExerciseGuidance(content))
        setExerciseContentStatus('IDLE')
      })
      .catch(() => {
        if (!active) return
        setExerciseContentStatus(localGuidance ? 'LOCAL' : 'FAILED')
      })
    return () => { active = false }
  }, [
    state?.clientSessionKey,
    state?.currentExerciseIndex,
    state?.exercises[state?.currentExerciseIndex ?? 0]?.exerciseCode,
  ])

  function updateWeight(value: string): void {
    weightDirtyRef.current = true
    setWeight(value)
    setWeightHint('')
    setInputError((current) => current?.field === 'weight' ? null : current)
  }

  function updateReps(value: string): void {
    setReps(value)
    setInputError((current) => current?.field === 'reps' ? null : current)
  }

  async function confirmFormalWeight(exerciseIndex: number): Promise<void> {
    if (!state || recordingRef.current) return
    const parsedWeight = Number(weight)
    if (weight.trim().length === 0 || !Number.isFinite(parsedWeight) || parsedWeight <= 0) {
      setInputError({ field: 'weight', message: '正式组重量必须是大于 0 的有效 KG 数值。' })
      return
    }
    recordingRef.current = true
    setRecording(true)
    setInputError(null)
    try {
      const updated = await application.workouts.setExerciseWeight(state, exerciseIndex, parsedWeight)
      setState(updated)
      setWeight(String(parsedWeight))
      setWeightHint('本次训练已确认，后续正式组自动复用')
      setShowWeightEditor(false)
      setMessage(state.currentSetIndex > 0
        ? '已调整本次训练后续正式组重量；之前完成的组保持原记录。'
        : '正式组重量已确认，后续各组无需重复输入。')
    } catch {
      setInputError({ field: 'action', message: '正式组重量保存失败，请稍后重试。' })
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function syncRecordedSet(updated: WorkoutFlowState): Promise<void> {
    const attempt = ++syncAttemptRef.current
    try {
      const synchronized = await application.workouts.flush(updated)
      if (attempt !== syncAttemptRef.current) return
      if (synchronized.syncStatus === 'CONFLICT') {
        setMessage('本组已保存在本地；发现两份不同记录，请在同步页确认。')
        application.telemetry.track('sync_failed', { reason: 'conflict' })
      } else if (synchronized.syncStatus === 'SYNC_REJECTED') {
        setMessage('本组已保存在本地，但服务器未接受该记录；请检查输入后重试同步。')
        application.telemetry.track('sync_failed', { reason: 'rejected' })
      }
    } catch {
      if (attempt !== syncAttemptRef.current) return
      setMessage('网络不可用，本组已保存在本地，稍后自动补传。')
      application.telemetry.track('sync_failed', { reason: 'network' })
    }
  }

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
    setInputError(null)
    try {
      const exerciseIndex = state.currentExerciseIndex
      const exercise = state.exercises[exerciseIndex]
      const isBodyweight = exercise.weightStatus === 'BODYWEIGHT'
      if (status !== 'SKIPPED' && !isBodyweight && exercise.sessionWeightKg === undefined) {
        setInputError({ field: 'weight', message: '请先确认本次正式组重量。' })
        return
      }
      const parsedWeight = isBodyweight ? 0 : exercise.sessionWeightKg
      const parsedReps = reps.trim().length === 0 ? Number.NaN : Number(reps)
      if (status !== 'SKIPPED' && (
        !Number.isSafeInteger(parsedReps)
        || parsedReps < 0
        || (status === 'COMPLETED' && parsedReps === 0)
      )) {
        setInputError({
          field: 'reps',
          message: status === 'COMPLETED'
            ? '完成本组时，实际次数必须是正整数。'
            : '实际次数必须是有效的非负整数。',
        })
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
      setRemaining(updated.restTimer?.configuredDurationSeconds ?? 0)
      setRir('UNKNOWN')
      setShowEffort(false)
      application.telemetry.track('workout_set_completed', { status: status.toLowerCase() as 'completed' | 'failed' | 'skipped' })
      if (status === 'SKIPPED') application.telemetry.track('exercise_skipped', { reason: 'user' })
      const prescriptionFinished = isWorkoutPrescriptionFinished(updated)
      setMessage(prescriptionFinished
        ? '本次训练已保存，正在整理训练总结。'
        : status === 'COMPLETED'
          ? '本组已保存在本地，休息计时已开始。'
          : '本组实际完成情况已保存在本地。')
      void syncRecordedSet(updated)
      if (prescriptionFinished) await application.navigation.replace('WORKOUT_SUMMARY')
    } catch (error) {
      const finished = await recoverAfterRecordFailure(state)
      if (!finished) {
        setInputError({
          field: 'action',
          message: error instanceof Error && error.message === 'clientSetKey already identifies different workout facts'
            ? '训练进度已从本地记录刷新，请核对当前组后重新提交。'
            : '本组记录失败，请稍后重试；已填写内容仍保留。',
        })
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
    setInputError(null)
    try {
      const exerciseIndex = state.warmup.rampExerciseIndex
      const count = completedRampSets(state)
      const exercise = state.exercises[exerciseIndex]
      const completedWeightKg = exercise.sets
        .filter((set) => set.setType === 'WARMUP' && set.status === 'COMPLETED' && set.actualWeightKg !== null)
        .map((set) => set.actualWeightKg as number)
      const remainingRampSets = exercise.sessionWeightKg === undefined
        ? []
        : buildRemainingRampWarmupSets(exercise.sessionWeightKg, completedWeightKg)
      const nextRampSet = remainingRampSets[0]
      if (!nextRampSet) {
        const work = await application.workouts.beginWorkSets(state)
        setState(work)
        setMessage('已进入正式组。')
        return
      }
      const updated = await application.workouts.recordSet(state, {
        clientSetKey: `${state.clientSessionKey}-warmup-${count + 1}`,
        exerciseIndex,
        setType: 'WARMUP',
        status: 'COMPLETED',
        actualWeightKg: nextRampSet.weightKg,
        actualReps: nextRampSet.reps,
      })
      const finalWarmupSet = remainingRampSets.length === 1
      const nextState = finalWarmupSet
        ? await application.workouts.beginWorkSets(updated)
        : updated
      setState(nextState)
      setMessage(finalWarmupSet
        ? '热身完成，正式组重量已准备好。'
        : '热身完成，下一组重量已自动调整。')
      void syncRecordedSet(nextState)
    } catch (error) {
      const finished = await recoverAfterRecordFailure(state)
      if (!finished) {
        setInputError({
          field: 'action',
          message: error instanceof Error && error.message === 'clientSetKey already identifies different workout facts'
            ? '训练进度已从本地记录刷新，请核对当前热身组后重新提交。'
            : '热身组记录失败，请稍后重试；已填写内容仍保留。',
        })
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
      : '通用热身完成。确认正式组重量后，系统会安排轻重量热身。')
  }

  async function enterWorkSets(): Promise<void> {
    if (!state) return
    const updated = await application.workouts.beginWorkSets(state)
    setState(updated)
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
    const formalWeight = rampExercise.sessionWeightKg
    const completedWeightKg = rampExercise.sets
      .filter((set) => set.setType === 'WARMUP' && set.status === 'COMPLETED' && set.actualWeightKg !== null)
      .map((set) => set.actualWeightKg as number)
    const remainingRampSets = formalWeight === undefined
      ? []
      : buildRemainingRampWarmupSets(formalWeight, completedWeightKg)
    const nextRampSet = remainingRampSets[0]
    const totalRampSets = count + remainingRampSets.length
    const progress = totalRampSets === 0 ? 0 : Math.min(100, (count / totalRampSets) * 100)
    return (
      <View className='screen workout-session-page'>
        <View className='page-hero session-hero'>
          <Text className='page-hero__eyebrow'>WARM UP · 02</Text>
          <Text className='page-hero__title'>{rampExercise.name}</Text>
          <Text className='page-hero__description'>
            {formalWeight === undefined
              ? '先确认今天的正式组重量，系统会自动安排轻重量热身。'
              : `今天正式组 ${formalWeight} KG，热身重量已自动计算。`}
          </Text>
          <View className='session-progress'>
            <View className='session-progress__fill' style={{ width: `${progress}%` }} />
          </View>
          <Text className='session-progress__label'>
            {formalWeight === undefined
              ? '等待确认正式组重量'
              : totalRampSets === 0
                ? '正式重量较轻，无需额外热身'
                : `${count} / ${totalRampSets} 个自动热身组`}
          </Text>
        </View>
        <View className='surface-card workout-entry'>
          {formalWeight === undefined ? (
            <View className='session-weight-setup'>
              <Text className='field-label'>今天正式组重量</Text>
              <Text className='field-helper'>{weightHint || '只需确认一次，正式组会自动复用'}</Text>
              <View className='metric-input-wrap'>
                <Input className='metric-input' type='digit' value={weight} placeholder='例如 10' onInput={(event) => updateWeight(event.detail.value)} />
                <Text className='metric-input-unit'>KG</Text>
              </View>
              {inputError?.field === 'weight' && <Text className='session-field-error'>{inputError.message}</Text>}
              <Button
                className='primary-action'
                loading={recording}
                disabled={recording}
                onClick={() => void confirmFormalWeight(state.warmup.rampExerciseIndex)}
              >
                {recording ? '正在保存' : '确认正式组重量'}
              </Button>
            </View>
          ) : (
            <>
              <View className='session-formal-weight'>
                <View>
                  <Text className='session-formal-weight__label'>本次正式组重量</Text>
                  <Text className='session-formal-weight__value data-number'>{formalWeight} KG</Text>
                </View>
                <Button
                  className='session-formal-weight__edit'
                  onClick={() => {
                    setWeight(String(formalWeight))
                    setShowWeightEditor(true)
                  }}
                >调整</Button>
              </View>
              {showWeightEditor && (
                <View className='session-weight-setup session-weight-setup--inline'>
                  <Text className='field-helper'>调整后会重新计算尚未完成的热身组。</Text>
                  <View className='metric-input-wrap'>
                    <Input className='metric-input' type='digit' value={weight} onInput={(event) => updateWeight(event.detail.value)} />
                    <Text className='metric-input-unit'>KG</Text>
                  </View>
                  {inputError?.field === 'weight' && <Text className='session-field-error'>{inputError.message}</Text>}
                  <Button className='secondary-action' onClick={() => void confirmFormalWeight(state.warmup.rampExerciseIndex)}>
                    保存调整
                  </Button>
                </View>
              )}
              {nextRampSet ? (
                <View className='session-auto-warmup'>
                  <Text className='session-auto-warmup__eyebrow'>接下来 · 第 {count + 1} 组热身</Text>
                  <Text className='session-auto-warmup__value data-number'>{nextRampSet.weightKg} KG</Text>
                  <Text className='session-auto-warmup__reps'>完成 {nextRampSet.reps} 次即可</Text>
                </View>
              ) : (
                <View className='session-auto-warmup session-auto-warmup--ready'>
                  <Text className='session-auto-warmup__eyebrow'>无需额外热身组</Text>
                  <Text className='session-auto-warmup__reps'>当前重量较轻，可以直接开始正式组。</Text>
                </View>
              )}
              <View className='session-safety-note'>热身重量由系统计算；出现疼痛或明显不适请停止。</View>
            </>
          )}
          <View className='session-inline-message'>{message}</View>
          {inputError?.field === 'action' && <View className='session-action-error'>{inputError.message}</View>}
          {formalWeight !== undefined && (
            <>
              <Button
                className='primary-action'
                loading={recording}
                disabled={recording}
                onClick={() => void recordRampSet()}
              >
                {recording ? '正在保存' : nextRampSet ? '完成本热身组' : '开始正式组'}
              </Button>
              {nextRampSet && (
                <Button className='secondary-action' onClick={() => void enterWorkSets()}>
                  跳过剩余热身，开始正式组
                </Button>
              )}
            </>
          )}
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
      <View className='page-hero session-hero session-hero--work'>
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
        <>
          <View className='surface-card workout-entry'>
            {isBodyweight && (
              <View className='session-bodyweight-note'>
                自重动作无需填写重量，只记录本组实际次数。
              </View>
            )}
            {!isBodyweight && (
              exercise.sessionWeightKg === undefined || showWeightEditor
            ) && (
              <View className='session-weight-setup'>
                <View className='session-field-heading'>
                  <Text className='field-label'>
                    {exercise.sessionWeightKg === undefined ? '确认本次正式组重量' : '调整本次正式组重量'}
                  </Text>
                  <Text className='field-helper'>仅本次训练</Text>
                </View>
                <Text className='field-helper'>
                  {exercise.sessionWeightKg === undefined
                    ? weightHint || '只确认一次，后续正式组自动复用'
                    : state.currentSetIndex > 0
                      ? '只影响后续组，已经完成的组保持原记录'
                      : '保存后自动复用，不会修改长期计划'}
                </Text>
                <View className='metric-input-wrap'>
                  <Input className='metric-input' type='digit' value={weight} placeholder='例如 12.5' onInput={(event) => updateWeight(event.detail.value)} />
                  <Text className='metric-input-unit'>KG</Text>
                </View>
                {inputError?.field === 'weight' && <Text className='session-field-error'>{inputError.message}</Text>}
                <View className='session-weight-setup__actions'>
                  <Button
                    className='secondary-action'
                    loading={recording}
                    disabled={recording}
                    onClick={() => void confirmFormalWeight(state.currentExerciseIndex)}
                  >
                    {recording ? '正在保存' : '保存本次重量'}
                  </Button>
                  {exercise.sessionWeightKg !== undefined && (
                    <Button className='session-formal-weight__edit' onClick={() => setShowWeightEditor(false)}>取消</Button>
                  )}
                </View>
              </View>
            )}

            <View
              className={
                isBodyweight || exercise.sessionWeightKg === undefined || showWeightEditor
                  ? 'session-recording-grid session-recording-grid--single'
                  : 'session-recording-grid'
              }
            >
              {!isBodyweight && exercise.sessionWeightKg !== undefined && !showWeightEditor && (
                <View className='session-formal-weight session-formal-weight--compact'>
                  <View className='session-field-heading'>
                    <Text className='session-formal-weight__label'>实际重量</Text>
                    <Button
                      className='session-formal-weight__edit session-formal-weight__edit--compact'
                      onClick={() => {
                        setWeight(String(exercise.sessionWeightKg))
                        setShowWeightEditor(true)
                      }}
                    >仅本次调整</Button>
                  </View>
                  <Text className='session-formal-weight__value data-number'>{exercise.sessionWeightKg} KG</Text>
                  <Text className='session-formal-weight__hint'>本次各组自动复用</Text>
                </View>
              )}
              <View className='field-group session-reps-field'>
                <View className='session-field-heading'>
                  <Text className='field-label'>实际次数</Text>
                  <Text className='field-helper'>目标 {exercise.targetReps} 次</Text>
                </View>
                <View className='metric-input-wrap'>
                  <Input className='metric-input' type='number' value={reps} onInput={(event) => updateReps(event.detail.value)} />
                  <Text className='metric-input-unit'>次</Text>
                </View>
                {inputError?.field === 'reps' && <Text className='session-field-error'>{inputError.message}</Text>}
              </View>
            </View>

            <View className='session-effort'>
              <Button className='session-effort__toggle' onClick={() => setShowEffort((value) => !value)}>
                <Text>训练余力（可选）</Text>
                <Text>{rirOptions.find((option) => option.value === rir)?.label} · {showEffort ? '收起' : '修改'}</Text>
              </Button>
              {showEffort && (
                <>
                  <Text className='field-helper'>选择最接近本组结束时的感受；不确定可以跳过。</Text>
                  <View className='rir-options'>
                    {rirOptions.map((option) => (
                      <Button
                        key={option.value}
                        size='mini'
                        className={rir === option.value ? 'rir-option rir-option--selected' : 'rir-option'}
                        onClick={() => {
                          setRir(option.value)
                          setShowEffort(false)
                        }}
                      >{option.label}</Button>
                    ))}
                  </View>
                </>
              )}
            </View>

            {inputError?.field === 'action' && <View className='session-action-error'>{inputError.message}</View>}
            <View className='action-row action-row--sticky workout-actions'>
              <Button
                className='primary-action'
                loading={recording}
                disabled={recording || (!isBodyweight && exercise.sessionWeightKg === undefined)}
                onClick={() => void record('COMPLETED')}
              >
                {recording ? '正在保存本组' : '完成本组'}
              </Button>
              <Button
                className='secondary-action'
                disabled={recording || (!isBodyweight && exercise.sessionWeightKg === undefined)}
                onClick={() => void record('FAILED')}
              >未完成</Button>
              <Button className='secondary-action' disabled={recording} onClick={() => void record('SKIPPED')}>跳过</Button>
              <Button
                className='workout-actions__pain'
                disabled={recording || (!isBodyweight && exercise.sessionWeightKg === undefined)}
                onClick={() => void record('FAILED', 'PAIN')}
              >疼痛或明显不适</Button>
            </View>

            <Button className='session-replace-action' onClick={() => void showReplacements()}>这个动作今天不合适？更换动作</Button>
            {replacements.length > 0 && (
              <View className='session-replacements'>
                {replacements.map((candidate) => (
                  <Button key={candidate.id} className='secondary-action' onClick={() => void replace(candidate)}>{candidate.name}</Button>
                ))}
              </View>
            )}
          </View>

          <View className='session-motion-section'>
            <ExerciseMotionGuide
              compact
              exerciseCode={exercise.exerciseCode}
              exerciseName={exercise.name}
              primaryRef={exerciseContent?.primaryRef}
              fallbackRef={exerciseContent?.fallbackRef}
            />
            <View className='surface-card session-exercise-guidance'>
              <View className='session-exercise-guidance__section'>
                <View className='session-exercise-guidance__heading'>
                  <Text className='session-exercise-guidance__title'>动作说明</Text>
                  <Text className='session-exercise-guidance__meta'>
                    {exerciseContentStatus === 'LOCAL' ? '本地安全指导' : '训练中可随时对照'}
                  </Text>
                </View>
                <Text className='session-exercise-guidance__description'>
                  {exerciseContent?.plainLanguage
                    ?? (exerciseContentStatus === 'FAILED'
                      ? '动作说明暂时无法读取，请保持稳定姿势并按静态示例控制动作。'
                      : '正在读取动作说明…')}
                </Text>
              </View>

              {exerciseContent && (
                <>
                  <View className='session-exercise-guidance__section'>
                    <View className='session-exercise-guidance__heading'>
                      <Text className='session-exercise-guidance__title'>动作步骤</Text>
                      <Text className='session-exercise-guidance__meta'>{exerciseContent.instructions.length} 个要点</Text>
                    </View>
                    <View className='session-exercise-steps'>
                      {exerciseContent.instructions.map((instruction, index) => (
                        <View className='session-exercise-step' key={`${index}-${instruction}`}>
                          <Text className='session-exercise-step__index data-number'>
                            {String(index + 1).padStart(2, '0')}
                          </Text>
                          <Text className='session-exercise-step__text'>{instruction}</Text>
                        </View>
                      ))}
                    </View>
                  </View>

                  <View className='session-exercise-coaching'>
                    <View className='session-exercise-coaching__block session-exercise-coaching__block--breathing'>
                      <Text className='session-exercise-coaching__title'>呼吸提示</Text>
                      {exerciseContent.breathingCues.map((cue) => (
                        <Text className='session-exercise-coaching__item' key={cue}>· {cue}</Text>
                      ))}
                    </View>
                    <View className='session-exercise-coaching__block session-exercise-coaching__block--mistakes'>
                      <Text className='session-exercise-coaching__title'>常见错误</Text>
                      {exerciseContent.commonMistakes.map((mistake) => (
                        <Text className='session-exercise-coaching__item' key={mistake}>· {mistake}</Text>
                      ))}
                    </View>
                  </View>

                  {exerciseContent.safetyCues.length > 0 && (
                    <View className='session-exercise-safety'>
                      <Text className='session-exercise-safety__title'>安全提醒</Text>
                      {exerciseContent.safetyCues.map((cue) => (
                        <Text className='session-exercise-safety__cue' key={cue}>· {cue}</Text>
                      ))}
                    </View>
                  )}
                </>
              )}
            </View>
          </View>
        </>
      )}

      <View className='workout-secondary-links'>
        <Button onClick={() => void application.navigation.open('WORKOUT_SUMMARY')}>结束或查看本次训练</Button>
        <Button onClick={() => void application.navigation.open('SYNC_CONFLICTS')}>记录同步帮助</Button>
      </View>
    </View>
  )
}
