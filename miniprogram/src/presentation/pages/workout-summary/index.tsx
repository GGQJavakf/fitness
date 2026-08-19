import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import { summarizeWorkout, type WorkoutFlowState } from '../../../application/workoutFlow'
import { ApplicationError } from '../../../application/errors'
import type { PlanDay } from '../../../application/models'
import { selectNextTrainingDayCode } from '../../../application/selectNextTrainingDay'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { exerciseDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()
type Summary = ReturnType<typeof summarizeWorkout>

export default function WorkoutSummaryPage() {
  const requestedSessionId = application.routeParameter('sessionId')
  const [summary, setSummary] = useState<Summary | null>(null)
  const [message, setMessage] = useState('正在整理本次训练…')
  const [settling, setSettling] = useState(false)
  const [settled, setSettled] = useState(false)
  const [saveFailureCount, setSaveFailureCount] = useState(0)
  const [summarySessionId, setSummarySessionId] = useState(requestedSessionId)
  const [aiSummary, setAiSummary] = useState('')
  const [summarySource, setSummarySource] = useState<'provider' | 'template' | 'unavailable' | null>(null)
  const [summarizing, setSummarizing] = useState(false)
  const [nextTrainingDay, setNextTrainingDay] = useState<PlanDay | null>(null)
  const [orphanedWorkoutMissing, setOrphanedWorkoutMissing] = useState(false)
  const [historicalLoadFailed, setHistoricalLoadFailed] = useState(false)
  const [historicalStatus, setHistoricalStatus] = useState<'COMPLETED' | 'ABORTED' | null>(null)
  const [advanceToNextTrainingDay, setAdvanceToNextTrainingDay] = useState(false)
  const autoSettlementStarted = useRef(false)
  const completionInFlight = useRef(false)
  const summaryInFlight = useRef(false)
  const workoutLoadInFlight = useRef(false)
  const summaryRequestId = useRef(0)
  const workoutLoadRequestId = useRef(0)
  const completedTrainingDayCodeRef = useRef<string | null>(null)
  const mounted = useRef(true)

  async function prepareNextDay(): Promise<void> {
    if (!nextTrainingDay) return
    try {
      await application.nextTrainingDaySelection.remember(nextTrainingDay.code)
      await application.navigation.replace('WORKOUT_PREPARE', {
        trainingDayCode: nextTrainingDay.code,
      })
    } catch {
      if (mounted.current) setMessage('下一训练日暂时无法打开；训练记录已经保存，请返回计划后重试。')
    }
  }

  async function loadWorkout(): Promise<void> {
    if (workoutLoadInFlight.current) return
    workoutLoadInFlight.current = true
    const requestId = ++workoutLoadRequestId.current
    setMessage('正在整理本次训练…')
    try {
      const state = await application.workouts.load()
      if (!mounted.current || requestId !== workoutLoadRequestId.current) return
        if (!state) {
          setMessage('暂时没有可以总结的训练。')
          return
        }
        const nextSummary = summarizeWorkout(state)
        setSummary(nextSummary)
        if (nextSummary.complete) {
          setMessage('训练已经完成，正在自动保存并生成回顾。')
          if (!autoSettlementStarted.current) {
            autoSettlementStarted.current = true
            await completeWorkout(state, nextSummary, 'FULL')
          }
          return
        }
        setMessage('已完成的内容会如实保存；提前结束不会触发自动加重。')
    } catch {
      if (mounted.current && requestId === workoutLoadRequestId.current) {
        setMessage('训练记录暂时无法读取，请稍后重试。')
      }
    } finally {
      workoutLoadInFlight.current = false
    }
  }

  async function loadHistoricalWorkout(sessionId: string): Promise<void> {
    if (workoutLoadInFlight.current) return
    workoutLoadInFlight.current = true
    const requestId = ++workoutLoadRequestId.current
    setHistoricalLoadFailed(false)
    setMessage('正在读取训练回顾…')
    try {
      const historical = await application.getWorkoutSessionSummary(sessionId)
      if (!mounted.current || requestId !== workoutLoadRequestId.current) return
      setHistoricalStatus(historical.status)
      setSummary({
        completedWorkSets: historical.completedWorkSets,
        completedVolumeKg: historical.completedVolumeKg,
        completedReps: historical.completedReps,
        usesExternalLoad: historical.usesExternalLoad,
        failedSets: 0,
        skippedSets: 0,
        complete: historical.status === 'COMPLETED',
      })
      setSettled(true)
    } catch {
      if (mounted.current && requestId === workoutLoadRequestId.current) {
        setHistoricalLoadFailed(true)
        setMessage('训练回顾暂时无法读取，请重新读取。')
      }
    } finally {
      if (mounted.current && requestId === workoutLoadRequestId.current) {
        workoutLoadInFlight.current = false
      }
    }
  }

  async function loadAiSummary(sessionId: string): Promise<void> {
    if (summaryInFlight.current) return
    summaryInFlight.current = true
    const requestId = ++summaryRequestId.current
    setSummarizing(true)
    try {
      application.telemetry.track('ai_summary_requested', { purpose: 'workout_summary' })
      const generated = await application.requestWorkoutSummary(sessionId)
      if (!mounted.current || requestId !== summaryRequestId.current) return
      setAiSummary(generated.content)
      const source = generated.status === 'DEGRADED' ? 'template' : 'provider'
      setSummarySource(source)
      setMessage(source === 'template' ? '规则模板回顾已准备好。' : 'AI 生成回顾已准备好。')
      application.telemetry.track('ai_summary_viewed', { source })
    } catch {
      if (!mounted.current || requestId !== summaryRequestId.current) return
      setAiSummary('个性化回顾暂不可用。训练记录已经保存，后续计划调整不受影响。')
      setSummarySource('unavailable')
      setMessage('训练记录已保存。')
      application.telemetry.track('ai_summary_failed', { reason: 'unavailable' })
    } finally {
      summaryInFlight.current = false
      if (mounted.current && requestId === summaryRequestId.current) setSummarizing(false)
    }
  }

  async function loadNextTrainingDay(completedDayCode?: string, attempt = 0): Promise<void> {
    try {
      const activePlan = await application.loadActivePlan()
      if (!mounted.current) return
      if (!activePlan) throw new Error('active plan is not visible after workout completion')
      const days = activePlan.activeVersion.plan.days
      let nextCode = ''
      const completedIndex = completedDayCode
        ? days.findIndex((day) => day.code === completedDayCode)
        : -1
      if (completedIndex >= 0) {
        nextCode = days[(completedIndex + 1) % days.length]?.code ?? ''
      } else {
        const history = await application.listWorkoutHistory(undefined, 50)
        nextCode = selectNextTrainingDayCode(days, history.items)
      }
      setNextTrainingDay(days.find((day) => day.code === nextCode) ?? null)
    } catch {
      if (!mounted.current) return
      if (attempt < 2) {
        await new Promise((resolve) => setTimeout(resolve, 150 * (attempt + 1)))
        if (mounted.current) await loadNextTrainingDay(completedDayCode, attempt + 1)
        return
      }
      setNextTrainingDay(null)
    }
  }

  useEffect(() => {
    mounted.current = true
    if (requestedSessionId) {
      void loadHistoricalWorkout(requestedSessionId)
      void loadAiSummary(requestedSessionId)
    } else {
      void loadWorkout()
    }
    return () => {
      mounted.current = false
      summaryRequestId.current += 1
      workoutLoadRequestId.current += 1
    }
  }, [])

  useEffect(() => {
    if (settled && advanceToNextTrainingDay && !requestedSessionId) {
      void loadNextTrainingDay(completedTrainingDayCodeRef.current ?? undefined)
    }
  }, [settled, advanceToNextTrainingDay])

  async function completeWorkout(
    state: WorkoutFlowState,
    nextSummary: Summary,
    completionType: 'FULL' | 'EARLY_END',
  ): Promise<void> {
    if (completionInFlight.current || settled) return
    completionInFlight.current = true
    setSettling(true)
    try {
      const result = await application.workouts.complete(state, completionType)
      setOrphanedWorkoutMissing(false)
      setMessage(result.complete ? '本次训练已完成并保存。' : '已保存完成的部分；本次不会据此提高训练重量。')
      completedTrainingDayCodeRef.current = result.session.trainingDayCode ?? null
      setAdvanceToNextTrainingDay(result.complete)
      setSettled(true)
      setSummarySessionId(result.session.id)
      application.telemetry.track(result.complete ? 'workout_completed' : 'workout_aborted', {
        completedSetCount: nextSummary.completedWorkSets,
      })
      await loadAiSummary(result.session.id)
    } catch (error) {
      const nextFailureCount = saveFailureCount + 1
      setSaveFailureCount(nextFailureCount)
      const reason = error instanceof ApplicationError && error.code === 'NETWORK_ERROR'
        ? '当前无法连接训练服务'
        : error instanceof ApplicationError && error.code === 'RESOURCE_NOT_FOUND'
          ? '服务端没有找到对应训练会话'
          : '服务端暂未确认这次保存'
      setOrphanedWorkoutMissing(error instanceof ApplicationError && error.code === 'RESOURCE_NOT_FOUND')
      setMessage(`第 ${nextFailureCount} 次保存失败：${reason}。本地训练记录仍然保留，可以稍后继续保存。`)
    } finally {
      completionInFlight.current = false
      setSettling(false)
    }
  }

  async function settle(): Promise<void> {
    const state = await application.workouts.load()
    if (!state || !summary) return
    await completeWorkout(state, summary, summary.complete ? 'FULL' : 'EARLY_END')
  }

  async function discardOrphanedWorkout(): Promise<void> {
    if (!orphanedWorkoutMissing || completionInFlight.current) return
    completionInFlight.current = true
    setSettling(true)
    try {
      const state = await application.workouts.load()
      if (!state) throw new Error('the orphaned local workout is no longer active')
      await application.workouts.discardOrphanedLocalWorkout(state)
      setOrphanedWorkoutMissing(false)
      await application.navigation.replace('PLAN')
    } catch {
      setMessage('本地记录暂时无法放弃，已继续保留；请稍后重试。')
    } finally {
      completionInFlight.current = false
      setSettling(false)
    }
  }

  const completionLabel = summary?.complete ? '完整完成' : '部分完成'
  const reviewTitle = requestedSessionId
    ? '训练回顾'
    : !summary
      ? '训练总结'
      : summary.complete
        ? '训练完成'
        : '本次训练已记录'

  return (
    <View className='screen workout-summary-page'>
      <View className='page-hero summary-hero'>
        <Text className='page-hero__eyebrow'>TRAINING REVIEW</Text>
        <Text className='page-hero__title'>{reviewTitle}</Text>
        <Text className='page-hero__description'>{message}</Text>
        {summary && (
          <View className='summary-hero__status'>
            <View className={summary.complete ? 'summary-hero__dot' : 'summary-hero__dot summary-hero__dot--partial'} />
            <Text>{completionLabel}</Text>
          </View>
        )}
      </View>

      {summary ? (
        <View className='surface-card summary-metrics'>
          <View className='summary-metric summary-metric--primary'>
            <Text className='summary-metric__value data-number'>{summary.completedWorkSets}</Text>
            <Text className='summary-metric__unit'>组</Text>
            <Text className='summary-metric__label'>有效正式组</Text>
          </View>
          <View className='summary-metric'>
            <Text className='summary-metric__value data-number'>
              {summary.usesExternalLoad ? summary.completedVolumeKg : summary.completedReps}
            </Text>
            <Text className='summary-metric__unit'>{summary.usesExternalLoad ? 'KG·次' : '次'}</Text>
            <Text className='summary-metric__label'>{summary.usesExternalLoad ? '训练容量' : '完成次数'}</Text>
          </View>
          {!(requestedSessionId && historicalStatus === 'ABORTED') && (
            <View className='summary-metric'>
              <Text className='summary-metric__value data-number'>{summary.failedSets + summary.skippedSets}</Text>
              <Text className='summary-metric__unit'>组</Text>
              <Text className='summary-metric__label'>未完成或跳过</Text>
            </View>
          )}
        </View>
      ) : (
        <View className='surface-card empty-state summary-empty'>
          <View className='summary-empty__mark' />
          <Text className='section-title'>{requestedSessionId
            ? historicalLoadFailed ? '训练回顾暂时无法读取' : '正在读取训练回顾'
            : '还没有训练记录'}</Text>
          <Text className='subtitle'>{requestedSessionId
            ? historicalLoadFailed ? '训练事实仍保留在服务端，请重新读取。' : '稍等片刻，训练事实读取后会显示在这里。'
            : '从计划页开始训练，完成后就能看到表现总结。'}</Text>
        </View>
      )}

      {summary && (
        <View className={summary.complete ? 'summary-quality' : 'summary-quality summary-quality--partial'}>
          <View className='summary-quality__mark'>
            <View className='summary-quality__check' />
          </View>
          <View>
            <Text className='summary-quality__title'>{summary.complete ? '本次数据可用于后续调整' : '本次仅保留实际完成内容'}</Text>
            <Text className='summary-quality__description'>
              {summary.complete
                ? '系统会结合连续训练表现判断是否需要调整。'
                : requestedSessionId && historicalStatus === 'ABORTED'
                  ? '历史汇总仅展示实际完成内容；未完成组数不会被推测。'
                  : '未完成的训练不会触发自动加重。'}
            </Text>
          </View>
        </View>
      )}

      {(aiSummary || summarizing) && (
        <View className='surface-card summary-coach'>
          <View className='summary-coach__heading'>
            <View className='summary-coach__mark'>
              <View className='summary-coach__mark-line' />
              <View className='summary-coach__mark-line summary-coach__mark-line--short' />
            </View>
            <View>
              <Text className='summary-coach__eyebrow'>{summarySource === 'provider' ? 'AI GENERATED' : summarySource === 'template' ? 'RULE TEMPLATE' : 'TRAINING NOTE'}</Text>
              <Text className='summary-coach__title'>{summarySource === 'provider' ? 'AI 生成回顾' : summarySource === 'template' ? '规则训练回顾' : '训练记录说明'}</Text>
            </View>
          </View>
          <Text className='summary-coach__content'>{summarizing ? '正在结合训练记录生成回顾…' : aiSummary}</Text>
        </View>
      )}

      {settled && nextTrainingDay && (
        <View className='surface-card summary-next-day'>
          <Text className='section-title'>下一次：{nextTrainingDay.name}</Text>
          <Text className='subtitle'>系统已按刚完成的训练日自动轮换；开始前仍可自由选择计划中的其他训练日。</Text>
          <View className='summary-next-day__exercises'>
            {nextTrainingDay.exercises.map((exercise, index) => (
              <Text key={exercise.exerciseCode} className='summary-next-day__exercise'>
                {index + 1}. {exerciseDisplayName(exercise.exerciseCode)} · {exercise.workSets} 组
              </Text>
            ))}
          </View>
          <Button
            className='primary-action'
            onClick={() => void prepareNextDay()}
          >
            准备下一训练日
          </Button>
        </View>
      )}

      <View className='action-row action-row--sticky summary-actions'>
        {requestedSessionId && historicalLoadFailed && (
          <Button className='secondary-action' onClick={() => void loadHistoricalWorkout(requestedSessionId)}>重新读取训练回顾</Button>
        )}
        {!requestedSessionId && !summary && message.includes('无法读取') && (
          <Button className='secondary-action' onClick={() => void loadWorkout()}>重新读取训练记录</Button>
        )}
        {summary && !settled && (
          <Button
            className={summary.complete ? 'primary-action' : 'secondary-action'}
            loading={settling}
            disabled={settling}
            onClick={() => void settle()}
          >
            {settling ? '正在保存到训练记录' : summary.complete ? '重新保存到训练记录' : '保存并提前结束'}
          </Button>
        )}
        {orphanedWorkoutMissing && (
          <Button
            className='secondary-action'
            loading={settling}
            disabled={settling}
            onClick={() => void discardOrphanedWorkout()}
          >
            放弃这条无法保存的本地记录
          </Button>
        )}
        {summarySessionId && (
          <Button
            className='secondary-action'
            disabled={summarizing}
            onClick={() => void loadAiSummary(summarySessionId)}
          >
            {summarizing ? '正在生成回顾' : '重新生成训练总结'}
          </Button>
        )}
        <Button className='secondary-action' onClick={() => void application.navigation.replace('PLAN')}>返回训练计划</Button>
        <Button className='summary-actions__history' onClick={() => void application.navigation.replace('HISTORY')}>查看训练进展</Button>
      </View>
    </View>
  )
}
