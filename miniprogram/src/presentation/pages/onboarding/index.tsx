import { Button, Text, Textarea, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import type { ExerciseContent } from '../../../application/content'
import {
  DEFAULT_GYM_EQUIPMENT,
  ONBOARDING_STEPS,
  advanceOnboarding,
  goToOnboardingStep,
  previousOnboardingStep,
  updateOnboardingDraft,
  type OnboardingDraft,
} from '../../../application/onboarding'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { experienceDisplayName, goalDisplayName, locationDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()
const primaryFrequencies = [2, 3, 4] as const
const moreFrequencies = [5, 6] as const
const primaryDurations = [30, 45, 60] as const
const moreDurations = [75, 90] as const

function gymEquipment() {
  return DEFAULT_GYM_EQUIPMENT.map((item) => ({
    ...item,
    minIncrement: { ...item.minIncrement },
    availableLevels: item.availableLevels.map((level) => ({ ...level })),
  }))
}

export default function OnboardingPage() {
  const [state, setState] = useState(() => application.resumeOnboarding())
  const [showSafetyDetails, setShowSafetyDetails] = useState(false)
  const [showMoreFrequency, setShowMoreFrequency] = useState(
    () => (state.draft.weeklyFrequency ?? 0) >= 5,
  )
  const [showMoreDuration, setShowMoreDuration] = useState(
    () => (state.draft.sessionMinutes ?? 0) >= 75,
  )
  const [otherEquipmentConfirmed, setOtherEquipmentConfirmed] = useState(
    () => state.draft.location === 'OTHER',
  )
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')
  const [preferenceOptions, setPreferenceOptions] = useState<readonly ExerciseContent[]>([])
  const submittingRef = useRef(false)

  useEffect(() => {
    application.telemetry.track('onboarding_started', { source: state.stepIndex > 0 ? 'resume' : 'new' })
  }, [])

  useEffect(() => {
    let active = true
    void application.listExercises()
      .then((items) => {
        if (active) setPreferenceOptions(items)
      })
      .catch(() => {
        // Optional preference controls stay hidden until exercise content is available.
      })
    void application.getExercisePreferences()
      .then((profile) => {
        if (!active) return
        setState((current) => current.draft.preferencesTouched
          ? current
          : {
              ...current,
              draft: {
                ...current.draft,
                preferences: profile.items.map((item) => ({ ...item })),
              },
            })
      })
      .catch(() => {
        // A first-time user has no preference profile yet.
      })
    return () => {
      active = false
    }
  }, [])

  function patch(patchValue: Partial<OnboardingDraft>): void {
    setState((current) => updateOnboardingDraft(current, patchValue))
    setSubmitError('')
  }

  function next(): void {
    setState((current) => advanceOnboarding(current))
  }

  function selectLocation(location: 'HOME' | 'GYM' | 'OTHER'): void {
    setOtherEquipmentConfirmed(location !== 'OTHER')
    if (location === 'HOME') {
      patch({ location, equipment: [] })
      return
    }
    if (location === 'GYM') {
      patch({ location, equipment: gymEquipment() })
      return
    }
    patch({ location })
  }

  function setExercisePreference(
    exerciseId: string,
    preferenceType: 'PREFERRED' | 'EXCLUDED',
  ): void {
    setState((current) => {
      const existing = current.draft.preferences.find(
        (item) => item.exerciseId === exerciseId,
      )
      const withoutExercise = current.draft.preferences.filter(
        (item) => item.exerciseId !== exerciseId,
      )
      const preferences = existing?.preferenceType === preferenceType
        ? withoutExercise
        : [...withoutExercise, { exerciseId, preferenceType }]
      return updateOnboardingDraft(current, {
        preferences,
        preferencesTouched: true,
      })
    })
    setSubmitError('')
  }

  async function submit(): Promise<void> {
    if (submittingRef.current) return
    const checked = advanceOnboarding(state)
    setState(checked)
    if (checked.errors.length > 0) return
    if (checked.draft.location === 'OTHER' && !otherEquipmentConfirmed) {
      setSubmitError('请选择在其他场地可使用自重训练还是基础器械')
      return
    }

    submittingRef.current = true
    setSubmitting(true)
    setSubmitError('')
    try {
      const candidate = await application.completeOnboarding(checked.draft)
      application.telemetry.track('onboarding_completed', {
        daysPerWeek: checked.draft.weeklyFrequency!,
        sessionMinutes: checked.draft.sessionMinutes!,
      })
      application.telemetry.track('plan_generated', {
        result: candidate.status === 'READY' ? 'ready' : 'needs_adjustment',
        issueCount: candidate.status === 'READY' ? 0 : 1,
      })
      await application.navigation.open('PLAN_CANDIDATES')
    } catch (error) {
      application.telemetry.track('plan_generation_failed', { reason: 'network' })
      setSubmitError(error instanceof Error ? error.message : '保存失败，请稍后重试')
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  const progress = `${Math.round(((state.stepIndex + 1) / ONBOARDING_STEPS.length) * 100)}%`
  const safetyConfirmed = state.draft.adultConfirmed && state.draft.safetyAccepted
  const equipmentSummary = state.draft.location === 'OTHER' && !otherEquipmentConfirmed
    ? '待选择器械范围'
    : state.draft.equipment.length > 0 ? '基础器械' : '仅自重'

  return (
    <View className='onboarding screen'>
      <View className='card onboarding__header'>
        <Text className='eyebrow'>基础档案</Text>
        <Text className='title'>用 4 步生成适合你的计划</Text>
        <Text className='subtitle'>第 {state.stepIndex + 1} / {ONBOARDING_STEPS.length} 步</Text>
        <View className='onboarding__progress-track'>
          <View className='onboarding__progress-value' style={{ width: progress }} />
        </View>
      </View>

      <View className='card onboarding__content'>
        {state.step === 'SAFETY' && (
          <>
            <Text className='section-title'>开始前确认</Text>
            <Text className='subtitle'>遇到疼痛、眩晕或其他不适时，请停止训练并咨询专业人士。</Text>
            <Button
              className={`choice onboarding__wide-choice ${safetyConfirmed ? 'choice--selected' : ''}`}
              onClick={() => patch({
                adultConfirmed: !safetyConfirmed,
                safetyAccepted: !safetyConfirmed,
              })}
            >我已满 18 周岁，并理解这不是医疗或康复服务</Button>
            <Button
              className='onboarding__text-action'
              onClick={() => setShowSafetyDetails((current) => !current)}
            >{showSafetyDetails ? '收起安全说明' : '查看安全说明'}</Button>
            {showSafetyDetails && (
              <View className='info-box'>
                本助手提供一般健身建议，不诊断疾病，也不提供治疗或康复处方。存在健康风险或正在接受治疗时，请先征询医生意见。
              </View>
            )}
          </>
        )}

        {state.step === 'GOAL_AND_EXPERIENCE' && (
          <>
            <Text className='section-title'>训练方向</Text>
            <Text className='subtitle'>先告诉我们最重要的目标和当前经验，之后仍可修改。</Text>
            <View className='field-group'>
              <Text className='field-label'>你的目标</Text>
              <View className='onboarding__option-list'>
                {([
                  ['HYPERTROPHY', '以肌肉增长和训练容量为重点'],
                  ['FAT_LOSS', '以规律训练和提高日常消耗为重点'],
                  ['GENERAL_FITNESS', '提升体能，建立稳定训练习惯'],
                ] as const).map(([value, description]) => (
                  <Button
                    key={value}
                    className={`choice onboarding__option-card ${state.draft.goal === value ? 'choice--selected' : ''}`}
                    onClick={() => patch({ goal: value })}
                  >
                    <Text className='onboarding__option-title'>{goalDisplayName(value)}</Text>
                    <Text className='onboarding__option-description'>{description}</Text>
                  </Button>
                ))}
              </View>
            </View>
            <View className='field-group'>
              <Text className='field-label'>训练经验</Text>
              <View className='choice-row'>
                {(['BEGINNER', 'INTERMEDIATE', 'ADVANCED'] as const).map((value) => (
                  <Button
                    key={value}
                    className={`choice ${state.draft.experience === value ? 'choice--selected' : ''}`}
                    onClick={() => patch({ experience: value })}
                  >{experienceDisplayName(value)}</Button>
                ))}
              </View>
            </View>
          </>
        )}

        {state.step === 'SCHEDULE' && (
          <>
            <Text className='section-title'>训练安排</Text>
            <Text className='subtitle'>选择真正能长期坚持的时间，不必一次选得很激进。</Text>
            <View className='field-group'>
              <Text className='field-label'>每周训练几天</Text>
              <View className='choice-row'>
                {[...primaryFrequencies, ...(showMoreFrequency ? moreFrequencies : [])].map((value) => (
                  <Button
                    key={value}
                    className={`choice ${state.draft.weeklyFrequency === value ? 'choice--selected' : ''}`}
                    onClick={() => patch({ weeklyFrequency: value })}
                  >{value} 天</Button>
                ))}
              </View>
              {!showMoreFrequency && (
                <Button className='onboarding__more-action' onClick={() => setShowMoreFrequency(true)}>
                  更多：5 天、6 天
                </Button>
              )}
            </View>
            <View className='field-group'>
              <Text className='field-label'>每次训练多久</Text>
              <View className='choice-row'>
                {[...primaryDurations, ...(showMoreDuration ? moreDurations : [])].map((value) => (
                  <Button
                    key={value}
                    className={`choice ${state.draft.sessionMinutes === value ? 'choice--selected' : ''}`}
                    onClick={() => patch({ sessionMinutes: value })}
                  >{value} 分钟</Button>
                ))}
              </View>
              {!showMoreDuration && (
                <Button className='onboarding__more-action' onClick={() => setShowMoreDuration(true)}>
                  更多：75 分钟、90 分钟
                </Button>
              )}
            </View>
          </>
        )}

        {state.step === 'LOCATION_AND_EQUIPMENT' && (
          <>
            <Text className='section-title'>场地与器械</Text>
            <Text className='subtitle'>最后确认训练条件，我们会据此选择能完成的动作。</Text>
            <View className='field-group'>
              <Text className='field-label'>主要训练场地</Text>
              <View className='onboarding__option-list'>
                {([
                  ['HOME', '居家', '以自重动作为主，不需要购买器械'],
                  ['GYM', '健身房', '使用常见哑铃、训练凳、绳索和固定器械'],
                  ['OTHER', '其他场地', '再选择你能使用的器械范围'],
                ] as const).map(([value, label, description]) => (
                  <Button
                    key={value}
                    className={`choice onboarding__option-card ${state.draft.location === value ? 'choice--selected' : ''}`}
                    onClick={() => selectLocation(value)}
                  >
                    <Text className='onboarding__option-title'>{label}</Text>
                    <Text className='onboarding__option-description'>{description}</Text>
                  </Button>
                ))}
              </View>
            </View>

            {state.draft.location === 'OTHER' && (
              <View className='field-group'>
                <Text className='field-label'>可使用的器械</Text>
                <View className='choice-row'>
                  <Button
                    className={`choice ${otherEquipmentConfirmed && state.draft.equipment.length === 0 ? 'choice--selected' : ''}`}
                    onClick={() => {
                      setOtherEquipmentConfirmed(true)
                      patch({ equipment: [] })
                    }}
                  >仅自重</Button>
                  <Button
                    className={`choice ${otherEquipmentConfirmed && state.draft.equipment.length > 0 ? 'choice--selected' : ''}`}
                    onClick={() => {
                      setOtherEquipmentConfirmed(true)
                      patch({ equipment: gymEquipment() })
                    }}
                  >有基础器械</Button>
                </View>
              </View>
            )}

            <View className='field-group onboarding__preferences'>
              <Text className='field-label'>偏好动作（可选）</Text>
              <Text className='onboarding__requirements-help'>
                标记想优先练或需要避开的动作；未修改时会保留你之前保存的选择。
              </Text>
              {preferenceOptions.length > 0 ? (
                <View className='onboarding__preference-list'>
                  {preferenceOptions.map((exercise) => {
                    const selected = state.draft.preferences.find(
                      (item) => item.exerciseId === exercise.id,
                    )?.preferenceType
                    return (
                      <View key={exercise.id} className='onboarding__preference-row'>
                        <Text className='onboarding__preference-name'>{exercise.name}</Text>
                        <View className='onboarding__preference-actions'>
                          <Button
                            className={`onboarding__preference-action ${selected === 'PREFERRED' ? 'choice--selected' : ''}`}
                            disabled={submitting}
                            onClick={() => setExercisePreference(exercise.id, 'PREFERRED')}
                          >优先</Button>
                          <Button
                            className={`onboarding__preference-action ${selected === 'EXCLUDED' ? 'onboarding__preference-action--excluded' : ''}`}
                            disabled={submitting}
                            onClick={() => setExercisePreference(exercise.id, 'EXCLUDED')}
                          >避开</Button>
                        </View>
                      </View>
                    )
                  })}
                </View>
              ) : (
                <Text className='onboarding__requirements-help'>
                  当前没有可选动作；仍可在下方用文字描述训练侧重点。
                </Text>
              )}
            </View>

            <View className='field-group onboarding__requirements'>
              <View className='onboarding__requirements-heading'>
                <Text className='field-label'>额外训练偏好（可选）</Text>
                <Text className='onboarding__requirements-count'>
                  {state.draft.additionalRequirements?.length ?? 0}/300
                </Text>
              </View>
              <Textarea
                className='onboarding__requirements-input'
                maxlength={300}
                value={state.draft.additionalRequirements ?? ''}
                placeholder='例如：胸背优先、不安排跳跃动作、希望覆盖更多动作模式'
                onInput={(event) => patch({ additionalRequirements: event.detail.value })}
              />
              <Text className='onboarding__requirements-help'>
                AI 会结合档案、器械和这些偏好生成计划。疼痛、疾病、诊断或康复需求请咨询专业人士，不在这里处理。
              </Text>
            </View>

            <View className='onboarding__summary'>
              <View className='onboarding__summary-row'>
                <View className='onboarding__summary-copy'>
                  <Text className='field-label'>训练方向</Text>
                  <Text>{goalDisplayName(state.draft.goal)} · {experienceDisplayName(state.draft.experience)}</Text>
                </View>
                <Button
                  className='onboarding__edit-action'
                  onClick={() => setState((current) => goToOnboardingStep(current, 'GOAL_AND_EXPERIENCE'))}
                >修改训练方向</Button>
              </View>
              <View className='onboarding__summary-row'>
                <View className='onboarding__summary-copy'>
                  <Text className='field-label'>训练安排</Text>
                  <Text>每周 {state.draft.weeklyFrequency} 天 · 每次 {state.draft.sessionMinutes} 分钟</Text>
                </View>
                <Button
                  className='onboarding__edit-action'
                  onClick={() => setState((current) => goToOnboardingStep(current, 'SCHEDULE'))}
                >修改训练安排</Button>
              </View>
              <Text>场地：{locationDisplayName(state.draft.location)}</Text>
              <Text>器械：{equipmentSummary}</Text>
            </View>
          </>
        )}

        {state.errors.map((error) => <View key={error} className='error-box'>{error}</View>)}
        {submitError && <View className='error-box'>{submitError}</View>}
      </View>

      <View className='action-row action-row--sticky'>
        {state.stepIndex > 0 && (
          <Button
            className='secondary-action'
            onClick={() => setState((current) => previousOnboardingStep(current))}
          >上一步</Button>
        )}
        {state.step === 'LOCATION_AND_EQUIPMENT'
          ? <Button
              className='primary-action'
              loading={submitting}
              disabled={submitting}
              onClick={() => void submit()}
            >生成我的计划</Button>
          : <Button className='primary-action' onClick={next}>继续</Button>}
      </View>
    </View>
  )
}
