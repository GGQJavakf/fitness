import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import { summarizeWorkout } from '../../../application/workoutFlow'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'

import './index.scss'

const application = getWeappApplication()
type Summary = ReturnType<typeof summarizeWorkout>

export default function WorkoutSummaryPage() {
  const requestedSessionId = application.routeParameter('sessionId')
  const [summary, setSummary] = useState<Summary | null>(null)
  const [message, setMessage] = useState('完成训练前会先同步本地事实。')
  const [settling, setSettling] = useState(false)
  const [settled, setSettled] = useState(false)
  const [summarySessionId, setSummarySessionId] = useState(requestedSessionId)
  const [aiSummary, setAiSummary] = useState('')
  useEffect(() => {
    if (requestedSessionId) {
      setMessage('可重新生成这次训练的 AI 总结；训练事实不会被修改。')
      void loadAiSummary(requestedSessionId)
      return
    }
    void application.workouts.load().then((state) => setSummary(state ? summarizeWorkout(state) : null))
  }, [])

  async function loadAiSummary(sessionId: string): Promise<void> {
    try {
      application.telemetry.track('ai_summary_requested', { purpose: 'workout_summary' })
      const generated = await application.requestWorkoutSummary(sessionId)
      setAiSummary(generated.content)
      application.telemetry.track('ai_summary_viewed', { source: generated.status === 'DEGRADED' ? 'template' : 'provider' })
    } catch {
      setAiSummary('AI 总结暂不可用；训练事实和规则建议不受影响。')
      application.telemetry.track('ai_summary_failed', { reason: 'unavailable' })
    }
  }

  async function settle(): Promise<void> {
    const state = await application.workouts.load()
    if (!state || !summary || settling || settled) return
    setSettling(true)
    try {
      const result = await application.workouts.complete(state, summary.complete ? 'FULL' : 'EARLY_END')
      setMessage(result.complete ? '训练已完整结算。' : '训练已提前结束；已完成组已保留，不会自动加重。')
      setSettled(true)
      setSummarySessionId(result.session.id)
      application.telemetry.track(result.complete ? 'workout_completed' : 'workout_aborted', {
        completedSetCount: summary.completedWorkSets,
      })
      await loadAiSummary(result.session.id)
    } catch {
      setMessage('结算暂未成功；本地事实仍保留，请检查网络或同步冲突后重试。')
    } finally {
      setSettling(false)
    }
  }
  return <View className='screen'><View className='card'>
    <Text className='title'>本次训练事实</Text>
    {summary ? <>
      <Text className='summary-number'>{summary.completedWorkSets} 组</Text>
      <Text>有效正式组容量：{summary.completedVolumeKg} KG·次</Text>
      <Text>失败 {summary.failedSets} 组 · 跳过 {summary.skippedSets} 组</Text>
      <View className={summary.complete ? 'info-box' : 'warning-box'}>{summary.complete ? '训练已完整记录。' : '训练尚不完整，不会据此自动加重。'}</View>
    </> : <Text className='subtitle'>{requestedSessionId ? '正在读取历史训练总结。' : '暂无可总结的训练草稿。'}</Text>}
    <Text className='subtitle'>{message}</Text>
    {aiSummary && <View className='info-box'>{aiSummary}</View>}
  </View><View className='action-row action-row--sticky'>{summary && !settled && <Button className={summary.complete ? 'primary-action' : 'danger-action'} disabled={settling} onClick={() => void settle()}>{settling ? '正在同步并结算…' : summary.complete ? '完成训练' : '确认提前结束'}</Button>}{summarySessionId && <Button className='secondary-action' onClick={() => void loadAiSummary(summarySessionId)}>重新生成 AI 总结</Button>}<Button className='secondary-action' onClick={() => void application.navigation.open('HISTORY')}>查看训练历史</Button></View></View>
}
