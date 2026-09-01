import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import { getPlanningApplication } from '../../../platform/weapp/featureRoots/planningCompositionRoot'
import { exerciseDisplayName, trainingSplitDisplayName, weekdayDisplayName } from '../../copy'

function sourceEyebrow(source: string | undefined): string {
  if (source === 'AI_PERSONALIZED') return 'AI PERSONALIZED PLAN'
  if (source === 'SYSTEM_PRESET') return 'SYSTEM PERSONAL PRESET'
  return 'RULE-BASED TRAINING PLAN'
}

function sourceTitle(source: string | undefined): string {
  if (source === 'AI_PERSONALIZED') return '你的 AI 个性化训练方案'
  if (source === 'SYSTEM_PRESET') return '你的系统预制训练方案'
  return '你的规则生成训练方案'
}

function sourceDescription(source: string | undefined): string {
  if (source === 'AI_PERSONALIZED') return 'AI 已结合目标、经验、训练频率、器械与额外偏好生成，并通过服务端规则校验。'
  if (source === 'SYSTEM_PRESET') return '动作顺序、组次、休息、热身、超级组和进阶规则均按固定版本载入；确认前不会覆盖当前计划。'
  return '已按你的档案与器械由确定性规则引擎生成，并通过安全校验。'
}

export default function PlanCandidatesPage() {
  const application = getPlanningApplication()
  const [candidate] = useState(() => application.getCandidate())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [explanation, setExplanation] = useState(candidate?.explanationMessage ?? '')
  const actionInFlight = useRef(false)
  const exerciseCount = candidate?.days.reduce((total, day) => total + day.exercises.length, 0) ?? 0

  useEffect(() => {
    if (!candidate?.candidateId || candidate.generationSource !== 'AI_PERSONALIZED') return
    let active = true
    void application.requestPlanExplanation(candidate.candidateId)
      .then((result) => { if (active) setExplanation(result.content) })
      .catch(() => { /* The rule template already shown remains authoritative. */ })
    return () => { active = false }
  }, [candidate?.candidateId, candidate?.generationSource])

  async function startFirstWorkout(): Promise<void> {
    if (actionInFlight.current) return
    actionInFlight.current = true
    setBusy(true)
    setError('')
    try {
      await application.activateCandidateAndOpenWorkoutPreparation()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '科学计划暂时无法启用，请稍后重试')
    } finally {
      actionInFlight.current = false
      setBusy(false)
    }
  }

  async function adjustCandidate(): Promise<void> {
    if (candidate?.action) {
      application.resumeOnboarding(candidate.action.route)
    }
    await application.navigation.replace('ONBOARDING')
  }

  async function editCandidate(): Promise<void> {
    if (actionInFlight.current) return
    actionInFlight.current = true
    setBusy(true)
    setError('')
    try {
      await application.activateCandidateAndOpenEditor()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '计划编辑器暂时无法打开，请稍后重试')
    } finally {
      actionInFlight.current = false
      setBusy(false)
    }
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
          <Text className='recommendation-hero__eyebrow'>
            {sourceEyebrow(candidate.generationSource)}
          </Text>
        </View>
        <Text className='recommendation-hero__title'>
          {candidate.status === 'READY'
            ? sourceTitle(candidate.generationSource)
            : '需要补充训练条件'}
        </Text>
        <Text className='recommendation-hero__subtitle'>
          {candidate.status === 'READY'
            ? sourceDescription(candidate.generationSource)
            : '当前信息暂时无法组成安全有效的训练方案。'}
        </Text>
        {candidate.generationLabel && (
          <Text className='recommendation-hero__source'>{candidate.generationLabel}</Text>
        )}
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
      {candidate.notices.map((notice) => (
        <View className='recommendation-notice' key={notice}>{notice}</View>
      ))}
      {candidate.status === 'READY' && (
        <>
          <View className='recommendation-reason'>
            <Text className='recommendation-reason__label'>计划名称</Text>
            <Text className='recommendation-reason__text'>{candidate.name}</Text>
            {candidate.trainingSplit && (
              <Text className='recommendation-reason__text'>训练分化：{trainingSplitDisplayName(candidate.trainingSplit)}</Text>
            )}
          </View>
          <View className='recommendation-notes'>
            <Text className='recommendation-notes__title'>执行规则</Text>
            {candidate.executionRules.length > 0
              ? candidate.executionRules.map((rule, index) => (
                  <Text className='recommendation-notes__item' key={`execution-rule-${index}`}>• {rule}</Text>
                ))
              : <Text className='recommendation-notes__item'>当前计划未提供额外执行规则。</Text>}
          </View>
          <View className='recommendation-notes'>
            <Text className='recommendation-notes__title'>进阶规则</Text>
            {candidate.progressionRules.length > 0
              ? candidate.progressionRules.map((rule, index) => (
                  <Text className='recommendation-notes__item' key={`progression-rule-${index}`}>• {rule}</Text>
                ))
              : <Text className='recommendation-notes__item'>当前计划未提供额外进阶规则。</Text>}
          </View>
        </>
      )}

      {candidate.days.map((day, dayIndex) => (
        <View key={day.code} className='recommendation-day'>
          <View className='recommendation-day__heading'>
            <Text className='recommendation-day__index'>{String(dayIndex + 1).padStart(2, '0')}</Text>
            <View className='recommendation-day__title-group'>
              <Text className='recommendation-day__title'>{day.name}</Text>
              <Text className='recommendation-day__count'>
                {[weekdayDisplayName(day.weekday), day.focus, day.estimatedMinutesLabel].filter(Boolean).join(' · ') || `${day.exercises.length} 个动作 · 按顺序完成`}
              </Text>
            </View>
          </View>
          {(day.warmup?.length ?? 0) > 0 && (
            <View className='recommendation-warmup'>
              <Text className='recommendation-warmup__title'>热身</Text>
              {day.warmup?.map((step, index) => (
                <Text className='recommendation-warmup__step' key={`${day.code}-warmup-${index}`}>
                  {index + 1}. {step.instruction}{step.prescription ? ` · ${step.prescription}` : ''}{step.optional ? '（可选）' : ''}
                </Text>
              ))}
            </View>
          )}
          {(day.notes?.length ?? 0) > 0 && (
            <View className='recommendation-notes'>
              <Text className='recommendation-notes__title'>训练提示</Text>
              {day.notes.map((note, index) => (
                <Text className='recommendation-notes__item' key={`${day.code}-note-${index}`}>• {note}</Text>
              ))}
            </View>
          )}
          {day.exercises.map((exercise) => (
            <View key={exercise.exerciseCode} className='recommendation-exercise'>
              <View className='recommendation-exercise__heading'>
                <Text className='recommendation-exercise__name'>{exerciseDisplayName(exercise.exerciseCode)}</Text>
                <Text className='recommendation-exercise__prescription'>{exercise.workSets} 组 · {exercise.repRange}</Text>
              </View>
              <View className='recommendation-exercise__meta'>
                <Text className='recommendation-exercise__tag'>{exercise.restLabel}</Text>
                <Text className='recommendation-exercise__tag'>{exercise.weightLabel}</Text>
                {exercise.targetRirLabel && <Text className='recommendation-exercise__tag'>{exercise.targetRirLabel}</Text>}
                {exercise.eccentricLabel && <Text className='recommendation-exercise__tag'>{exercise.eccentricLabel}</Text>}
                {exercise.perSide && <Text className='recommendation-exercise__tag'>每侧完成</Text>}
                {exercise.executionGroup && (
                  <Text className='recommendation-exercise__tag recommendation-exercise__tag--group'>
                    超级组 {exercise.executionGroup.replace(/^SUPERSET_/, '')} · 第 {exercise.executionOrder} 个动作
                  </Text>
                )}
              </View>
              {exercise.optionalSetDescription && (
                <Text className='recommendation-exercise__optional'>{exercise.optionalSetDescription}</Text>
              )}
              {(exercise.notes?.length ?? 0) > 0 && (
                <View className='recommendation-exercise__notes'>
                  <Text className='recommendation-exercise__notes-title'>动作提示</Text>
                  {exercise.notes.map((note, index) => (
                    <Text className='recommendation-exercise__note' key={`${exercise.exerciseCode}-note-${index}`}>• {note}</Text>
                  ))}
                </View>
              )}
              <Button
                className='recommendation-exercise__guide'
                onClick={() => void application.navigation.open('EXERCISE_DETAIL', {
                  exerciseCode: exercise.exerciseCode,
                })}
              >
                查看动作指导
              </Button>
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
              确认启用并准备训练
            </Button>
          )
          : <Button className='recommendation-actions__primary' onClick={() => void adjustCandidate()}>{candidate.action?.label ?? '返回调整档案'}</Button>}
        <Button
          className='recommendation-actions__edit'
          disabled={busy}
          onClick={() => void application.navigation.open('PLAN_PRESETS')}
        >
          选择系统预设
        </Button>
        {candidate.canContinue && (
          <Button
            className='recommendation-actions__edit'
            disabled={busy}
            onClick={() => void editCandidate()}
          >
            修改训练计划
          </Button>
        )}
        {candidate.canContinue && <Text className='recommendation-actions__note'>修改期间不会启用计划；只有保存时才会创建初始计划，若有调整再生成新版本。取消返回不会创建计划。</Text>}
      </View>
    </View>
  )
}
