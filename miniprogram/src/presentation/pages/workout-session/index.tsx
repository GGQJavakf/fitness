import { Button, Input, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import { ApplicationError } from '../../../application/errors'
import {
  areRequiredWorkSetsComplete,
  completedRampSets,
  isOptionalSetInProgress,
  isWorkoutPrescriptionFinished,
  pendingOptionalSetChoice,
  remainingRampWarmupSets,
  type WorkoutFlowState,
  type WorkoutRir,
  type WorkoutSafetyFlag,
} from '../../../application/workoutFlow'
import {
  DEFAULT_FORMAL_WEIGHT_KG,
} from '../../../application/automaticWorkoutWeight'
import type { ExerciseReplacementCandidate } from '../../../application/ports/WorkoutReplacementPort'
import { getWorkoutApplication } from '../../../platform/weapp/featureRoots/workoutCompositionRoot'
import type {
  WorkoutRecordRecoveryResult,
  WorkoutSessionLaunchContext,
  WorkoutSynchronizationResult,
} from '../../../platform/weapp/featureRoots/workoutGenerationOperations'
import { useWeappDidHide, useWeappDidShow } from '../../../platform/weapp/lifecycle'
import {
  resolveExerciseGuidance,
  toExerciseGuidance,
  type ExerciseGuidance,
} from '../../../subpackages/exercise-guide/exercise-guidance'
import WorkoutExerciseMotionGuide from '../../../subpackages/workout/components/workout-exercise-motion-guide'
import { toWeightInputValue } from '../../workoutWeightInput'

const rirOptions: ReadonlyArray<{ value: WorkoutRir; label: string }> = [
  { value: '0', label: '已到极限' },
  { value: '1', label: '还能 1 次' },
  { value: '2', label: '还能 2 次' },
  { value: '3_PLUS', label: '还能 3 次以上' },
  { value: 'UNKNOWN', label: '不确定或跳过' },
]
const safetyOptions: ReadonlyArray<{ value: WorkoutSafetyFlag; label: string }> = [
  { value: 'PAIN', label: '疼痛' },
  { value: 'INJURY', label: '受伤' },
  { value: 'CHEST_DISCOMFORT', label: '胸部不适' },
  { value: 'DIZZINESS', label: '头晕' },
  { value: 'SEVERE_UNWELL', label: '严重不适' },
]
type WorkoutInputError = {
  readonly field: 'weight' | 'reps' | 'action'
  readonly message: string
}

function isUserGenerationInvalidated(error: unknown): boolean {
  return error instanceof ApplicationError && error.name === 'UserGenerationInvalidatedError'
}

function replacementFailureMessage(
  reason: unknown,
  phase: 'LOAD' | 'APPLY',
): string {
  if (reason instanceof ApplicationError) {
    if (reason.code === 'INSUFFICIENT_REPLACEMENTS') {
      return '没有经审核且同时匹配动作模式、主要肌群、难度和器械的替代动作；请保留原动作或结束本次训练。'
    }
    if (reason.code === 'VERSION_CONFLICT') {
      return '训练状态已在其他位置更新，请重新进入训练后再选择替代动作。'
    }
    if (reason.code === 'NETWORK_ERROR') {
      return phase === 'LOAD'
        ? '网络连接失败，暂时无法读取替代动作，请检查网络后重试。'
        : '网络连接失败，替换尚未保存，请检查网络后重试。'
    }
  }
  return phase === 'LOAD'
    ? '替代动作暂时无法加载，请稍后重试。'
    : '暂时无法替换动作，请稍后重试。'
}

function recordedSetMessage(input: {
  safetyFlag?: WorkoutSafetyFlag
  prescriptionFinished: boolean
  requiredWorkComplete: boolean
  status: 'COMPLETED' | 'FAILED' | 'SKIPPED'
  resting: boolean
}): string {
  if (input.safetyFlag) return '安全信号已保存。请立即停止训练并按上方提示处理。'
  if (input.prescriptionFinished) return '本次训练已保存，正在整理训练总结。'
  if (input.requiredWorkComplete) return '固定训练已完成，请确认是否增加一个可选补充组。'
  if (input.status !== 'COMPLETED') return '本组实际完成情况已保存在本地。'
  return input.resting
    ? '本组已保存在本地，休息计时已开始。'
    : '本组已保存在本地，继续完成超级组的下一个动作。'
}

type WorkoutSynchronizationSource =
  | 'START'
  | 'RESUME'
  | 'RECORD_RECOVERY'
  | 'REST_ADJUSTMENT'
  | 'RECORDED_SET'

const synchronizationFeedback: Readonly<Record<WorkoutSynchronizationSource, {
  readonly failed: string
  readonly conflict: string
  readonly rejected: string
  readonly synchronized?: string
}>> = {
  START: {
    failed: '训练已开始；当前网络不可用，未同步记录仍安全保留。',
    conflict: '训练已开始；发现两份不同记录，请在同步页确认。',
    rejected: '训练已开始，但服务器未接受部分记录；请检查后重试同步。',
    synchronized: '训练已开始，未同步记录已完成后台补传。',
  },
  RESUME: {
    failed: '训练已恢复；当前网络不可用，未同步记录仍安全保留。',
    conflict: '训练已恢复；发现两份不同记录，请在同步页确认。',
    rejected: '训练已恢复，但服务器未接受部分记录；请检查后重试同步。',
    synchronized: '训练已恢复，未同步记录已完成后台补传。',
  },
  RECORD_RECOVERY: {
    failed: '训练草稿已恢复；当前网络不可用，未同步记录仍安全保留。',
    conflict: '训练草稿已恢复；发现两份不同记录，请在同步页确认。',
    rejected: '训练草稿已恢复，但服务器未接受部分记录；请检查后重试同步。',
    synchronized: '训练草稿已恢复，未同步记录已完成后台补传。',
  },
  REST_ADJUSTMENT: {
    failed: '休息时间已调整并保存在本地；未同步记录稍后自动补传。',
    conflict: '休息时间已调整；训练记录存在冲突，请在同步页确认。',
    rejected: '休息时间已调整，但服务器未接受部分记录；请检查后重试同步。',
    synchronized: '休息时间已调整，未同步记录已完成后台补传。',
  },
  RECORDED_SET: {
    failed: '网络不可用，本组已保存在本地，稍后自动补传。',
    conflict: '本组已保存在本地；发现两份不同记录，请在同步页确认。',
    rejected: '本组已保存在本地，但服务器未接受该记录；请检查输入后重试同步。',
  },
}

export function beginWorkoutSessionMount(
  mountedRef: { current: boolean },
  loadSession: () => Promise<void>,
): () => void {
  mountedRef.current = true
  void loadSession()
  return () => {
    mountedRef.current = false
  }
}

function optionalSetCue(conditionCode: string, active: boolean): string {
  if (active) return '这是本次已选择的唯一补充组；完成后直接结束训练。'
  if (conditionCode === 'TUESDAY_UNDER_42_GOOD_STATE') {
    return '若当前用时不超过 42 分钟且状态良好，本动作可作为二选一补充组；不要与另一项同时增加。'
  }
  return '满足当天条件时，本动作可增加 1 个补充组。'
}

function repetitionTargetLabel(targetRepMin: number, targetRepMax: number): string {
  return targetRepMin === targetRepMax
    ? String(targetRepMax)
    : `${targetRepMin}～${targetRepMax}`
}

function loadedSessionMessage(input: {
  readonly state: WorkoutFlowState
  readonly clockRollbackDetected: boolean
  readonly freshLaunch: boolean
}): string {
  if (input.clockRollbackDetected) {
    return '检测到设备时间回拨，计时已按最近可信时间校准。'
  }
  if (input.state.syncStatus === 'CONFLICT') {
    return '发现两份不同的训练记录，请稍后确认保留方式。'
  }
  if (input.state.syncStatus === 'SYNC_REJECTED') {
    return input.freshLaunch
      ? '训练已开始，但有记录未被服务器接受；请检查后再结束训练。'
      : '训练已恢复，但有记录未被服务器接受；请检查后再结束训练。'
  }
  if (input.state.syncStatus === 'OFFLINE_PENDING') {
    return input.freshLaunch
      ? '训练已开始，可以继续；未同步记录正在后台补传。'
      : '训练已恢复，可以继续；未同步记录正在后台补传。'
  }
  if (!input.freshLaunch) return '训练已恢复，可以从上次位置继续。'
  return input.state.warmup.phase === 'WORK'
    ? '训练已开始，可以记录第一组。'
    : '训练已开始，请按计划完成热身。'
}

export default function WorkoutSessionPage() {
  const application = getWorkoutApplication()
  const [initialLaunchContext] = useState<WorkoutSessionLaunchContext>(() => {
    const launchMode = application.routeParameter('workoutLaunchMode')
    const clientSessionKey = application.routeParameter('clientSessionKey')?.trim()
    return launchMode === 'FRESH_START' && clientSessionKey
      ? { launchMode: 'FRESH_START', clientSessionKey }
      : { launchMode: 'RESUME_INTERRUPTED' }
  })
  const [state, setState] = useState<WorkoutFlowState | null>(null)
  const [weight, setWeight] = useState('')
  const [weightHint, setWeightHint] = useState('')
  const [reps, setReps] = useState('')
  const [rir, setRir] = useState<WorkoutRir>('UNKNOWN')
  const [remaining, setRemaining] = useState(0)
  const [warmupRemaining, setWarmupRemaining] = useState(0)
  const [message, setMessage] = useState(
    initialLaunchContext.launchMode === 'FRESH_START'
      ? '正在打开新训练…'
      : '正在恢复训练草稿…',
  )
  const [replacements, setReplacements] = useState<readonly ExerciseReplacementCandidate[]>([])
  const [recording, setRecording] = useState(false)
  const [recoveryRequired, setRecoveryRequired] = useState(false)
  const [showEffort, setShowEffort] = useState(false)
  const [showWeightEditor, setShowWeightEditor] = useState(false)
  const [showSafetyChoices, setShowSafetyChoices] = useState(false)
  const [showMotionGuide, setShowMotionGuide] = useState(false)
  const [inputError, setInputError] = useState<WorkoutInputError | null>(null)
  const [exerciseContent, setExerciseContent] = useState<ExerciseGuidance | null>(null)
  const [exerciseContentStatus, setExerciseContentStatus] = useState<'IDLE' | 'LOADING' | 'LOCAL' | 'FAILED'>('IDLE')
  const [sessionLoadFailed, setSessionLoadFailed] = useState(false)
  const recordingRef = useRef(false)
  const syncAttemptRef = useRef(0)
  const weightDirtyRef = useRef(false)
  const weightSuggestionRequestRef = useRef(0)
  const sessionLoadInFlightRef = useRef(false)
  const sessionLoadRequestRef = useRef(0)
  const launchContextRef = useRef<WorkoutSessionLaunchContext>(initialLaunchContext)
  const pageHiddenRef = useRef(false)
  const mountedRef = useRef(true)

  async function loadSession(): Promise<void> {
    if (sessionLoadInFlightRef.current) return
    sessionLoadInFlightRef.current = true
    const requestId = ++sessionLoadRequestRef.current
    const launchContext = launchContextRef.current
    setSessionLoadFailed(false)
    setMessage(launchContext.launchMode === 'FRESH_START'
      ? '正在打开新训练…'
      : '正在恢复训练草稿…')
    try {
      const loaded = await application.loadWorkoutSession(launchContext)
      if (!mountedRef.current || requestId !== sessionLoadRequestRef.current) return
      if (loaded.kind === 'RECOVERY_REQUIRED') {
        setRecoveryRequired(true)
        setMessage('检测到损坏的训练草稿。登录状态仍然有效，请清理该草稿后返回计划。')
        return
      }
      if (loaded.kind === 'SESSION_MISMATCH') {
        setState(null)
        setRecoveryRequired(false)
        setRemaining(0)
        setWarmupRemaining(0)
        setReplacements([])
        setMessage('新训练启动信息已过期；现有训练草稿未改动，请返回计划后重新开始。')
        return
      }
      if (loaded.kind === 'NONE') {
        setState(null)
        setRecoveryRequired(false)
        setRemaining(0)
        setWarmupRemaining(0)
        setReplacements([])
        setMessage('没有可恢复的训练，请从计划页开始。')
        return
      }
      setRecoveryRequired(false)
      const resumed = loaded.resumed
      launchContextRef.current = {
        launchMode: 'RESUME_INTERRUPTED',
        clientSessionKey: resumed.state.clientSessionKey,
      }
      if (!mountedRef.current || requestId !== sessionLoadRequestRef.current) return
      setState(resumed.state)
      setRemaining(resumed.remainingSeconds)
      setWarmupRemaining(resumed.warmupRemainingSeconds)
      if (resumed.state.safetyNotice) {
        setMessage('安全信号已保存在本地。请立即停止训练并按上方提示处理。')
        return
      }
      if (loaded.openSummary) {
        setMessage('本次训练已完成，正在整理训练总结。')
        await loaded.openSummary()
        return
      }
      const freshLaunch = loaded.launchMode === 'FRESH_START'
      setMessage(loadedSessionMessage({
        state: resumed.state,
        clockRollbackDetected: resumed.clockRollbackDetected,
        freshLaunch,
      }))
      if (resumed.state.syncStatus === 'OFFLINE_PENDING') {
        void monitorSynchronization(loaded.synchronization, freshLaunch ? 'START' : 'RESUME')
      }
      if (!freshLaunch) application.telemetry.track('workout_resumed', { source: 'foreground' })
    } catch (error) {
      if (isUserGenerationInvalidated(error)) return
      if (mountedRef.current && requestId === sessionLoadRequestRef.current) {
        setSessionLoadFailed(true)
        setMessage('训练记录读取失败，本地草稿未作改动，请重新读取。')
      }
    } finally {
      sessionLoadInFlightRef.current = false
    }
  }

  useWeappDidShow(() => {
    if (!pageHiddenRef.current) return
    pageHiddenRef.current = false
    void loadSession()
  })

  useWeappDidHide(() => {
    pageHiddenRef.current = true
    if (state) application.telemetry.track('workout_paused', { reason: 'background' })
  })

  useEffect(() => {
    return beginWorkoutSessionMount(mountedRef, loadSession)
  }, [])

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
    setReps(current ? String(current.targetRepMax) : '')
    setRir('UNKNOWN')
    setShowEffort(false)
    setShowMotionGuide(false)
    setInputError(null)
  }, [
    state?.clientSessionKey,
    state?.currentExerciseIndex,
    state?.currentSetIndex,
    state?.exercises[state?.currentExerciseIndex ?? 0]?.exerciseCode,
    state?.exercises[state?.currentExerciseIndex ?? 0]?.targetRepMax,
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
      setWeightHint('')
      return () => { active = false }
    }
    const plannedWeight = toWeightInputValue(current.targetWeightKg)
    setWeight(plannedWeight ?? String(DEFAULT_FORMAL_WEIGHT_KG))
    setWeightHint('')
    void application.setAutomaticWorkoutWeight(
      state,
      state.currentExerciseIndex,
      current.exerciseCode,
      current.targetWeightKg,
      () => active
        && requestId === weightSuggestionRequestRef.current
        && !weightDirtyRef.current,
    )
      .then((result) => {
        if (!result) return
        if (!active
          || requestId !== weightSuggestionRequestRef.current
          || weightDirtyRef.current) return
        setWeight(String(result.weightKg))
        setState(result.state)
      })
      .catch((error) => {
        if (isUserGenerationInvalidated(error)) return
        if (active && requestId === weightSuggestionRequestRef.current) {
          setInputError({ field: 'weight', message: '重量暂时无法自动保存，请点击下方按钮重试。' })
        }
      })
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
      setWeightHint('已应用到本动作全部未完成正式组')
      setShowWeightEditor(false)
      setMessage(state.currentSetIndex > 0
        ? '已统一调整本动作全部未完成组的重量；已完成组保持原记录。'
        : '已统一设置本动作全部正式组的重量，后续各组无需重复输入。')
    } catch {
      setInputError({ field: 'action', message: '正式组重量保存失败，请稍后重试。' })
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function monitorSynchronization(
    synchronization: Promise<WorkoutSynchronizationResult>,
    source: WorkoutSynchronizationSource,
  ): Promise<void> {
    const attempt = ++syncAttemptRef.current
    const feedback = synchronizationFeedback[source]
    try {
      const result = await synchronization
      if (!mountedRef.current || attempt !== syncAttemptRef.current || result.kind === 'CANCELLED') return
      if (result.kind === 'FAILED') {
        setMessage(feedback.failed)
        application.telemetry.track('sync_failed', { reason: 'network' })
        return
      }
      const synchronized = result.state
      if (synchronized.syncStatus === 'CONFLICT') {
        setMessage(feedback.conflict)
        application.telemetry.track('sync_failed', { reason: 'conflict' })
      } else if (synchronized.syncStatus === 'SYNC_REJECTED') {
        setMessage(feedback.rejected)
        application.telemetry.track('sync_failed', { reason: 'rejected' })
      } else if (feedback.synchronized) {
        setMessage(feedback.synchronized)
      }
    } catch (error) {
      if (isUserGenerationInvalidated(error)) return
      if (!mountedRef.current || attempt !== syncAttemptRef.current) return
      setMessage(feedback.failed)
      application.telemetry.track('sync_failed', { reason: 'network' })
    }
  }

  async function applyRecordRecovery(
    recovery: WorkoutRecordRecoveryResult | null,
  ): Promise<boolean> {
    if (!recovery) return false
    const restored = recovery.state
    setState(restored)
    if (recovery.resumed) {
      setRemaining(recovery.resumed.remainingSeconds)
      setWarmupRemaining(recovery.resumed.warmupRemainingSeconds)
    }
    if (restored.safetyNotice) {
      setMessage('安全信号已保存在本地。请立即停止训练并按上方提示处理。')
      return true
    }
    if (!isWorkoutPrescriptionFinished(restored)) {
      if (restored.syncStatus === 'OFFLINE_PENDING' && recovery.synchronization) {
        void monitorSynchronization(recovery.synchronization, 'RECORD_RECOVERY')
      }
      return false
    }
    setMessage('本次训练已完成，正在整理训练总结。')
    await recovery.openSummary?.()
    return true
  }

  async function record(
    status: 'COMPLETED' | 'FAILED' | 'SKIPPED',
    safetyFlag?: WorkoutSafetyFlag,
  ): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    setInputError(null)
    try {
      const exerciseIndex = state.currentExerciseIndex
      const exercise = state.exercises[exerciseIndex]
      const optionalSet = isOptionalSetInProgress(state)
      const isBodyweight = exercise.weightStatus === 'BODYWEIGHT'
      const safetyEvent = safetyFlag !== undefined
      if (status !== 'SKIPPED' && !safetyEvent && !isBodyweight && exercise.sessionWeightKg === undefined) {
        setInputError({ field: 'weight', message: '请先确认本次正式组重量。' })
        return
      }
      const parsedWeight = isBodyweight ? 0 : exercise.sessionWeightKg ?? (safetyEvent ? 0 : undefined)
      const parsedReps = reps.trim().length === 0 && safetyEvent ? 0 : Number(reps)
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
      const clientSetKey = optionalSet
        ? `${state.clientSessionKey}-extra-${exercise.optionalSetRule!.exclusiveChoiceGroup}`
        : `${state.clientSessionKey}-${exerciseIndex}-${state.currentSetIndex}`
      const outcome = await application.recordWorkoutSetAndSync(state, {
        clientSetKey,
        exerciseIndex,
        setType: optionalSet ? 'EXTRA' : 'WORK',
        status,
        actualWeightKg: status === 'SKIPPED' ? undefined : parsedWeight,
        actualReps: status === 'SKIPPED' ? undefined : parsedReps,
        rir: status === 'COMPLETED' ? rir : 'UNKNOWN',
        safetyFlag,
      })
      if (outcome.kind === 'RECORD_FAILED') {
        const finished = await applyRecordRecovery(outcome.recovery)
        if (!finished) {
          setInputError({
            field: 'action',
            message: outcome.error instanceof Error
              && outcome.error.message === 'clientSetKey already identifies different workout facts'
              ? '训练进度已从本地记录刷新，请核对当前组后重新提交。'
              : '本组记录失败，请稍后重试；已填写内容仍保留。',
          })
        }
        return
      }
      const updated = outcome.state
      setState(updated)
      setRemaining(updated.restTimer?.configuredDurationSeconds ?? 0)
      setRir('UNKNOWN')
      setShowEffort(false)
      setShowSafetyChoices(false)
      application.telemetry.track('workout_set_completed', { status: status.toLowerCase() as 'completed' | 'failed' | 'skipped' })
      if (status === 'SKIPPED') application.telemetry.track('exercise_skipped', { reason: 'user' })
      const prescriptionFinished = isWorkoutPrescriptionFinished(updated)
      setMessage(recordedSetMessage({
        safetyFlag,
        prescriptionFinished,
        requiredWorkComplete: areRequiredWorkSetsComplete(updated),
        status,
        resting: Boolean(updated.restTimer),
      }))
      void monitorSynchronization(outcome.synchronization, 'RECORDED_SET')
      await outcome.openSummary?.()
    } catch (error) {
      if (isUserGenerationInvalidated(error)) return
      setInputError({ field: 'action', message: '本组记录失败，请稍后重试；已填写内容仍保留。' })
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
      if (exerciseIndex === null) throw new Error('server warmup exercise is unavailable')
      const count = completedRampSets(state)
      const exercise = state.exercises[exerciseIndex]
      const remainingRampSets = remainingRampWarmupSets(state)
      const nextRampSet = remainingRampSets[0]
      if (!nextRampSet) {
        const work = await application.workouts.beginWorkSets(state)
        setState(work)
        setMessage('已进入正式组。')
        return
      }
      const finalWarmupSet = remainingRampSets.length === 1
      const outcome = await application.recordRampSetAndMaybeBeginWorkSets(state, {
        clientSetKey: `${state.clientSessionKey}-warmup-${count + 1}`,
        exerciseIndex,
        setType: 'WARMUP',
        status: 'COMPLETED',
        actualWeightKg: nextRampSet.weightKg,
        actualReps: nextRampSet.reps,
      }, finalWarmupSet)
      if (outcome.kind === 'RECORD_FAILED') {
        const finished = await applyRecordRecovery(outcome.recovery)
        if (!finished) {
          setInputError({
            field: 'action',
            message: outcome.error instanceof Error
              && outcome.error.message === 'clientSetKey already identifies different workout facts'
              ? '训练进度已从本地记录刷新，请核对当前热身组后重新提交。'
              : '热身组记录失败，请稍后重试；已填写内容仍保留。',
          })
        }
        return
      }
      const nextState = outcome.state
      setState(nextState)
      setMessage(finalWarmupSet
        ? '热身完成，正式组重量已准备好。'
        : '热身完成，下一组训练安排中的热身重量已准备好。')
      void monitorSynchronization(outcome.synchronization, 'RECORDED_SET')
    } catch (error) {
      if (isUserGenerationInvalidated(error)) return
      setInputError({ field: 'action', message: '热身组记录失败，请稍后重试；已填写内容仍保留。' })
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function finishGeneralWarmup(): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    try {
      const updated = await application.workouts.completeGeneralWarmup(state)
      setState(updated)
      setMessage(updated.warmup.phase === 'WORK'
        ? '通用热身完成，自重动作无需额外加重，可以开始正式组。'
        : updated.warmup.rampStatus === 'CALIBRATION_REQUIRED'
          ? '通用热身完成。请按校准提示确认正式重量；不会生成推测的热身重量。'
          : '通用热身完成。已按本次训练安排准备轻重量热身。')
    } catch {
      setMessage('热身进度暂时无法保存，请重试；不会跳过当前阶段。')
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function enterWorkSets(): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    try {
      const updated = await application.workouts.beginWorkSets(state)
      setState(updated)
      setMessage('递增热身完成，开始记录正式组。')
    } catch {
      setMessage('暂时无法进入正式组，当前热身记录仍保留，请重试。')
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function adjust(seconds: 15 | -15): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    try {
      const recovery = await application.adjustAndResumeWorkout(state, seconds)
      const resumed = recovery.resumed
      setState(resumed.state)
      setRemaining(resumed.remainingSeconds)
      if (resumed.state.syncStatus === 'OFFLINE_PENDING') {
        void monitorSynchronization(recovery.synchronization, 'REST_ADJUSTMENT')
      }
    } catch (error) {
      if (isUserGenerationInvalidated(error)) return
      setMessage('休息时间调整未保存，已保留原计时。')
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function skipRest(): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    try {
      setState(await application.workouts.skipRest(state))
      setRemaining(0)
      setMessage('休息已结束，可以继续训练。')
    } catch {
      setMessage('休息状态暂时无法保存，请重试后继续。')
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function selectOptionalSet(choiceGroup: string, exerciseIndex: number | null): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    setInputError(null)
    try {
      const result = await application.chooseOptionalSetAndMaybeOpenSummary(
        state,
        choiceGroup,
        exerciseIndex,
      )
      const updated = result.state
      setState(updated)
      if (result.openSummary) {
        setMessage('正式训练已完成，正在整理训练总结。')
        await result.openSummary()
      } else {
        const selected = updated.exercises[updated.currentExerciseIndex]
        setMessage(`已选择 ${selected.name} 补充 1 组；另一项不会再增加。`)
      }
    } catch (error) {
      if (isUserGenerationInvalidated(error)) return
      setInputError({ field: 'action', message: '补充组选择暂时无法保存，请重试。' })
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function showReplacements(): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    try {
      const items = await application.workouts.replacementCandidates(state)
      setReplacements(items)
      setMessage(items.length ? '请选择仅用于本次训练的替代动作。' : '当前没有符合器械和安全条件的替代动作。')
    } catch (reason) {
      setMessage(replacementFailureMessage(reason, 'LOAD'))
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function replace(candidate: ExerciseReplacementCandidate): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    try {
      const updated = await application.workouts.replaceCurrentExercise(state, candidate)
      setState(updated); setReplacements([])
      application.telemetry.track('exercise_replaced', { source: 'user' })
      setMessage('已替换本次训练动作，长期计划保持不变。')
    } catch (reason) {
      setMessage(replacementFailureMessage(reason, 'APPLY'))
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function discardCorruptedDraft(): Promise<void> {
    if (recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    try {
      await application.discardCorruptedDraftAndOpenPlan()
    } catch (error) {
      if (isUserGenerationInvalidated(error)) return
      setMessage('草稿暂时无法清理，未创建新训练，请重试。')
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  async function abandonBlockedOperations(): Promise<void> {
    if (!state || recordingRef.current) return
    recordingRef.current = true
    setRecording(true)
    try {
      const updated = await application.workouts.abandonBlockedOperations(state)
      setState(updated)
      setMessage('未被服务器接受的记录已按你的选择放弃，现在可以结束训练。')
    } catch {
      setMessage('未同步记录尚未放弃，训练草稿仍完整保留，请重试。')
    } finally {
      recordingRef.current = false
      setRecording(false)
    }
  }

  const exercise = state?.exercises[state.currentExerciseIndex]
  const rampExerciseIndex = state?.warmup.rampExerciseIndex
  const rampExercise = rampExerciseIndex === null || rampExerciseIndex === undefined
    ? undefined
    : state?.exercises[rampExerciseIndex]
  const isBodyweight = exercise?.weightStatus === 'BODYWEIGHT'
  const safetyStopped = Boolean(state?.safetyNotice)
  const optionalChoice = state
    ? pendingOptionalSetChoice(state, new Date().toISOString())
    : null
  if (recoveryRequired) return (
    <View className='screen workout-session-page'>
      <View className='page-hero session-hero'>
        <Text className='page-hero__eyebrow'>DRAFT RECOVERY</Text>
        <Text className='page-hero__title'>训练草稿需要恢复</Text>
        <Text className='page-hero__description'>{message}</Text>
      </View>
      <View className='surface-card empty-state'>
        <Text className='section-title'>账号与已同步记录不受影响</Text>
        <Text className='subtitle'>损坏草稿已隔离，不会再覆盖新的训练记录。清理后可从计划页重新开始。</Text>
        <Button
          className='primary-action'
          loading={recording}
          disabled={recording}
          onClick={() => void discardCorruptedDraft()}
        >
          清理损坏草稿并返回计划
        </Button>
      </View>
    </View>
  )

  if (state?.safetyNotice) return (
    <View className='screen workout-session-page workout-session-page--focus'>
      <View className='page-hero session-hero session-hero--work'>
        <Text className='page-hero__eyebrow'>SAFETY STOP</Text>
        <Text className='page-hero__title'>本次训练已停止</Text>
        <Text className='page-hero__description'>安全信号优先于剩余组数和补充组，请不要继续训练。</Text>
      </View>
      <View className='error-box session-alert'>
        <Text>{state.safetyNotice}</Text>
        <Button
          className='primary-action'
          onClick={() => void application.navigation.open('WORKOUT_SUMMARY')}
        >停止训练并查看总结</Button>
      </View>
    </View>
  )

  if (state?.warmup.phase === 'GENERAL') return (
    <View className='screen workout-session-page workout-session-page--focus'>
      <View className='page-hero session-hero session-hero--warmup'>
        <Text className='page-hero__eyebrow'>WARM UP · 01</Text>
        <Text className='page-hero__title'>让身体逐渐进入状态</Text>
        <Text className='page-hero__description'>
          {rampExercise ? `轻松活动，并熟悉今天需要递增热身的动作：${rampExercise.name}。` : '轻松活动，让身体逐渐进入训练状态。'}
        </Text>
        <View className='session-timer'>
          <Text className='session-timer__value data-number'>
            {Math.floor(warmupRemaining / 60)}:{String(warmupRemaining % 60).padStart(2, '0')}
          </Text>
          <Text className='session-timer__label'>保持能正常说话的强度</Text>
        </View>
      </View>
      <View className='surface-card session-guide'>
        {state.warmup.instructions.length > 0
          ? state.warmup.instructions.map((step, index) => (
              <View className='session-guide__step' key={`warmup-${index}`}>
                <Text className='session-guide__index data-number'>{String(index + 1).padStart(2, '0')}</Text>
                <Text>{step.instruction}{step.prescription ? ` · ${step.prescription}` : ''}{step.optional ? '（可选）' : ''}</Text>
              </View>
            ))
          : (
            <>
              <View className='session-guide__step'>
                <Text className='session-guide__index data-number'>01</Text>
                <Text>进行轻松的全身活动</Text>
              </View>
              <View className='session-guide__step'>
                <Text className='session-guide__index data-number'>02</Text>
                <Text>练习主要动作的运动轨迹</Text>
              </View>
              <View className='session-guide__step'>
                <Text className='session-guide__index data-number'>03</Text>
                <Text>无疼痛、呼吸平稳后继续</Text>
              </View>
            </>
          )}
      </View>
      <Text className='session-passive-note'>切到后台也会继续计时</Text>
      <View className='action-row action-row--sticky session-focus-action'>
        <Button className='primary-action' loading={recording} disabled={recording} onClick={() => void finishGeneralWarmup()}>
          {warmupRemaining === 0 ? '热身完成，继续' : '我已准备好'}
        </Button>
      </View>
    </View>
  )

  if (state?.warmup.phase === 'RAMP' && rampExercise) {
    const count = completedRampSets(state)
    const formalWeight = rampExercise.sessionWeightKg
    const remainingRampSets = remainingRampWarmupSets(state)
    const nextRampSet = remainingRampSets[0]
    const totalRampSets = count + remainingRampSets.length
    const progress = totalRampSets === 0 ? 0 : Math.min(100, (count / totalRampSets) * 100)
    return (
      <View className='screen workout-session-page'>
        <View className='page-hero session-hero'>
          <Text className='page-hero__eyebrow'>WARM UP · 02</Text>
          <Text className='page-hero__title'>{rampExercise.name}</Text>
          <Text className='page-hero__description'>
            {state.warmup.rampStatus === 'CALIBRATION_REQUIRED'
              ? state.warmup.calibrationMessage
              : formalWeight === undefined
              ? '先确认今天的正式组重量，再按本次训练安排进行轻重量热身。'
              : `今天正式组 ${formalWeight} KG，以下热身重量由本次训练安排确定。`}
          </Text>
          <View className='session-progress'>
            <View className='session-progress__fill' style={{ width: `${progress}%` }} />
          </View>
          <Text className='session-progress__label'>
            {state.warmup.rampStatus === 'CALIBRATION_REQUIRED'
              ? '需要校准，不生成推测重量'
              : formalWeight === undefined
              ? '等待确认正式组重量'
              : totalRampSets === 0
                ? '正式重量较轻，无需额外热身'
                : `${count} / ${totalRampSets} 个递增热身组`}
          </Text>
        </View>
        <View className='surface-card workout-entry'>
          {formalWeight === undefined && inputError?.field !== 'weight' ? (
            <View className='session-bodyweight-note'>
              正在自动设置正式组重量 {weight || DEFAULT_FORMAL_WEIGHT_KG} KG。
            </View>
          ) : formalWeight === undefined ? (
            <View className='session-weight-setup'>
              <Text className='field-label'>今天正式组重量</Text>
              <Text className='field-helper'>自动保存失败，可调整后重试。</Text>
              <View className='metric-input-wrap'>
                <Input className='metric-input' type='digit' value={weight} placeholder='例如 10' onInput={(event) => updateWeight(event.detail.value)} />
                <Text className='metric-input-unit'>KG</Text>
              </View>
              {inputError?.field === 'weight' && <Text className='session-field-error'>{inputError.message}</Text>}
              <Button
                className='primary-action'
                loading={recording}
                disabled={recording}
                onClick={() => void confirmFormalWeight(rampExerciseIndex!)}
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
                  <Text className='field-helper'>调整后不会推测新重量，只保留低于新正式重量的服务端热身组。</Text>
                  <View className='metric-input-wrap'>
                    <Input className='metric-input' type='digit' value={weight} onInput={(event) => updateWeight(event.detail.value)} />
                    <Text className='metric-input-unit'>KG</Text>
                  </View>
                  {inputError?.field === 'weight' && <Text className='session-field-error'>{inputError.message}</Text>}
                  <Button className='secondary-action' onClick={() => void confirmFormalWeight(rampExerciseIndex!)}>
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
                  <Text className='session-auto-warmup__eyebrow'>没有可执行的精确热身重量</Text>
                  <Text className='session-auto-warmup__reps'>按服务端校准提示确认后，可以进入正式组。</Text>
                </View>
              )}
              <View className='session-safety-note'>热身重量来自本次训练安排；出现疼痛或明显不适请停止。</View>
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
                <Button className='secondary-action' disabled={recording} onClick={() => void enterWorkSets()}>
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
        <Text className='subtitle'>
          {exercise
            ? `第 ${state.currentSetIndex + 1} 组，目标 ${repetitionTargetLabel(exercise.targetRepMin, exercise.targetRepMax)} 次`
            : message}
        </Text>
        <View className='rest-controls__adjust'>
          <Button className='secondary-action' disabled={recording} onClick={() => void adjust(-15)}>− 15 秒</Button>
          <Button className='secondary-action' disabled={recording} onClick={() => void adjust(15)}>＋ 15 秒</Button>
        </View>
        <Button className='primary-action' loading={recording} disabled={recording} onClick={() => void skipRest()}>结束休息，继续训练</Button>
      </View>
    </View>
  )

  if (state && optionalChoice) return (
    <View className='screen workout-session-page'>
      <View className='page-hero session-hero session-hero--optional'>
        <Text className='page-hero__eyebrow'>OPTIONAL SET</Text>
        <Text className='page-hero__title'>固定训练已完成</Text>
        <Text className='page-hero__description'>
          {optionalChoice.eligible
            ? '若当前状态良好，可在下面两项中只选一项增加 1 组；不需要为了凑时长勉强完成。'
            : '本次训练用时已超过 42 分钟，不再增加补充组。'}
        </Text>
      </View>
      <View className='surface-card session-optional-choice'>
        {optionalChoice.eligible && optionalChoice.candidateExerciseIndices.map((exerciseIndex) => (
          <Button
            key={state.exercises[exerciseIndex].snapshotExerciseKey}
            className='secondary-action session-optional-choice__action'
            loading={recording}
            disabled={recording}
            onClick={() => void selectOptionalSet(optionalChoice.choiceGroup, exerciseIndex)}
          >
            状态良好，{state.exercises[exerciseIndex].name}＋1组
          </Button>
        ))}
        <Button
          className='primary-action'
          loading={recording}
          disabled={recording}
          onClick={() => void selectOptionalSet(optionalChoice.choiceGroup, null)}
        >
          {optionalChoice.eligible ? '本次不增加，完成训练' : '完成训练'}
        </Button>
        {inputError?.field === 'action' && <View className='session-action-error'>{inputError.message}</View>}
      </View>
    </View>
  )

  let setProgress = 0
  let setEyebrow = 'TRAINING'
  if (exercise && state) {
    const optionalSet = isOptionalSetInProgress(state)
    setProgress = optionalSet
      ? 100
      : Math.min(100, ((state.currentSetIndex + 1) / exercise.targetWorkSets) * 100)
    const groupLabel = exercise.executionGroup
      ? `SUPERSET ${exercise.executionGroup.replace(/^SUPERSET_/, '')} · `
      : ''
    setEyebrow = optionalSet
      ? 'OPTIONAL SET · 1 OF 1'
      : `${groupLabel}SET ${state.currentSetIndex + 1} OF ${exercise.targetWorkSets}`
  }

  return (
    <View className='screen workout-session-page'>
      <View className='page-hero session-hero session-hero--work'>
        <Text className='page-hero__eyebrow'>
          {setEyebrow}
        </Text>
        <Text className='page-hero__title'>
          {exercise?.name ?? (initialLaunchContext.launchMode === 'FRESH_START' ? '开始训练' : '恢复训练')}
        </Text>
        <Text className='page-hero__description'>
          {exercise
            ? `本组目标 ${repetitionTargetLabel(exercise.targetRepMin, exercise.targetRepMax)} 次${exercise.perSide ? '／侧' : ''}，按真实完成情况记录。`
            : message}
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

      <View className='session-inline-message'>{message}</View>
      {exercise && (exercise.targetRirMin !== undefined || exercise.eccentricSeconds || exercise.executionGroup || exercise.optionalSetRule) && (
        <View className='session-prescription-cues'>
          {exercise.targetRirMin !== undefined && exercise.targetRirMax !== undefined && (
            <Text>目标 RIR {exercise.targetRirMin}～{exercise.targetRirMax}</Text>
          )}
          {exercise.eccentricSeconds && <Text>下放约 {exercise.eccentricSeconds} 秒</Text>}
          {exercise.executionGroup && (
            <Text>与同组动作连续完成，两个动作都完成后再休息</Text>
          )}
          {exercise.optionalSetRule && (
            <Text>
              {optionalSetCue(
                exercise.optionalSetRule.conditionCode,
                Boolean(state && isOptionalSetInProgress(state)),
              )}
            </Text>
          )}
        </View>
      )}
      {sessionLoadFailed && !state && (
        <Button className='secondary-action' onClick={() => void loadSession()}>重新读取训练记录</Button>
      )}

      {exercise && (
        <>
          <View className='surface-card workout-entry'>
            {isBodyweight && (
              <View className='session-bodyweight-note'>
                自重动作无需填写重量，只记录本组实际次数。
              </View>
            )}
            {!isBodyweight && exercise.sessionWeightKg === undefined && inputError?.field !== 'weight' && (
              <View className='session-bodyweight-note'>
                正在自动设置重量 {weight || DEFAULT_FORMAL_WEIGHT_KG} KG。
              </View>
            )}
            {!isBodyweight && (
              (exercise.sessionWeightKg === undefined && inputError?.field === 'weight') || showWeightEditor
            ) && (
              <View className='session-weight-setup'>
                <View className='session-field-heading'>
                  <Text className='field-label'>实际重量</Text>
                  <Text className='field-helper'>应用到本动作全部未完成组</Text>
                </View>
                <Text className='field-helper'>
                  {exercise.sessionWeightKg === undefined
                    ? weightHint || '正在自动使用最近一次有效重量；没有历史时使用 2.5 KG。'
                    : state.currentSetIndex > 0
                      ? '本动作后续未完成组统一使用该重量；已完成组保留原记录'
                      : '本动作全部正式组统一使用该重量，不修改其他动作和长期计划'}
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
                    {recording
                      ? '正在应用'
                      : inputError?.field === 'weight'
                        ? '重试应用重量'
                        : '应用到本动作全部未完成组'}
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
                    >调整本动作重量</Button>
                  </View>
                  <Text className='session-formal-weight__value data-number'>{exercise.sessionWeightKg} KG</Text>
                  <Text className='session-formal-weight__hint'>后续未完成组统一使用此重量</Text>
                </View>
              )}
              <View className='field-group session-reps-field'>
                <View className='session-field-heading'>
                  <Text className='field-label'>实际次数</Text>
                  <Text className='field-helper'>
                    目标 {repetitionTargetLabel(exercise.targetRepMin, exercise.targetRepMax)} 次
                  </Text>
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
                disabled={safetyStopped || recording || (!isBodyweight && exercise.sessionWeightKg === undefined)}
                onClick={() => void record('COMPLETED')}
              >
                {recording ? '正在保存本组' : '完成本组'}
              </Button>
              <Button
                className='secondary-action'
                disabled={safetyStopped || recording || (!isBodyweight && exercise.sessionWeightKg === undefined)}
                onClick={() => void record('FAILED')}
              >未完成</Button>
              <Button className='secondary-action' disabled={safetyStopped || recording} onClick={() => void record('SKIPPED')}>跳过</Button>
              <Button
                className='workout-actions__pain'
                disabled={safetyStopped || recording}
                onClick={() => setShowSafetyChoices((value) => !value)}
              >疼痛或明显不适</Button>
              {showSafetyChoices && !safetyStopped && safetyOptions.map((option) => (
                <Button
                  key={option.value}
                  className='workout-actions__pain'
                  disabled={recording}
                  onClick={() => void record('FAILED', option.value)}
                >{option.label}</Button>
              ))}
            </View>

            <Button className='session-replace-action' disabled={safetyStopped || recording} onClick={() => void showReplacements()}>这个动作今天不合适？更换动作</Button>
            {replacements.length > 0 && (
              <View className='session-replacements'>
                {replacements.map((candidate) => (
                  <Button key={candidate.id} className='secondary-action' disabled={recording} onClick={() => void replace(candidate)}>{candidate.name}</Button>
                ))}
              </View>
            )}
          </View>

          <View className='session-motion-section'>
            {showMotionGuide ? (
              <>
                <WorkoutExerciseMotionGuide
                  compact
                  exerciseCode={exercise.exerciseCode}
                  exerciseName={exercise.name}
                  primaryRef={exerciseContent?.primaryRef}
                  fallbackRef={exerciseContent?.fallbackRef}
                />
                <Button
                  className='session-motion-guide-toggle'
                  onClick={() => setShowMotionGuide(false)}
                >收起动作示例</Button>
              </>
            ) : (
              <View className='surface-card session-motion-opt-in'>
                <View>
                  <Text className='session-motion-opt-in__title'>动作示例按需加载</Text>
                  <Text className='session-motion-opt-in__description'>
                    训练草稿已可直接使用；需要图片时再加载，不影响当前组和计时。
                  </Text>
                </View>
                <Button
                  className='session-motion-guide-toggle'
                  onClick={() => setShowMotionGuide(true)}
                >加载动作示例</Button>
              </View>
            )}
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
        {(state?.syncStatus === 'CONFLICT' || state?.syncStatus === 'SYNC_REJECTED') && (
          <Button disabled={recording} onClick={() => void abandonBlockedOperations()}>
            放弃未同步记录并继续
          </Button>
        )}
      </View>
    </View>
  )
}
