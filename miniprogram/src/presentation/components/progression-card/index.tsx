import { Button, Input, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

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
  const [weight, setWeight] = useState(String(card.recommendedWeightKg))
  const acceptedWeight = Number(weight)
  const validWeight = Number.isFinite(acceptedWeight) && acceptedWeight >= 0

  useEffect(() => setWeight(String(card.recommendedWeightKg)), [card.id, card.recommendedWeightKg])

  return <View className='card progression-card'>
    <View className='progression-card__heading'>
      <View className='progression-card__titles'>
        <View><Text className='section-title'>{exerciseDisplayName(card.exerciseCode)}</Text><Text className='code-label'>{card.exerciseCode}</Text></View>
        <Text className='progression-card__decision'>{card.title}</Text>
      </View>
      <Text className='progression-card__weight'>{card.weightLabel}</Text>
    </View>
    <Text className={card.locked ? 'warning-box' : 'subtitle'}>{card.reason}</Text>
    <Text className='progression-card__algorithm'>{card.algorithmLabel} · 重量由服务端规则计算</Text>
    {card.actionable && <View className='progression-card__editor'>
      <Text>采纳重量（KG）</Text>
      <Input
        className='progression-card__input'
        type='digit'
        value={weight}
        disabled={busy}
        onInput={(event) => setWeight(event.detail.value)}
      />
    </View>}
    <View className='action-row'>
      {card.actionable && <Button
        className='primary-action progression-card__action'
        disabled={busy || !validWeight}
        onClick={() => void onApply(acceptedWeight)}
      >{busy ? '处理中…' : '确认采纳'}</Button>}
      <Button
        className='secondary-action progression-card__action'
        disabled={busy}
        onClick={() => void onDismiss()}
      >忽略建议</Button>
      <Button
        className='secondary-action progression-card__action'
        disabled={busy}
        onClick={() => void onOpenTrend()}
      >查看趋势</Button>
    </View>
  </View>
}
