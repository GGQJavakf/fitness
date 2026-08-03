import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import { summarizeWorkout, type WorkoutFlowState } from '../../../application/workoutFlow'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'

import './index.scss'

const application = getWeappApplication()
type Summary = ReturnType<typeof summarizeWorkout>

export default function WorkoutSummaryPage() {
  const requestedSessionId = application.routeParameter('sessionId')
  const [summary, setSummary] = useState<Summary | null>(null)
  const [message, setMessage] = useState('正在整理本次训练…')
  const [settling, setSettling] = useState(false)
  const [settled, setSettled] = useState(false)
  const [summarySessionId, setSummarySessionId] = useState(requestedSessionId)
  const [aiSummary, setAiSummary] = useState('')
  const [summarizing, setSummarizing] = useState(false)
  const autoSettlementStarted = useRef(false)
  const completionInFlight = useRef(false)

  useEffect(() => {
    if (requestedSessionId) {
      setMessage('训练记录已保存，正在生成个性化回顾。')
      void loadAiSummary(requestedSessionId)
      return
    }
    void application.workouts.load()
      .then(async (state) => {
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
      })
      .catch(() => setMessage('训练记录暂时无法读取，请稍后重试。'))
  }, [])

  async function loadAiSummary(sessionId: string): Promise<void> {
    if (summarizing) return
    setSummarizing(true)
    try {
      application.telemetry.track('ai_summary_requested', { purpose: 'workout_summary' })
      const generated = await application.requestWorkoutSummary(sessionId)
      setAiSummary(generated.content)
      setMessage('训练回顾已准备好。')
      application.telemetry.track('ai_summary_viewed', { source: generated.status === 'DEGRADED' ? 'template' : 'provider' })
    } catch {
      setAiSummary('个性化回顾暂不可用。训练记录已经保存，后续计划调整不受影响。')
      setMessage('训练记录已保存。')
      application.telemetry.track('ai_summary_failed', { reason: 'unavailable' })
    } finally {
      setSummarizing(false)
    }
  }

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
      setMessage(result.complete ? '本次训练已完成并保存。' : '已保存完成的部分；本次不会据此提高训练重量。')
      setSettled(true)
      setSummarySessionId(result.session.id)
      application.telemetry.track(result.complete ? 'workout_completed' : 'workout_aborted', {
        completedSetCount: nextSummary.completedWorkSets,
      })
      await loadAiSummary(result.session.id)
    } catch {
      setMessage('训练尚未同步完成，已记录内容仍会保留；请检查网络后重新保存。')
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
          <View className='summary-metric'>
            <Text className='summary-metric__value data-number'>{summary.failedSets + summary.skippedSets}</Text>
            <Text className='summary-metric__unit'>组</Text>
            <Text className='summary-metric__label'>未完成或跳过</Text>
          </View>
        </View>
      ) : (
        <View className='surface-card empty-state summary-empty'>
          <View className='summary-empty__mark' />
          <Text className='section-title'>{requestedSessionId ? '正在读取训练回顾' : '还没有训练记录'}</Text>
          <Text className='subtitle'>{requestedSessionId ? '稍等片刻，个性化回顾生成后会显示在这里。' : '从计划页开始训练，完成后就能看到表现总结。'}</Text>
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
              <Text className='summary-coach__eyebrow'>AI COACHING NOTE</Text>
              <Text className='summary-coach__title'>本次训练建议</Text>
            </View>
          </View>
          <Text className='summary-coach__content'>{summarizing ? '正在结合训练记录生成回顾…' : aiSummary}</Text>
        </View>
      )}

      <View className='action-row action-row--sticky summary-actions'>
        {summary && !settled && (
          <Button
            className={summary.complete ? 'primary-action' : 'secondary-action'}
            loading={settling}
            disabled={settling}
            onClick={() => void settle()}
          >
            {settling ? '正在自动保存训练' : summary.complete ? '重试保存训练' : '保存并提前结束'}
          </Button>
        )}
        {summarySessionId && (
          <Button
            className='secondary-action'
            disabled={summarizing}
            onClick={() => void loadAiSummary(summarySessionId)}
          >
            {summarizing ? '正在生成回顾' : '重新生成 AI 总结'}
          </Button>
        )}
        <Button className='summary-actions__history' onClick={() => void application.navigation.replace('HISTORY')}>查看训练进展</Button>
      </View>
    </View>
  )
}
