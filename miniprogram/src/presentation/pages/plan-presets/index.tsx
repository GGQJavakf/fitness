import { Button, ScrollView, Text, View } from '@tarojs/components'
import { useCallback, useEffect, useRef, useState } from 'react'

import type { PlanPresetSummary } from '../../../application/models'
import { getPlanningApplication } from '../../../platform/weapp/featureRoots/planningCompositionRoot'
import { experienceDisplayName, goalDisplayName, locationDisplayName, weekdayDisplayName } from '../../copy'

const mismatchFieldNames: Record<PlanPresetSummary['mismatchFields'][number], string> = {
  EXPERIENCE: '训练经验',
  GOAL: '训练目标',
  WEEKLY_FREQUENCY: '每周训练天数',
  SESSION_MINUTES: '单次训练时长',
  LOCATION: '训练地点',
}

function matchDescription(preset: PlanPresetSummary): string {
  if (preset.availabilityStatus === 'BLOCKED_CAPABILITY') {
    return '当前计划与画像可以匹配，但设备能力契约尚未满足，暂不提供生成。'
  }
  if (preset.matchStatus === 'EXACT') {
    return preset.recommended ? '推荐给你 · 与当前训练档案完全匹配' : '与当前训练档案完全匹配'
  }
  const fields = preset.mismatchFields.map((field) => mismatchFieldNames[field]).join('、')
  return `${preset.recommended ? '最接近当前档案 · ' : ''}需调整：${fields}`
}

function contentStatusLabel(status: PlanPresetSummary['contentStatus']): string {
  if (status === 'AI_VALIDATED') return 'AI 已校验'
  if (status === 'PUBLIC_RELEASE_APPROVED') return '已批准公开'
  if (status === 'RETIRED') return '已停用'
  return 'AI 草稿'
}

function professionalReviewLabel(
  status: PlanPresetSummary['professionalReviewStatus'],
): string {
  return status === 'APPROVED' ? '专业审核已完成' : '专业审核待完成'
}

