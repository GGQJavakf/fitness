import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import { toWorkoutHistoryCard, type WorkoutHistoryCard } from '../../../application/history'
import {
  toProgressionCard,
  progressionApplyIdempotencyKey,
  type ProgressionRecommendationData,
} from '../../../application/progression'
import { ApplicationError } from '../../../application/errors'
import { summarizeWorkout } from '../../../application/workoutFlow'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import MainNavigation from '../../components/main-navigation'
import ProgressionCard from '../../components/progression-card'
import { trainingDayDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

function isUncertainRecommendationOutcome(error: unknown): boolean {
  return error instanceof ApplicationError
    && ['NETWORK_ERROR', 'INVALID_RESPONSE', 'INTERNAL_ERROR'].includes(error.code)
}

export default function HistoryPage() {
  const [items, setItems] = useState<WorkoutHistoryCard[]>([])
  const [cursor, setCursor] = useState<string | undefined>()
  const [message, setMessage] = useState('正在读取训练记录…')
  const [loading, setLoading] = useState(false)
  const [pendingWorkout, setPendingWorkout] = useState<ReturnType<typeof summarizeWorkout> | null>(null)
  const [recommendations, setRecommendations] = useState<ProgressionRecommendationData[]>([])
  const [recommendationCursor, setRecommendationCursor] = useState<string | undefined>()
  const [recommendationRetryCursor, setRecommendationRetryCursor] = useState<string | undefined>()
  const [recommendationMessage, setRecommendationMessage] = useState('正在分析近期表现…')
  const [busyRecommendationId, setBusyRecommendationId] = useState<string>()
  const recommendationActionActiveRef = useRef(false)
  const recommendationApplyAttempts = useRef(new Map<string, {
    expectedVersion: number
    acceptedWeightKg: number
    idempotencyKey: string
  }>()).current
  const historyRequestActiveRef = useRef(false)
  const historyRequestIdRef = useRef(0)
  const recommendationRequestActiveRef = useRef(false)
  const recommendationRequestIdRef = useRef(0)
  const mountedRef = useRef(true)
  const lifecycleEpochRef = useRef(0)

  async function load(nextCursor?: string): Promise<void> {
    if (historyRequestActiveRef.current) return
    historyRequestActiveRef.current = true
    const requestId = ++historyRequestIdRef.current
    setLoading(true)
    try {
      const page = await application.listWorkoutHistory(nextCursor)
      if (requestId !== historyRequestIdRef.current) return
      const cards = page.items.map(toWorkoutHistoryCard)
      setItems((current) => nextCursor ? [...current, ...cards] : cards)
      setCursor(page.nextCursor)
      setMessage(cards.length || nextCursor ? '最近训练记录已更新。' : '完成第一次训练后，这里会记录你的变化。')
    } catch {
      if (requestId !== historyRequestIdRef.current) return
      setMessage('训练记录暂时无法加载，已保存的训练不会丢失。')
    } finally {
      if (requestId === historyRequestIdRef.current) {
        historyRequestActiveRef.current = false
        setLoading(false)
      }
    }
  }

  async function loadPendingWorkout(): Promise<void> {
    try {
      const local = await application.workouts.load()
      if (!mountedRef.current) return
      if (!local) {
        setPendingWorkout(null)
        return
      }
      const summary = summarizeWorkout(local)
      setPendingWorkout(summary.complete && local.syncStatus !== 'SYNCED' ? summary : null)
    } catch {
      if (mountedRef.current) setPendingWorkout(null)
    }
  }

  async function loadRecommendations(nextCursor?: string): Promise<void> {
    if (!mountedRef.current) return
    if (recommendationRequestActiveRef.current) return
    recommendationRequestActiveRef.current = true
    const requestId = ++recommendationRequestIdRef.current
    setRecommendationMessage('正在分析近期表现…')
    try {
      const page = await application.listProgressionRecommendations(nextCursor)
      if (requestId !== recommendationRequestIdRef.current) return
      setRecommendations((current) => {
        if (!nextCursor) return [...page.items]
        const existing = new Set(current.map((item) => item.id))
        return [...current, ...page.items.filter((item) => !existing.has(item.id))]
      })
      setRecommendationCursor(page.nextCursor)
      setRecommendationRetryCursor(undefined)
      page.items.forEach((value) => application.telemetry.track('progression_recommended', {
        decision: value.decision.toLowerCase() as 'increase' | 'keep' | 'reduce' | 'review',
      }))
      setRecommendationMessage(page.items.length || nextCursor
        ? '系统已结合连续训练反馈生成可执行建议。'
        : '继续训练，证据充分后才会显示需要处理的调整。')
    } catch {
      if (requestId !== recommendationRequestIdRef.current) return
      setRecommendationRetryCursor(nextCursor)
      setRecommendationMessage('暂时无法读取调整建议，训练记录不受影响。')
    } finally {
      if (requestId === recommendationRequestIdRef.current) {
        recommendationRequestActiveRef.current = false
      }
    }
  }

  async function reconcileRecommendations(lifecycleEpoch: number): Promise<void> {
    if (!mountedRef.current || lifecycleEpoch !== lifecycleEpochRef.current) return
    const requestId = ++recommendationRequestIdRef.current
    recommendationRequestActiveRef.current = true
    try {
      const page = await application.listProgressionRecommendations()
      if (!mountedRef.current
        || lifecycleEpoch !== lifecycleEpochRef.current
        || requestId !== recommendationRequestIdRef.current) return
      setRecommendations([...page.items])
      setRecommendationCursor(page.nextCursor)
      setRecommendationRetryCursor(undefined)
    } catch {
      // The original action error remains visible; reconciliation is best-effort only.
    } finally {
      if (requestId === recommendationRequestIdRef.current) {
        recommendationRequestActiveRef.current = false
      }
    }
  }

  async function applyRecommendation(id: string, acceptedWeightKg: number): Promise<void> {
    if (recommendationActionActiveRef.current || !mountedRef.current) return
    recommendationActionActiveRef.current = true
    const lifecycleEpoch = lifecycleEpochRef.current
    setBusyRecommendationId(id)
    try {
      let attempt = recommendationApplyAttempts.get(id)
      if (!attempt) {
        const plan = await application.loadActivePlan()
        if (!plan) throw new Error('active plan is missing')
        attempt = {
          expectedVersion: plan.activeVersion.versionNumber,
          acceptedWeightKg,
          idempotencyKey: progressionApplyIdempotencyKey(
            id,
            acceptedWeightKg,
            plan.activeVersion.versionNumber,
          ),
        }
        recommendationApplyAttempts.set(id, attempt)
      }
      await application.applyProgressionRecommendation(
        id,
        attempt.expectedVersion,
        attempt.acceptedWeightKg,
        attempt.idempotencyKey,
      )
      if (!mountedRef.current || lifecycleEpoch !== lifecycleEpochRef.current) return
      const applied = recommendations.find((item) => item.id === id)
      application.telemetry.track('progression_applied', {
        decision: applied && applied.recommendedWeightKg < applied.currentWeightKg ? 'reduce' : 'increase',
        modified: Boolean(applied && acceptedWeightKg !== applied.recommendedWeightKg),
      })
      setRecommendations((current) => current.filter((item) => item.id !== id))
      recommendationApplyAttempts.delete(id)
      setRecommendationMessage('建议已采用，新的计划从下一次训练开始生效。')
    } catch (error) {
      const uncertainOutcome = isUncertainRecommendationOutcome(error)
      if (!uncertainOutcome) recommendationApplyAttempts.delete(id)
      if (!mountedRef.current || lifecycleEpoch !== lifecycleEpochRef.current) return
      setRecommendationMessage(error instanceof ApplicationError && error.code === 'VERSION_CONFLICT'
        ? '计划已在其他位置更新，请重试，系统会按最新版本重新提交。'
        : '这次调整暂未确认，请稍后重试；系统会先核对当前计划状态。')
      if (uncertainOutcome) await reconcileRecommendations(lifecycleEpoch)
    } finally {
      recommendationActionActiveRef.current = false
      if (mountedRef.current && lifecycleEpoch === lifecycleEpochRef.current) {
        setBusyRecommendationId(undefined)
      }
    }
  }

  async function dismissRecommendation(id: string): Promise<void> {
    if (recommendationActionActiveRef.current || !mountedRef.current) return
    recommendationActionActiveRef.current = true
    const lifecycleEpoch = lifecycleEpochRef.current
    setBusyRecommendationId(id)
    try {
      await application.dismissProgressionRecommendation(id)
      if (!mountedRef.current || lifecycleEpoch !== lifecycleEpochRef.current) return
      const dismissed = recommendations.find((item) => item.id === id)
      application.telemetry.track('progression_dismissed', {
        decision: (dismissed?.decision.toLowerCase() as 'increase' | 'keep' | 'reduce' | 'review' | undefined) ?? 'review',
      })
      setRecommendations((current) => current.filter((item) => item.id !== id))
      setRecommendationMessage('已保留当前计划，后续会继续结合训练反馈判断。')
    } catch (error) {
      if (!mountedRef.current || lifecycleEpoch !== lifecycleEpochRef.current) return
      setRecommendationMessage('暂时无法确认建议状态，请稍后重试。')
      if (isUncertainRecommendationOutcome(error)) await reconcileRecommendations(lifecycleEpoch)
    } finally {
      recommendationActionActiveRef.current = false
      if (mountedRef.current && lifecycleEpoch === lifecycleEpochRef.current) {
        setBusyRecommendationId(undefined)
      }
    }
  }

  useEffect(() => {
    mountedRef.current = true
    void load()
    void loadPendingWorkout()
    void loadRecommendations()
    return () => {
      mountedRef.current = false
      lifecycleEpochRef.current += 1
      recommendationActionActiveRef.current = false
      historyRequestIdRef.current += 1
      historyRequestActiveRef.current = false
      recommendationRequestIdRef.current += 1
      recommendationRequestActiveRef.current = false
    }
  }, [])

  return (
    <View className='screen screen--with-nav history-page'>
      <View className='page-hero history-hero'>
        <Text className='page-hero__eyebrow'>YOUR PROGRESS</Text>
        <Text className='page-hero__title'>训练进展</Text>
        <Text className='page-hero__description'>看见每一次完成，也让后续计划更贴合身体反馈。</Text>
        <View className='history-hero__metrics'>
          <View>
            <Text className='history-hero__value data-number'>{items.length + (pendingWorkout ? 1 : 0)}</Text>
            <Text className='history-hero__label'>次训练记录</Text>
          </View>
          <View className='history-hero__divider' />
          <View>
            <Text className='history-hero__value data-number'>{recommendations.length}</Text>
            <Text className='history-hero__label'>条已加载建议</Text>
          </View>
        </View>
      </View>

      <View className='history-section'>
        <View className='section-heading'>
          <Text className='section-heading__title'>下一步建议</Text>
          <Text className='section-heading__meta'>基于训练反馈</Text>
        </View>
        <View className='history-section__message'>{recommendationMessage}</View>
        {recommendationMessage.includes('无法读取') && (
          <Button
            className='secondary-action'
            onClick={() => void loadRecommendations(recommendationRetryCursor)}
          >重试调整建议</Button>
        )}
        {recommendations.map((item) => {
          const card = toProgressionCard(item)
          return (
            <ProgressionCard
              key={card.id}
              card={card}
              busy={busyRecommendationId === card.id}
              actionsDisabled={busyRecommendationId !== undefined}
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
        {recommendationCursor && !recommendationMessage.includes('无法读取') && (
          <Button
            className='secondary-action'
            onClick={() => void loadRecommendations(recommendationCursor)}
          >查看更多建议</Button>
        )}
      </View>

      <View className='history-section'>
        <View className='section-heading'>
          <Text className='section-heading__title'>训练记录</Text>
          <Text className='section-heading__meta'>{message}</Text>
        </View>
        {pendingWorkout && (
          <View className='surface-card history-card history-card--pending'>
            <View className='history-card__index data-number'>待</View>
            <View className='history-card__content'>
              <View className='history-card__heading'>
                <Text className='history-card__title'>待同步训练</Text>
                <Text className='history-status history-status--incomplete'>本地保留</Text>
              </View>
              <Text className='history-card__facts'>
                {pendingWorkout.usesExternalLoad
                  ? `${pendingWorkout.completedWorkSets} 组 · ${pendingWorkout.completedVolumeKg} KG·次`
                  : `${pendingWorkout.completedWorkSets} 组 · 共 ${pendingWorkout.completedReps} 次`}
              </Text>
              <Text className='history-card__time'>尚未写入服务端历史，本地训练记录仍然保留。</Text>
              <Button className='history-card__action' onClick={() => void application.navigation.open('WORKOUT_SUMMARY')}>继续保存这次训练</Button>
            </View>
          </View>
        )}
        {items.map((item, index) => (
          <View className='surface-card history-card' key={item.id}>
            <View className='history-card__index data-number'>{String(index + 1).padStart(2, '0')}</View>
            <View className='history-card__content'>
              <View className='history-card__heading'>
                <Text className='history-card__title'>{item.title === item.trainingDayCode
                  ? trainingDayDisplayName(item.title)
                  : item.title}</Text>
                <Text className={item.incomplete ? 'history-status history-status--incomplete' : 'history-status'}>{item.statusLabel}</Text>
              </View>
              <Text className='history-card__facts'>{item.factsLabel}</Text>
              <Text className='history-card__time'>{item.timeLabel}</Text>
              <Button className='history-card__action' onClick={() => void application.navigation.open('WORKOUT_SUMMARY', { sessionId: item.id })}>查看训练回顾</Button>
            </View>
          </View>
        ))}
      </View>

      {!loading && items.length === 0 && !pendingWorkout && !message.includes('无法加载') && (
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
