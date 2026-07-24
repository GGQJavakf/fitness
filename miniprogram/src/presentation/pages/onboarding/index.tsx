import { Button, Text, View } from '@tarojs/components'
import { useState } from 'react'

import {
  DEFAULT_GYM_EQUIPMENT,
  ONBOARDING_STEPS,
  advanceOnboarding,
  previousOnboardingStep,
  updateOnboardingDraft,
  type OnboardingDraft,
} from '../../../application/onboarding'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { experienceDisplayName, goalDisplayName, locationDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

export default function OnboardingPage() {
  const [state, setState] = useState(() => application.resumeOnboarding())
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')

  function patch(patchValue: Partial<OnboardingDraft>): void {
    setState((current) => updateOnboardingDraft(current, patchValue))
  }

  function next(): void {
    setState((current) => advanceOnboarding(current))
  }

  async function submit(): Promise<void> {
    const checked = advanceOnboarding(state)
    setState(checked)
    if (checked.errors.length > 0) return

    setSubmitting(true)
    setSubmitError('')
    try {
      await application.completeOnboarding(state.draft)
      await application.navigation.open('PLAN_CANDIDATES')
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : '保存失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  const progress = `${Math.round(((state.stepIndex + 1) / ONBOARDING_STEPS.length) * 100)}%`

  return (
    <View className='onboarding screen'>
      <View className='card'>
        <Text className='eyebrow'>基础档案</Text>
        <Text className='title'>3 分钟建立基础档案</Text>
        <Text className='subtitle'>第 {state.stepIndex + 1} / {ONBOARDING_STEPS.length} 步</Text>
        <View className='onboarding__progress-track'>
          <View className='onboarding__progress-value' style={{ width: progress }} />
        </View>
      </View>

      <View className='card'>
        {state.step === 'SAFETY' && (
          <>
            <Text className='section-title'>成年与安全说明</Text>
            <Text className='subtitle'>本产品仅支持已满 18 周岁的成年人，且不提供医疗诊断、治疗建议或康复处方。如有疼痛或健康风险，请先咨询专业人士。</Text>
            <Button
              className={`choice ${state.draft.adultConfirmed ? 'choice--selected' : ''}`}
              onClick={() => patch({ adultConfirmed: !state.draft.adultConfirmed })}
            >我已满 18 周岁</Button>
            <Button
              className={`choice ${state.draft.safetyAccepted ? 'choice--selected' : ''}`}
              onClick={() => patch({ safetyAccepted: !state.draft.safetyAccepted })}
            >我理解非医疗/康复边界</Button>
          </>
        )}

        {state.step === 'GOAL_AND_EXPERIENCE' && (
          <>
            <Text className='section-title'>目标与经验</Text>
            <Text className='subtitle'>计划关键数字将由服务端规则引擎生成。</Text>
            <View className='field-group'>
              <Text className='field-label'>你的目标</Text>
              <View className='choice-row'>
                {(['GENERAL_FITNESS', 'STRENGTH', 'HYPERTROPHY'] as const).map((value) => (
                  <Button key={value} className={`choice ${state.draft.goal === value ? 'choice--selected' : ''}`} onClick={() => patch({ goal: value })}>{goalDisplayName(value)}</Button>
                ))}
              </View>
            </View>
            <View className='field-group'>
              <Text className='field-label'>训练经验</Text>
              <View className='choice-row'>
                {(['BEGINNER', 'INTERMEDIATE', 'ADVANCED'] as const).map((value) => (
                  <Button key={value} className={`choice ${state.draft.experience === value ? 'choice--selected' : ''}`} onClick={() => patch({ experience: value })}>{experienceDisplayName(value)}</Button>
                ))}
              </View>
            </View>
          </>
        )}

        {state.step === 'SCHEDULE' && (
          <>
            <Text className='section-title'>时间、频率与场地</Text>
            <Text className='subtitle'>每周 2～6 天；单次时长使用 P0 支持的固定选项。</Text>
            <View className='field-group'>
              <Text className='field-label'>每周训练几天</Text>
              <View className='choice-row'>
                {[2, 3, 4, 5, 6].map((value) => (
                  <Button key={value} className={`choice ${state.draft.weeklyFrequency === value ? 'choice--selected' : ''}`} onClick={() => patch({ weeklyFrequency: value })}>{value} 天</Button>
                ))}
              </View>
            </View>
            <View className='field-group'>
              <Text className='field-label'>每次训练多久</Text>
              <View className='choice-row'>
                {([30, 45, 60, 75, 90] as const).map((value) => (
                  <Button key={value} className={`choice ${state.draft.sessionMinutes === value ? 'choice--selected' : ''}`} onClick={() => patch({ sessionMinutes: value })}>{value} 分钟</Button>
                ))}
              </View>
            </View>
            <View className='field-group'>
              <Text className='field-label'>主要训练场地</Text>
              <View className='choice-row'>
                {(['HOME', 'GYM', 'OTHER'] as const).map((value) => (
                  <Button key={value} className={`choice ${state.draft.location === value ? 'choice--selected' : ''}`} onClick={() => patch({ location: value })}>{locationDisplayName(value)}</Button>
                ))}
              </View>
            </View>
          </>
        )}

        {state.step === 'EQUIPMENT' && (
          <>
            <Text className='section-title'>场地与器械</Text>
            <Text className='subtitle'>P0 仅使用 KG，不支持 LB 或隐式换算。近期重量不在这里发明处方；候选会标记是否需要首次训练校准。</Text>
            <Button
              className={`choice ${state.draft.equipment.length === 0 ? 'choice--selected' : ''}`}
              onClick={() => patch({ equipment: [] })}
            >仅自重</Button>
            <Button
              className={`choice ${state.draft.equipment.length > 0 ? 'choice--selected' : ''}`}
              onClick={() => patch({
                equipment: DEFAULT_GYM_EQUIPMENT.map((item) => ({
                  ...item,
                  minIncrement: { ...item.minIncrement },
                  availableLevels: item.availableLevels.map((level) => ({ ...level })),
                })),
              })}
            >健身房基础器械（哑铃/训练凳/绳索/器械）</Button>
          </>
        )}

        {state.step === 'PREFERENCES' && (
          <>
            <Text className='section-title'>动作偏好与近期重量</Text>
            <Text className='subtitle'>当前 P0 不要求填写动作偏好。近期重量将在候选中以 KNOWN / NEEDS_CALIBRATION / BODYWEIGHT 展示；没有独立 API 时不会在客户端写入虚构数据。</Text>
            <View className='info-box'>可直接继续，后续可在档案能力扩展后维护动作偏好。</View>
          </>
        )}

        {state.step === 'REVIEW' && (
          <>
            <Text className='section-title'>确认档案并生成候选</Text>
            <View className='onboarding__summary'>
              <Text>目标：{goalDisplayName(state.draft.goal)}</Text>
              <Text>经验：{experienceDisplayName(state.draft.experience)}</Text>
              <Text>安排：每周 {state.draft.weeklyFrequency} 天，每次 {state.draft.sessionMinutes} 分钟</Text>
              <Text>场地：{locationDisplayName(state.draft.location)}</Text>
              <Text>器械：{state.draft.equipment.length > 0 ? '健身房基础器械（KG）' : '仅自重'}</Text>
            </View>
          </>
        )}

        {state.errors.map((error) => <View key={error} className='error-box'>{error}</View>)}
        {submitError && <View className='error-box'>{submitError}</View>}
      </View>

      <View className='action-row action-row--sticky'>
        {state.stepIndex > 0 && <Button className='secondary-action' onClick={() => setState((current) => previousOnboardingStep(current))}>上一步</Button>}
        {state.step === 'REVIEW'
          ? <Button className='primary-action' loading={submitting} onClick={() => void submit()}>保存并生成候选</Button>
          : <Button className='primary-action' onClick={next}>继续</Button>}
      </View>
    </View>
  )
}
