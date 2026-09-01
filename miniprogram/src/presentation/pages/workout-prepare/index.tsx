import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import type { ActivePlanData } from '../../../application/models'
import type { WorkoutFlowState } from '../../../application/workoutFlow'
import type { WorkoutRecoveryAssessment } from '../../../application/ports/WorkoutRecoveryPort'
import { PendingWorkoutStartError } from '../../../application/ports/WorkoutStartIntentStore'
import { selectNextTrainingDayCode } from '../../../application/selectNextTrainingDay'
import type {
  CoordinatedWorkoutStartInput,
  CoordinatedWorkoutStartResult,
} from '../../../application/use-cases/WorkoutStartCoordinator'
import {
  WorkoutReplacementWorkflowError,
  type WorkoutReplacementPhase,
} from '../../../application/workoutReplacementWorkflow'
import { getWorkoutApplication } from '../../../platform/weapp/featureRoots/workoutCompositionRoot'
import { exerciseDisplayName } from '../../copy'

const muscleLabels: Readonly<Record<string, string>> = {
  BACK: '背部',
  BICEPS: '肱二头肌',
  CALVES: '小腿',
  CHEST: '胸部',
  CORE: '核心',
  GLUTES: '臀部',
  HAMSTRINGS: '大腿后侧',
  LATS: '背阔肌',
  QUADRICEPS: '大腿前侧',
  SHOULDERS: '肩部',
  TRICEPS: '肱三头肌',
}

type WorkoutStartPhase = 'IDLE' | 'STARTING' | WorkoutReplacementPhase

