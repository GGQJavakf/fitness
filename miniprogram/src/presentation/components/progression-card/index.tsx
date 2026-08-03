import { Button, Text, View } from '@tarojs/components'

import type { ProgressionCardView } from '../../../application/progression'
import { exerciseDisplayName } from '../../copy'

import './index.scss'

interface ProgressionCardProps {
  readonly card: ProgressionCardView
  readonly busy: boolean
  readonly onApply: (acceptedWeightKg: number) => Promise<void> | void
  readonly onDismiss: () => Promise<void> | void
  readonly onOpenTrend: () => Promise<void> | void
}

export default function ProgressionCard({
  card, busy, onApply, onDismiss, onOpenTrend,
}: ProgressionCardProps) {
  return (
    <View className='surface-card progression-card'>
      <View className='progression-card__heading'>
        <View className='progression-card__index'>
          <View className='progression-card__index-line' />
          <View className='progression-card__index-line progression-card__index-line--short' />
        </View>
        <View className='progression-card__titles'>
          <Text className='progression-card__exercise'>{exerciseDisplayName(card.exerciseCode)}</Text>
          <Text className='progression-card__decision'>{card.title}</Text>
        </View>
      </View>

      <Text className='progression-card__weight data-number'>{card.weightLabel}</Text>
      <Text className={card.locked ? 'progression-card__reason progression-card__reason--locked' : 'progression-card__reason'}>{card.reason}</Text>
      <View className='progression-card__basis'>
        <View className='progression-card__basis-dot' />
        <Text>基于连续训练记录与身体反馈计算</Text>
      </View>

      <View className='progression-card__actions'>
        {card.actionable && (
          <Button
            className='primary-action progression-card__apply'
            loading={busy}
            disabled={busy}
            onClick={() => void onApply(card.recommendedWeightKg)}
          >
            {busy ? '正在更新计划' : '采用建议'}
          </Button>
        )}
        <Button className='secondary-action' disabled={busy} onClick={() => void onOpenTrend()}>查看训练变化</Button>
        <Button className='progression-card__dismiss' disabled={busy} onClick={() => void onDismiss()}>保持当前安排</Button>
      </View>
    </View>
  )
}