export default function PlanPresetsPage() {
  const application = getPlanningApplication()
  const [presets, setPresets] = useState<readonly PlanPresetSummary[]>([])
  const [selectedCode, setSelectedCode] = useState('')
  const [loading, setLoading] = useState(true)
  const [selectingCode, setSelectingCode] = useState('')
  const [error, setError] = useState('')
  const mounted = useRef(true)
  const selectionInFlight = useRef(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const items = await application.listPlanPresets()
      if (mounted.current) {
        setPresets(items)
        setSelectedCode((current) => (
          items.some((preset) => preset.code === current)
            ? current
            : items.find((preset) => preset.recommended)?.code ?? items[0]?.code ?? ''
        ))
      }
    } catch (reason) {
      if (mounted.current) {
        setError(reason instanceof Error ? reason.message : '系统预设暂时无法读取，请稍后重试')
      }
    } finally {
      if (mounted.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void load()
    return () => { mounted.current = false }
  }, [load])

  async function selectPreset(presetCode: string): Promise<void> {
    if (selectionInFlight.current) return
    const preset = presets.find((item) => item.code === presetCode)
    if (!preset || preset.matchStatus !== 'EXACT' || preset.availabilityStatus !== 'AVAILABLE') return
    selectionInFlight.current = true
    setSelectingCode(presetCode)
    setError('')
    try {
      const candidate = await application.selectPlanPresetAndOpenCandidates(presetCode)
      if (!candidate) return
      if (!candidate.canContinue) {
        setError(candidate.reason ?? '当前档案与该预设不兼容，请先调整训练档案')
        return
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '预设暂时无法选择，请稍后重试')
    } finally {
      selectionInFlight.current = false
      if (mounted.current) setSelectingCode('')
    }
  }

  function choosePreset(presetCode: string): void {
    if (selectionInFlight.current) return
    setSelectedCode(presetCode)
  }

  const selectedPreset = presets.find((preset) => preset.code === selectedCode) ?? presets[0]

  return (
    <View className='preset-page screen'>
      <View className='preset-hero'>
        <Text className='preset-hero__eyebrow'>SYSTEM PRESETS</Text>
        <Text className='preset-hero__title'>选择一套完整计划</Text>
        <Text className='preset-hero__description'>先预览，再启用。选择预设不会直接覆盖当前计划，只有确认启用后才会创建新的活动计划版本。</Text>
      </View>

      {error && <View className='preset-error'>{error}</View>}
      {loading && <View className='preset-state'>正在读取系统预设…</View>}
      {!loading && presets.length === 0 && (
        <View className='preset-state'>当前没有可用预设</View>
      )}

      {presets.length > 0 && (
        <ScrollView className='preset-selector' scrollX enhanced showScrollbar={false}>
          <View className='preset-selector__track'>
            {presets.map((preset) => (
              <Button
                aria-label={selectedPreset?.code === preset.code ? `${preset.name}，当前计划` : preset.name}
                id={`plan-preset-selector-${preset.code}`}
                className={`preset-selector__item${selectedPreset?.code === preset.code ? ' preset-selector__item--selected' : ''}`}
                disabled={Boolean(selectingCode)}
                key={preset.code}
                onClick={() => choosePreset(preset.code)}
              >
                {preset.name}
              </Button>
            ))}
          </View>
        </ScrollView>
      )}

      {selectedPreset && (
        <View className='preset-card' key={selectedPreset.code}>
          <View className='preset-card__heading'>
            <View>
              <Text className='preset-card__name'>{selectedPreset.name}</Text>
              <Text className='preset-card__version'>预设版本 {selectedPreset.version}</Text>
            </View>
            <Text className='preset-card__badge'>{goalDisplayName(selectedPreset.goal)}</Text>
          </View>
          <View className='preset-card__metrics'>
            <Text>{selectedPreset.weeklyFrequency} 天训练</Text>
            <Text>单次约 {selectedPreset.sessionMinutes} 分钟</Text>
            <Text>{locationDisplayName(selectedPreset.location)}</Text>
          </View>
          <View className='preset-card__notice'>
            适用档案：{experienceDisplayName(selectedPreset.experience)}、{goalDisplayName(selectedPreset.goal)}、
            每周 {selectedPreset.weeklyFrequency} 天、{locationDisplayName(selectedPreset.location)}、单次 {selectedPreset.sessionMinutes} 分钟
          </View>
          <View
            className={`preset-card__match preset-card__match--${selectedPreset.matchStatus.toLowerCase()}`}
          >
            {matchDescription(selectedPreset)}
          </View>
          {selectedPreset.availabilityStatus === 'BLOCKED_CAPABILITY' && (
            <View className='preset-capability-blocker'>
              <Text className='preset-capability-blocker__title'>设备能力待补齐</Text>
              <Text className='preset-capability-blocker__reason'>{selectedPreset.unavailableReason}</Text>
              <Text className='preset-capability-blocker__boundary'>不会用无器械背部控制动作冒充弹力带划船或下拉。</Text>
            </View>
          )}
          {selectedPreset.introductoryPhase && (
            <View className='preset-intro-phase'>
              <Text className='preset-intro-phase__title'>新手引导期</Text>
              <Text className='preset-intro-phase__dose'>
                前 {selectedPreset.introductoryPhase.weeks} 周 · 每个动作 {selectedPreset.introductoryPhase.workSets} 组 · RIR {selectedPreset.introductoryPhase.targetRirMin}～{selectedPreset.introductoryPhase.targetRirMax}
              </Text>
              <Text className='preset-intro-phase__condition'>{selectedPreset.introductoryPhase.transitionCondition}</Text>
            </View>
          )}
          <View className='preset-evidence'>
            <View className='preset-evidence__heading'>
              <Text className='preset-evidence__title'>依据与适用边界</Text>
              <View className='preset-evidence__statuses'>
                <Text className='preset-evidence__status'>
                  {contentStatusLabel(selectedPreset.contentStatus)}
                </Text>
                <Text className={`preset-evidence__status preset-evidence__status--${selectedPreset.professionalReviewStatus.toLowerCase()}`}>
                  {professionalReviewLabel(selectedPreset.professionalReviewStatus)}
                </Text>
              </View>
            </View>
            <Text className='preset-evidence__disclaimer'>
              权威来源支持通用原则，不代表来源机构审核或背书本预设的具体动作、组次、休息与时长。
            </Text>
            <View className='preset-evidence__group'>
              <Text className='preset-evidence__group-title'>核心依据</Text>
              {selectedPreset.sources.map((source) => (
                <View className='preset-source' key={source.id}>
                  <Text className='preset-source__title'>{source.title}</Text>
                  <Text className='preset-source__url' selectable>
                    {source.url ?? '内部来源（无外部链接）'}
                  </Text>
                  <Text className='preset-source__boundary'>适用边界：{source.usageBoundary}</Text>
                </View>
              ))}
            </View>
            {selectedPreset.explanationSources.length > 0 && (
              <View className='preset-evidence__group'>
                <Text className='preset-evidence__group-title'>解释来源</Text>
                {selectedPreset.explanationSources.map((source) => (
                  <View className='preset-source' key={source.id}>
                    <Text className='preset-source__title'>{source.title}</Text>
                    <Text className='preset-source__url' selectable>{source.url}</Text>
                    <Text className='preset-source__boundary'>适用边界：{source.usageBoundary}</Text>
                  </View>
                ))}
              </View>
            )}
          </View>
          <Button
            id={`plan-preset-${selectedPreset.code}`}
            className='preset-card__action'
            disabled={Boolean(selectingCode)
              || selectedPreset.matchStatus !== 'EXACT'
              || selectedPreset.availabilityStatus !== 'AVAILABLE'}
            onClick={() => void selectPreset(selectedPreset.code)}
          >
            {selectedPreset.availabilityStatus === 'BLOCKED_CAPABILITY'
              ? '设备能力待补齐'
              : selectingCode === selectedPreset.code
              ? '正在生成预览…'
              : selectedPreset.matchStatus === 'EXACT'
                ? '预览并选择此计划'
                : '请先调整训练档案'}
          </Button>
          {selectedPreset.availabilityStatus === 'AVAILABLE' && <View className='preset-days'>
            {selectedPreset.days.map((day) => (
              <View className='preset-day' key={day.weekday}>
                <View className='preset-day__copy'>
                  <Text className='preset-day__weekday'>{weekdayDisplayName(day.weekday)}</Text>
                  <Text className='preset-day__name'>{day.name}</Text>
                  <Text className='preset-day__focus'>{day.focus}</Text>
                </View>
                <View className='preset-day__meta'>
                  <Text>{day.exerciseCount} 个动作</Text>
                  <Text>{day.estimatedMinutesMin}～{day.estimatedMinutesMax} 分钟</Text>
                </View>
              </View>
            ))}
          </View>}
        </View>
      )}

      {!loading && error && (
        <Button className='preset-retry' onClick={() => void load()}>重新读取</Button>
      )}
    </View>
  )
}
