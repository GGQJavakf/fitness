import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { exerciseDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

export default function PlanCandidatesPage() {
  const [candidate] = useState(() => application.getCandidate())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [explanation, setExplanation] = useState(candidate?.explanationMessage ?? '')
  const exerciseCount = candidate?.days.reduce((total, day) => total + day.exercises.length, 0) ?? 0

  useEffect(() => {
    if (!candidate?.candidateId) return
    let active = true
    void application.requestPlanExplanation(candidate.candidateId)
      .then((result) => { if (active) setExplanation(result.content) })
      .catch(() => { /* The rule template already shown remains authoritative. */ })
    return () => { active = false }
  }, [candidate?.candidateId])

  async function startFirstWorkout(): Promise<void> {
    setBusy(true)
    setError('')
    try {
      const activePlan = await application.activateCandidate()
      application.telemetry.track('plan_confirmed', {
        versionNumber: activePlan.activeVersion.versionNumber,
      })
      await application.navigation.replace('WORKOUT_PREPARE')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '科学计划暂时无法启用，请稍后重试')
    } finally {
      setBusy(false)
    }
  }

  async function adjustCandidate(): Promise<void> {
    if (candidate?.action?.route === 'ONBOARDING_EQUIPMENT') {
      application.resumeOnboarding(candidate.action.route)
    }
    await application.navigation.replace('ONBOARDING')
  }

  if (!candidate) {
    return (
      <View className='recommendation-page screen'>
        <View className='recommendation-empty'>
          <Text className='recommendation-empty__title'>推荐方案已失效</Text>
          <Text className='recommendation-empty__description'>请重新完成训练档案，系统不会使用过期或未经校验的计划。</Text>
          <Button className='primary-action' onClick={() => void application.navigation.replace('ONBOARDING')}>返回建档</Button>
        </View>
      </View>
    )
  }

  return (
    <View className='recommendation-page screen'>
      <View className='recommendation-hero'>
        <View className='recommendation-hero__orbit recommendation-hero__orbit--large' />
        <View className='recommendation-hero__orbit recommendation-hero__orbit--small' />
        <View className='recommendation-hero__brand'>
          <View className='recommendation-hero__mark'>
            <View className='recommendation-hero__mark-core' />
          </View>
          <Text className='recommendation-hero__eyebrow'>AI SCIENTIFIC PLAN</Text>
        </View>
        <Text className='recommendation-hero__title'>
          {candidate.status === 'READY' ? '你的科学训练方案' : '需要补充训练条件'}
        </Text>
        <Text className='recommendation-hero__subtitle'>
          {candidate.status === 'READY'
            ? '已结合你的目标、经验、训练频率与器械条件生成，可直接开始。'
            : '当前信息暂时无法组成安全有效的训练方案。'}
        </Text>
        {candidate.status === 'READY'
          ? (
            <View className='recommendation-hero__summary'>
              <View className='recommendation-hero__metric'>
                <Text className='recommendation-hero__metric-value'>{candidate.days.length}</Text>
                <Text className='recommendation-hero__metric-label'>每周训练日</Text>
              </View>
              <View className='recommendation-hero__metric-divider' />
              <View className='recommendation-hero__metric'>
                <Text className='recommendation-hero__metric-value'>{exerciseCount}</Text>
                <Text className='recommendation-hero__metric-label'>计划动作</Text>
              </View>
              <View className='recommendation-hero__metric-divider' />
              <View className='recommendation-hero__metric recommendation-hero__metric--wide'>
                <Text className='recommendation-hero__metric-value'>持续</Text>
                <Text className='recommendation-hero__metric-label'>训练后再调整</Text>
              </View>
            </View>
          )
          : <View className='recommendation-hero__error'>{candidate.reason}</View>}
      </View>

      {candidate.status === 'READY' && (
        <View className={explanation.includes('暂不可用') ? 'recommendation-reason recommendation-reason--degraded' : 'recommendation-reason'}>
          <Text className='recommendation-reason__label'>为什么这样安排</Text>
          <Text className='recommendation-reason__text'>{explanation}</Text>
        </View>
      )}

      {candidate.days.map((day, dayIndex) => (
        <View key={day.code} className='recommendation-day'>
          <View className='recommendation-day__heading'>
            <Text className='recommendation-day__index'>{String(dayIndex + 1).padStart(2, '0')}</Text>
            <View className='recommendation-day__title-group'>
              <Text className='recommendation-day__title'>{day.name}</Text>
              <Text className='recommendation-day__count'>{day.exercises.length} 个动作 · 按顺序完成</Text>
            </View>
          </View>
          {day.exercises.map((exercise) => (
            <View key={exercise.exerciseCode} className='recommendation-exercise'>
              <View className='recommendation-exercise__heading'>
                <Text className='recommendation-exercise__name'>{exerciseDisplayName(exercise.exerciseCode)}</Text>
                <Text className='recommendation-exercise__prescription'>{exercise.workSets} 组 · {exercise.repRange}</Text>
              </View>
              <View className='recommendation-exercise__meta'>
                <Text className='recommendation-exercise__tag'>{exercise.restLabel}</Text>
                <Text className='recommendation-exercise__tag'>{exercise.weightLabel}</Text>
              </View>
            </View>
          ))}
        </View>
      ))}

      {error && <View className='error-box'>{error}</View>}
      {candidate.canContinue && (
        <View className='recommendation-feedback'>
          <View className='recommendation-feedback__line' />
          <View>
            <Text className='recommendation-feedback__title'>不用先懂所有训练参数</Text>
            <Text className='recommendation-feedback__text'>完成训练时记录次数、余力和身体感受，系统会据此给出下一步进阶建议。</Text>
          </View>
        </View>
      )}
      <View className='recommendation-actions'>
        {candidate.canContinue
          ? (
            <Button
              className='recommendation-actions__primary'
              loading={busy}
              disabled={busy}
              onClick={() => void startFirstWorkout()}
            >
              开始第一次训练
            </Button>
          )
          : <Button className='recommendation-actions__primary' onClick={() => void adjustCandidate()}>{candidate.action?.label ?? '返回调整档案'}</Button>}
        {candidate.canContinue && <Text className='recommendation-actions__note'>计划关键数值由科学规则生成，训练记录不会被后续调整覆盖。</Text>}
      </View>
    </View>
  )
}
