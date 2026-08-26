import { Button, Text, View } from '@tarojs/components'
import { useCallback, useEffect, useRef, useState } from 'react'

import type { PlanPresetSummary } from '../../../application/models'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { weekdayDisplayName } from '../../copy'

import './index.scss'

export default function PlanPresetsPage() {
  const application = getWeappApplication()
  const [presets, setPresets] = useState<readonly PlanPresetSummary[]>([])
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
      if (mounted.current) setPresets(items)
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
    selectionInFlight.current = true
    setSelectingCode(presetCode)
    setError('')
    try {
      const candidate = await application.selectPlanPreset(presetCode)
      if (!candidate.canContinue) {
        setError(candidate.reason ?? '当前档案与该预设不兼容，请先调整训练档案')
        return
      }
      await application.navigation.open('PLAN_CANDIDATES')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '预设暂时无法选择，请稍后重试')
    } finally {
      selectionInFlight.current = false
      if (mounted.current) setSelectingCode('')
    }
  }

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

      {presets.map((preset) => (
        <View className='preset-card' key={preset.code}>
          <View className='preset-card__heading'>
            <View>
              <Text className='preset-card__name'>{preset.name}</Text>
              <Text className='preset-card__version'>预设版本 {preset.version}</Text>
            </View>
            <Text className='preset-card__badge'>增肌增重</Text>
          </View>
          <View className='preset-card__metrics'>
            <Text>{preset.weeklyFrequency} 天训练</Text>
            <Text>单次约 {preset.sessionMinutes} 分钟</Text>
            <Text>周末双休</Text>
          </View>
          <View className='preset-days'>
            {preset.days.map((day) => (
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
          </View>
          <View className='preset-card__notice'>适用档案：增肌、每周 5 天、健身房、单次 45 分钟</View>
          <Button
            className='preset-card__action'
            disabled={Boolean(selectingCode)}
            onClick={() => void selectPreset(preset.code)}
          >
            {selectingCode === preset.code ? '正在生成预览…' : '预览并选择此计划'}
          </Button>
        </View>
      ))}

      {!loading && error && (
        <Button className='preset-retry' onClick={() => void load()}>重新读取</Button>
      )}
    </View>
  )
}