export default function WorkoutPreparePage() {
  const application = getWorkoutApplication()
  const [routedTrainingDayCode] = useState(
    () => application.routeParameter('trainingDayCode')?.trim() ?? '',
  )
  const [plan, setPlan] = useState<ActivePlanData | null>(null)
  const [selectedDayCode, setSelectedDayCode] = useState('')
  const [message, setMessage] = useState('正在准备今天的训练…')
  const [clientSessionKey, setClientSessionKey] = useState(newWorkoutClientSessionKey)
  const [loading, setLoading] = useState(true)
  const [planReadFailed, setPlanReadFailed] = useState(false)
  const [startPhase, setStartPhase] = useState<WorkoutStartPhase>('IDLE')
  const [unfinished, setUnfinished] = useState<WorkoutFlowState | null>(null)
  const [recoveryWarning, setRecoveryWarning] = useState<WorkoutRecoveryAssessment | null>(null)
  const [recoveryConfirmationToken, setRecoveryConfirmationToken] = useState<string | null>(null)
  const [daySelectionRequired, setDaySelectionRequired] = useState(false)
  const loadActiveRef = useRef(false)
  const loadRequestIdRef = useRef(0)
  const mountedRef = useRef(true)
  const startInFlightRef = useRef(false)
  const abandonInFlightRef = useRef(false)
  const starting = startPhase !== 'IDLE'

  async function loadPlan(): Promise<void> {
    if (loadActiveRef.current) return
    loadActiveRef.current = true
    const requestId = ++loadRequestIdRef.current
    setLoading(true)
    setPlanReadFailed(false)
    setMessage('正在准备今天的训练…')
    try {
      const {
        plan: value,
        history,
        historyUnavailable,
        rememberedTrainingDayCode,
      } = await application.loadWorkoutPreparation()
      if (!mountedRef.current || requestId !== loadRequestIdRef.current) return
      const requestedTrainingDayCode = (
        routedTrainingDayCode || rememberedTrainingDayCode || ''
      ).trim()
      const requestedDayExists = Boolean(value?.activeVersion.plan.days.some(
        (day) => day.code === requestedTrainingDayCode,
      ))
      setPlan(value)
      setDaySelectionRequired(Boolean(value && historyUnavailable && !requestedDayExists))
      setSelectedDayCode(value
        ? requestedDayExists
          ? requestedTrainingDayCode
          : !historyUnavailable
            ? selectNextTrainingDayCode(value.activeVersion.plan.days, history.items)
            : ''
        : '')
      setMessage(value
        ? historyUnavailable && !requestedDayExists
          ? '计划已加载，但暂时无法判断上次完成到哪一天；请重新判断，或明确选择今天要练的训练日。'
          : '计划已就绪，确认今天的训练安排即可开始。'
        : '暂时没有可开始的训练计划。')
    } catch {
      if (!mountedRef.current || requestId !== loadRequestIdRef.current) return
      setPlanReadFailed(true)
      setMessage('计划暂时无法读取，请检查网络后重试。')
    } finally {
      loadActiveRef.current = false
      if (mountedRef.current && requestId === loadRequestIdRef.current) setLoading(false)
    }
  }

  useEffect(() => {
    mountedRef.current = true
    void loadPlan()
    return () => {
      mountedRef.current = false
      loadRequestIdRef.current += 1
    }
  }, [])

  function buildStartInput(
    activeDraftDecision?: 'RESUME',
    confirmationToken?: string,
  ): CoordinatedWorkoutStartInput | null {
    const day = plan?.activeVersion.plan.days.find((item) => item.code === selectedDayCode)
    if (!plan || !day) return null
    return {
      clientSessionKey,
      planId: plan.planId,
      planVersionNo: plan.activeVersion.versionNumber,
      planDayId: day.code,
      activeDraftDecision,
      ...(confirmationToken ? { recoveryConfirmationToken: confirmationToken } : {}),
    }
  }

  function applyStartResult(result: CoordinatedWorkoutStartResult): void {
    if (result.kind === 'RECOVERY_CONFIRMATION_REQUIRED') {
      setRecoveryWarning(result.assessment)
      setRecoveryConfirmationToken(result.confirmationToken)
      setMessage('最近的实际训练仍在规则恢复窗口内，请明确选择下一步。')
      return
    }
    if (result.kind === 'RESUME_REQUIRED') {
      setUnfinished(result.state)
      setMessage('发现一场未完成训练。继续原训练，或明确结束原训练后再创建新会话。')
      return
    }
    if (result.kind === 'TERMINAL_REPLAY') {
      setClientSessionKey(newWorkoutClientSessionKey())
      setRecoveryWarning(null)
      setRecoveryConfirmationToken(null)
      setMessage('上次训练已经结束，已准备新的安全启动标识；请再次点击开始。')
      return
    }
    setUnfinished(null)
    setRecoveryWarning(null)
    setRecoveryConfirmationToken(null)
    if (result.kind === 'STARTED') {
      application.telemetry.track('workout_started', { exerciseCount: result.state.exercises.length })
    }
  }

  function handleStartError(error: unknown, currentPlan: ActivePlanData): void {
    if (error instanceof PendingWorkoutStartError
      && currentPlan.planId === error.intent.planId
      && currentPlan.activeVersion.versionNumber === error.intent.planVersionNo
      && currentPlan.activeVersion.plan.days.some((item) => item.code === error.intent.planDayId)) {
      setSelectedDayCode(error.intent.planDayId)
      setRecoveryWarning(null)
      setRecoveryConfirmationToken(null)
      setMessage('检测到上次开始训练的结果尚未确认，已切回原训练日；请再次点击以安全恢复。')
    } else {
      setMessage('本次训练暂未开始，请稍后重试；重复点击不会生成两次训练。')
    }
  }

  async function start(
    activeDraftDecision?: 'RESUME',
    confirmationToken?: string,
  ): Promise<void> {
    const currentPlan = plan
    const input = buildStartInput(activeDraftDecision, confirmationToken)
    if (!currentPlan || !input || startInFlightRef.current || abandonInFlightRef.current) return
    startInFlightRef.current = true
    setStartPhase('STARTING')
    setMessage(confirmationToken ? '正在按你的明确选择开始训练…' : '正在开始训练…')
    try {
      const result = await application.startWorkout(input)
      if (result) applyStartResult(result)
    } catch (error) {
      handleStartError(error, currentPlan)
    } finally {
      startInFlightRef.current = false
      setStartPhase('IDLE')
    }
  }

  async function chooseAnotherDay(): Promise<void> {
    if (!await clearUncreatedRecoveryStart()) return
    setRecoveryWarning(null)
    setRecoveryConfirmationToken(null)
    setMessage('请选择其他训练日；系统不会自动重排或减量。')
  }

  async function postponeWorkout(): Promise<void> {
    const input = buildCancelInput()
    if (!input) return
    try {
      const completed = await application.cancelWorkoutStartAndOpenPlan(input)
      if (!completed) return
      setRecoveryWarning(null)
      setRecoveryConfirmationToken(null)
    } catch {
      setMessage('暂时无法安全清除上次待启动记录，已保留当前恢复提示；请稍后重试。')
    }
  }

  function buildCancelInput() {
    const recoveryDay = plan?.activeVersion.plan.days.find((item) => item.code === selectedDayCode)
    if (!plan || !recoveryDay) {
      setMessage('当前训练日信息已变化，无法安全清除待启动记录；请重新读取计划。')
      return null
    }
    return {
      clientSessionKey,
      planId: plan.planId,
      planVersionNo: plan.activeVersion.versionNumber,
      planDayId: recoveryDay.code,
    }
  }

  async function clearUncreatedRecoveryStart(): Promise<boolean> {
    const input = buildCancelInput()
    if (!input) return false
    try {
      await application.workoutStart.cancelUncreatedStart(input)
      return true
    } catch {
      setMessage('暂时无法安全清除上次待启动记录，已保留当前恢复提示；请稍后重试。')
      return false
    }
  }

  async function abandonAndStart(confirmationToken?: string): Promise<void> {
    const currentPlan = plan
    const currentUnfinished = unfinished
    const input = buildStartInput(undefined, confirmationToken)
    if (!currentPlan || !currentUnfinished || !input
      || abandonInFlightRef.current || startInFlightRef.current) return
    abandonInFlightRef.current = true
    setStartPhase('ENDING_ACTIVE')
    setMessage('正在结束原训练…')
    try {
      const result = await application.abandonAndStartWorkout(currentUnfinished, input, {
        onPhaseChanged(phase) {
          if (!mountedRef.current) return
          setStartPhase(phase)
          setMessage(phase === 'OPENING_NEW'
            ? '新训练已创建，正在打开训练页…'
            : '正在结束原训练…')
        },
      })
      if (result) applyStartResult(result)
    } catch (error) {
      const failure = error instanceof WorkoutReplacementWorkflowError
        ? error.failure
        : error
      const failedAfterEnding = error instanceof WorkoutReplacementWorkflowError
        && error.phase === 'OPENING_NEW'
      if (failedAfterEnding) {
        setUnfinished(null)
        setRecoveryWarning(null)
        setRecoveryConfirmationToken(null)
        setMessage('新训练已创建，但页面暂时未打开；请再次点击开始以安全恢复。')
      } else if (failure instanceof PendingWorkoutStartError) {
        handleStartError(failure, currentPlan)
      } else {
        setMessage('原训练暂时无法安全结束，已保留草稿；请稍后重试或继续原训练。')
      }
    } finally {
      abandonInFlightRef.current = false
      if (!startInFlightRef.current) setStartPhase('IDLE')
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
              <Text className='prepare-hero__metric-value data-number'>1</Text>
              <Text className='prepare-hero__metric-label'>次通用热身</Text>
            </View>
          </View>
        )}
      </View>

      {!loading && !plan && (
        <View className='surface-card empty-state'>
          <Text className='section-title'>{planReadFailed ? '训练计划读取失败' : '还没有可开始的计划'}</Text>
          <Text className='subtitle'>{planReadFailed
            ? '这不是计划被删除。请确认训练服务可用后重新读取。'
            : '先生成科学训练方案，再回来开始训练。'}</Text>
          <Button className='primary-action' onClick={() => void application.navigation.replace('PLAN')}>查看我的计划</Button>
          <Button className='secondary-action' onClick={() => void loadPlan()}>重新读取</Button>
        </View>
      )}

      {plan && (
        <>
          <View className='section-heading'>
            <Text className='section-heading__title'>今天这样练</Text>
            <Text className='section-heading__meta'>默认下一天，也可自由改选</Text>
          </View>
          <View className='surface-card prepare-schedule'>
            <View className='prepare-readiness'>
              <View className='prepare-readiness__mark'>
                <View className='prepare-readiness__check' />
              </View>
              <View className='prepare-readiness__copy'>
                <Text className='prepare-readiness__title'>{daySelectionRequired ? '需要确认训练日' : '训练条件已匹配'}</Text>
                <Text className='prepare-readiness__description'>{daySelectionRequired
                  ? '本次没有自动猜测训练日，确认后仍会由服务端检查实际恢复窗口。'
                  : '今天的动作、组数和休息时间已经按你的档案安排。'}</Text>
              </View>
            </View>
            {daySelectionRequired && (
              <Button className='secondary-action' loading={loading} onClick={() => void loadPlan()}>重新判断训练日</Button>
            )}
            <View className='day-options'>
              {plan.activeVersion.plan.days.map((option, index) => (
                <Button
                  key={option.code}
                  className={option.code === selectedDayCode ? 'day-option day-option--selected' : 'day-option'}
                  onClick={() => {
                    setSelectedDayCode(option.code)
                    setDaySelectionRequired(false)
                    setRecoveryWarning(null)
                    setRecoveryConfirmationToken(null)
                    setMessage('已切换训练日，开始前会重新检查实际恢复窗口。')
                  }}
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
              <Text className='subtitle'>开始训练时由服务端按当前规则确定热身时长；训练页会按已确定的安排执行。</Text>
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

          {recoveryWarning && (
            <View className='surface-card recovery-warning'>
              <Text className='recovery-warning__title'>恢复窗口尚未满足</Text>
              <View className='recovery-warning__facts'>
                {recoveryWarning.affectedMuscles.map((affected) => (
                  <View className='recovery-warning__fact' key={affected.muscleGroup}>
                    <Text className='recovery-warning__muscle'>
                      {muscleLabels[affected.muscleGroup] ?? affected.muscleGroup}
                    </Text>
                    <Text className='recovery-warning__duration'>
                      {`已过 ${affected.elapsedHours} 小时，规则最低 ${affected.minimumRecoveryHours} 小时`}
                    </Text>
                  </View>
                ))}
              </View>
              <Text className='recovery-warning__note'>
                这是基于已完成训练事实的规则提醒，不是医疗判断；系统不会自动重排训练日或减少训练量。
              </Text>
              <View className='recovery-warning__actions'>
                <Button className='secondary-action' disabled={starting} onClick={() => void chooseAnotherDay()}>
                  选择其他训练日
                </Button>
                <Button className='secondary-action' disabled={starting} onClick={() => void postponeWorkout()}>
                  暂不训练
                </Button>
                <Button
                  className='primary-action'
                  loading={startPhase === 'STARTING'}
                  disabled={starting}
                  onClick={() => {
                    if (!recoveryConfirmationToken) return
                    if (unfinished) {
                      void abandonAndStart(recoveryConfirmationToken)
                    } else {
                      void start(undefined, recoveryConfirmationToken)
                    }
                  }}
                >
                  仍继续本次训练
                </Button>
              </View>
            </View>
          )}
        </>
      )}

      {!recoveryWarning && <View className='action-row action-row--sticky prepare-actions'>
        {unfinished && (
          <Button
            className='secondary-action'
            loading={startPhase === 'ENDING_ACTIVE' || startPhase === 'OPENING_NEW'}
            disabled={starting}
            onClick={() => void abandonAndStart()}
          >
            {startPhase === 'ENDING_ACTIVE'
              ? '正在结束原训练…'
              : startPhase === 'OPENING_NEW'
                ? '正在打开新训练…'
                : '结束原训练并开始新的'}
          </Button>
        )}
        <Button
          className='primary-action'
          loading={startPhase === 'STARTING'}
          disabled={!day || starting}
          onClick={() => void start(unfinished ? 'RESUME' : undefined)}
        >
          {startPhase === 'STARTING' ? '正在开始训练' : unfinished ? '继续未完成训练' : '开始热身'}
        </Button>
      </View>}
    </View>
  )
}

function newWorkoutClientSessionKey(): string {
  return `weapp-session-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
