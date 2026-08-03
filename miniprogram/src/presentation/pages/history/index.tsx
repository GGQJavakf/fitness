import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import { toWorkoutHistoryCard, type WorkoutHistoryCard } from '../../../application/history'
import {
  toProgressionCard,
  createRecommendationActionGate,
  type ProgressionRecommendationData,
} from '../../../application/progression'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import MainNavigation from '../../components/main-navigation'
import ProgressionCard from '../../components/progression-card'
import { trainingDayDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

export default function HistoryPage() {
  const [items, setItems] = useState<WorkoutHistoryCard[]>([])
  const [cursor, setCursor] = useState<string | undefined>()
  const [message, setMessage] = useState('正在读取训练记录…')
  const [loading, setLoading] = useState(false)
  const [recommendations, setRecommendations] = useState<ProgressionRecommendationData[]>([])
  const [recommendationMessage, setRecommendationMessage] = useState('正在分析近期表现…')
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
      setMessage(cards.length || nextCursor ? '最近训练记录已更新。' : '完成第一次训练后，这里会记录你的变化。')
    } catch {
      setMessage('训练记录暂时无法加载，已保存的训练不会丢失。')
    } finally {
      setLoading(false)
    }
  }

  async function loadRecommendations(): Promise<void> {
    try {
      const values = await application.listProgressionRecommendations()
      setRecommendations([...values])
      values.forEach((value) => application.telemetry.track('progression_recommended', {
        decision: value.decision.toLowerCase() as 'increase' | 'keep' | 'reduce' | 'review',
      }))
      setRecommendationMessage(values.length ? '系统已结合连续训练反馈生成可执行建议。' : '继续训练，证据充分后才会显示需要处理的调整。')
    } catch {
      setRecommendationMessage('暂时无法读取调整建议，训练记录不受影响。')
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
      const applied = recommendations.find((item) => item.id === id)
      application.telemetry.track('progression_applied', {
        decision: applied && applied.recommendedWeightKg < applied.currentWeightKg ? 'reduce' : 'increase',
        modified: Boolean(applied && acceptedWeightKg !== applied.recommendedWeightKg),
      })
      setRecommendations((current) => current.filter((item) => item.id !== id))
      setRecommendationMessage('建议已采用，新的计划从下一次训练开始生效。')
    } catch {
      setRecommendationMessage('这次调整暂未生效，请稍后重试；你的原计划保持不变。')
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
      const dismissed = recommendations.find((item) => item.id === id)
      application.telemetry.track('progression_dismissed', {
        decision: (dismissed?.decision.toLowerCase() as 'increase' | 'keep' | 'reduce' | 'review' | undefined) ?? 'review',
      })
      setRecommendations((current) => current.filter((item) => item.id !== id))
      setRecommendationMessage('已保留当前计划，后续会继续结合训练反馈判断。')
    } catch {
      setRecommendationMessage('暂时无法更新建议状态，请稍后重试。')
    } finally {
      recommendationActions.finish(id)
      setBusyRecommendationId(undefined)
    }
  }

  useEffect(() => {
    void load()
    void loadRecommendations()
  }, [])

  return (
    <View className='screen screen--with-nav history-page'>
      <View className='page-hero history-hero'>
        <Text className='page-hero__eyebrow'>YOUR PROGRESS</Text>
        <Text className='page-hero__title'>训练进展</Text>
        <Text className='page-hero__description'>看见每一次完成，也让后续计划更贴合身体反馈。</Text>
        <View className='history-hero__metrics'>
          <View>
            <Text className='history-hero__value data-number'>{items.length}</Text>
            <Text className='history-hero__label'>次训练记录</Text>
          </View>
          <View className='history-hero__divider' />
          <View>
            <Text className='history-hero__value data-number'>{recommendations.length}</Text>
            <Text className='history-hero__label'>条待处理建议</Text>
          </View>
        </View>
      </View>

      <View className='history-section'>
        <View className='section-heading'>
          <Text className='section-heading__title'>下一步建议</Text>
          <Text className='section-heading__meta'>基于训练反馈</Text>
        </View>
        <View className='history-section__message'>{recommendationMessage}</View>
        {recommendations.map((item) => {
          const card = toProgressionCard(item)
          return (
            <ProgressionCard
              key={card.id}
              card={card}
              busy={busyRecommendationId === card.id}
              onApply={(weight) => applyRecommendation(card.id, weight)}
              onDismiss={() => dismissRecommendation(card.id)}
              onOpenTrend={() => application.navigation.open('EXERCISE_TREND', { exerciseCode: card.exerciseCode })}
            />
          )
        })}
        {recommendations.length === 0 && (
          <View className='history-recommendation-empty'>
            <View className='history-recommendation-empty__mark' />
            <View>
              <Text className='history-recommendation-empty__title'>当前计划保持稳定</Text>
              <Text className='history-recommendation-empty__description'>完成更多训练后，系统会在证据充分时给出调整建议。</Text>
            </View>
          </View>
        )}
      </View>

      <View className='history-section'>
        <View className='section-heading'>
          <Text className='section-heading__title'>训练记录</Text>
          <Text className='section-heading__meta'>{message}</Text>
        </View>
        {items.map((item, index) => (
          <View className='surface-card history-card' key={item.id}>
            <View className='history-card__index data-number'>{String(index + 1).padStart(2, '0')}</View>
            <View className='history-card__content'>
              <View className='history-card__heading'>
                <Text className='history-card__title'>{trainingDayDisplayName(item.title)}</Text>
                <Text className={item.incomplete ? 'history-status history-status--incomplete' : 'history-status'}>{item.statusLabel}</Text>
              </View>
              <Text className='history-card__facts'>{item.factsLabel}</Text>
              <Text className='history-card__time'>{item.timeLabel}</Text>
              <Button className='history-card__action' onClick={() => void application.navigation.open('WORKOUT_SUMMARY', { sessionId: item.id })}>查看训练回顾</Button>
            </View>
          </View>
        ))}
      </View>

      {!loading && items.length === 0 && !message.includes('无法加载') && (
        <View className='surface-card empty-state history-empty'>
          <View className='history-empty__mark' />
          <Text className='section-title'>从第一次训练开始积累</Text>
          <Text className='subtitle'>完成训练后，这里会记录组数、训练容量和长期变化。</Text>
          <Button className='primary-action' onClick={() => void application.navigation.replace('PLAN')}>开始训练</Button>
        </View>
      )}
      {cursor && <Button className='secondary-action history-load-more' disabled={loading} onClick={() => void load(cursor)}>{loading ? '正在加载' : '查看更多记录'}</Button>}
      {!cursor && message.includes('无法加载') && <Button className='secondary-action history-load-more' disabled={loading} onClick={() => void load()}>重新加载</Button>}

      <MainNavigation current='HISTORY' onNavigate={(destination) => void application.navigation.replace(destination)} />
    </View>
  )
}
