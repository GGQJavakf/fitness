import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import { summarizeWorkout } from '../../../application/workoutFlow'
import { ApplicationError } from '../../../application/errors'
import type { PlanDay } from '../../../application/models'
import { getWorkoutApplication } from '../../../platform/weapp/featureRoots/workoutCompositionRoot'
import { exerciseDisplayName } from '../../copy'

type Summary = ReturnType<typeof summarizeWorkout>

export default function WorkoutSummaryPage() {
  const application = getWorkoutApplication()
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
      await application.prepareNextTrainingDay(nextTrainingDay.code)
    } catch {
      if (mounted.current) setMessage('下一训练日暂时无法打开；训练记录已经保存，请返回计划后重试。')
    }
  }

  async function loadWorkout(): Promise<void> {
    await runWorkoutWorkflow()
  }

  async function runWorkoutWorkflow(
    completionType?: 'FULL' | 'EARLY_END',
  ): Promise<void> {
    if (workoutLoadInFlight.current) return
    if (completionInFlight.current || (completionType && settled)) return
    workoutLoadInFlight.current = true
    completionInFlight.current = true
    const requestId = ++workoutLoadRequestId.current
    setSettling(Boolean(completionType))
    setSummarizing(true)
    setMessage('正在整理本次训练…')
    try {
      const result = await application.loadAndSettleWorkout(completionType)
      if (!mounted.current || requestId !== workoutLoadRequestId.current) return
      if (result.kind === 'EMPTY') {
        setMessage('暂时没有可以总结的训练。')
        return
      }
      setSummary(result.summary)
      if (result.kind === 'LOADED') {
        setMessage('已完成的内容会如实保存；提前结束不会触发自动加重。')
        return
      }
      if (result.kind === 'SETTLEMENT_FAILED') {
        reportSettlementFailure(result.error)
        return
      }

      const completion = result.completion
      setOrphanedWorkoutMissing(false)
      completedTrainingDayCodeRef.current = completion.session.trainingDayCode ?? null
      setAdvanceToNextTrainingDay(completion.complete)
      setSettled(true)
      setSummarySessionId(completion.session.id)
      application.telemetry.track(completion.complete ? 'workout_completed' : 'workout_aborted', {
        completedSetCount: result.summary.completedWorkSets,
      })
      application.telemetry.track('ai_summary_requested', { purpose: 'workout_summary' })
      if (result.generatedSummary) {
        const source = result.generatedSummary.status === 'DEGRADED' ? 'template' : 'provider'
        setAiSummary(result.generatedSummary.content)
        setSummarySource(source)
        setMessage(source === 'template' ? '规则模板回顾已准备好。' : 'AI 生成回顾已准备好。')
        application.telemetry.track('ai_summary_viewed', { source })
      } else {
        setAiSummary('个性化回顾暂不可用。训练记录已经保存，后续计划调整不受影响。')
        setSummarySource('unavailable')
        setMessage('训练记录已保存。')
        application.telemetry.track('ai_summary_failed', { reason: 'unavailable' })
      }
    } catch {
      if (mounted.current && requestId === workoutLoadRequestId.current) {
        setMessage('训练记录暂时无法读取，请稍后重试。')
      }
    } finally {
      workoutLoadInFlight.current = false
      completionInFlight.current = false
      if (mounted.current && requestId === workoutLoadRequestId.current) {
        setSettling(false)
        setSummarizing(false)
      }
    }
  }

  function reportSettlementFailure(error: unknown): void {
    const nextFailureCount = saveFailureCount + 1
    setSaveFailureCount(nextFailureCount)
    const reason = error instanceof ApplicationError && error.code === 'NETWORK_ERROR'
      ? '当前无法连接训练服务'
      : error instanceof ApplicationError && error.code === 'RESOURCE_NOT_FOUND'
        ? '服务端没有找到对应训练会话'
        : '服务端暂未确认这次保存'
    setOrphanedWorkoutMissing(error instanceof ApplicationError && error.code === 'RESOURCE_NOT_FOUND')
    setMessage(`第 ${nextFailureCount} 次保存失败：${reason}。本地训练记录仍然保留，可以稍后继续保存。`)
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

  async function loadNextTrainingDay(completedDayCode?: string): Promise<void> {
    try {
      const nextDay = await application.loadNextTrainingDay(completedDayCode)
      if (mounted.current) setNextTrainingDay(nextDay)
    } catch {
      if (!mounted.current) return
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

  async function settle(): Promise<void> {
    if (!summary) return
    await runWorkoutWorkflow(summary.complete ? 'FULL' : 'EARLY_END')
  }

  async function discardOrphanedWorkout(): Promise<void> {
    if (!orphanedWorkoutMissing || completionInFlight.current) return
    completionInFlight.current = true
    setSettling(true)
    try {
      await application.discardOrphanedWorkout()
      setOrphanedWorkoutMissing(false)
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
