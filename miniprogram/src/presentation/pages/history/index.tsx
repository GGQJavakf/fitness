import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import { toWorkoutHistoryCard, type WorkoutHistoryCard } from '../../../application/history'
import {
  toProgressionCard,
  createRecommendationActionGate,
  type ProgressionRecommendationData,
} from '../../../application/progression'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import ProgressionCard from '../../components/progression-card'

import './index.scss'

const application = getWeappApplication()

export default function HistoryPage() {
  const [items, setItems] = useState<WorkoutHistoryCard[]>([])
  const [cursor, setCursor] = useState<string | undefined>()
  const [message, setMessage] = useState('正在读取训练历史…')
  const [loading, setLoading] = useState(false)
  const [recommendations, setRecommendations] = useState<ProgressionRecommendationData[]>([])
  const [recommendationMessage, setRecommendationMessage] = useState('正在读取进阶建议…')
  const [busyRecommendationId, setBusyRecommendationId] = useState<string>()
  const recommendationActions = useRef(createRecommendationActionGate()).current

  async function load(nextCursor?: string): Promise<void> {
    if (loading) return
    setLoading(true)
    try {
      const page = await application.listWorkoutHistory(nextCursor)
      const cards = page.items.map(toWorkoutHistoryCard)
      setItems((current) => nextCursor ? [...current, ...cards] : cards)
      setCursor(page.nextCursor)
      setMessage(cards.length || nextCursor ? '训练事实来自服务端记录。' : '还没有已完成或提前结束的训练。')
    } catch {
      setMessage('历史暂时无法加载，请检查网络后重试；本地训练草稿不会被删除。')
    } finally {
      setLoading(false)
    }
  }

  async function loadRecommendations(): Promise<void> {
    try {
      const values = await application.listProgressionRecommendations()
      setRecommendations([...values])
      setRecommendationMessage(values.length ? '建议不会自动改变计划，由你决定是否采纳。' : '当前没有待处理建议。')
    } catch {
      setRecommendationMessage('进阶建议暂时无法加载，训练记录不受影响。')
    }
  }

  async function applyRecommendation(id: string, acceptedWeightKg: number): Promise<void> {
    if (!recommendationActions.tryStart(id)) return
    setBusyRecommendationId(id)
    try {
      const plan = await application.loadActivePlan()
      if (!plan) throw new Error('active plan is missing')
      await application.applyProgressionRecommendation(
        id,
        plan.activeVersion.versionNumber,
        acceptedWeightKg,
        `progression-${id}-${Date.now()}`,
      )
      setRecommendations((current) => current.filter((item) => item.id !== id))
      setRecommendationMessage('建议已采纳并生成新的计划版本。')
    } catch {
      setRecommendationMessage('建议未生效；请刷新活动计划后重试，锁定字段不会被覆盖。')
    } finally {
      recommendationActions.finish(id)
      setBusyRecommendationId(undefined)
    }
  }

  async function dismissRecommendation(id: string): Promise<void> {
    if (!recommendationActions.tryStart(id)) return
    setBusyRecommendationId(id)
    try {
      await application.dismissProgressionRecommendation(id)
      setRecommendations((current) => current.filter((item) => item.id !== id))
      setRecommendationMessage('建议已忽略，计划保持不变。')
    } catch {
      setRecommendationMessage('建议状态更新失败，请稍后重试。')
    } finally {
      recommendationActions.finish(id)
      setBusyRecommendationId(undefined)
    }
  }

  useEffect(() => {
    void load()
    void loadRecommendations()
  }, [])

  return <View className='screen'>
    <View className='card'><Text className='title'>进阶建议</Text><Text className='subtitle'>{recommendationMessage}</Text></View>
    {recommendations.map((item) => {
      const card = toProgressionCard(item)
      return <ProgressionCard
        key={card.id}
        card={card}
        busy={busyRecommendationId === card.id}
        onApply={(weight) => applyRecommendation(card.id, weight)}
        onDismiss={() => dismissRecommendation(card.id)}
        onOpenTrend={() => application.navigation.open('EXERCISE_TREND', { exerciseCode: card.exerciseCode })}
      />
    })}
    <View className='card'><Text className='title'>训练历史</Text><Text className='subtitle'>{message}</Text></View>
    {items.map((item) => <View className='card history-card' key={item.id}>
      <View className='history-row'><Text className='section-title'>{item.title}</Text>
        <Text className={item.incomplete ? 'history-status history-status--incomplete' : 'history-status'}>{item.statusLabel}</Text></View>
      <Text>{item.factsLabel}</Text><Text className='subtitle'>{item.timeLabel}</Text>
    </View>)}
    {cursor && <Button className='secondary-action' disabled={loading} onClick={() => void load(cursor)}>{loading ? '加载中…' : '加载更多'}</Button>}
    {!cursor && message.includes('无法加载') && <Button className='secondary-action' disabled={loading} onClick={() => void load()}>重试</Button>}
  </View>
}
