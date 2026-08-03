import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import { toExerciseTrendRows, type ExerciseTrendRow } from '../../../application/progression'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'
import { exerciseDisplayName } from '../../copy'

import './index.scss'

const application = getWeappApplication()

export default function ExerciseTrendPage() {
  const exerciseCode = application.routeParameter('exerciseCode')
  const [rows, setRows] = useState<ExerciseTrendRow[]>([])
  const [message, setMessage] = useState('正在读取训练变化…')
  const [loading, setLoading] = useState(false)

  async function load(): Promise<void> {
    if (!exerciseCode) {
      setMessage('缺少动作信息，请从训练进展页重新进入。')
      return
    }
    setLoading(true)
    setMessage('正在读取训练变化…')
    try {
      const trend = await application.getExerciseTrend(exerciseCode)
      const nextRows = toExerciseTrendRows(trend.points)
      setRows(nextRows)
      setMessage(nextRows.length ? '这里只统计完整训练中的有效正式组。' : '继续完成训练，趋势会逐步变得清晰。')
    } catch {
      setMessage('训练趋势暂时无法加载，请稍后重试。')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void load() }, [exerciseCode])

  const latest = rows[rows.length - 1]

  return (
    <View className='screen exercise-trend-page'>
      <View className='page-hero trend-hero'>
        <Text className='page-hero__eyebrow'>EXERCISE TREND</Text>
        <Text className='page-hero__title'>{exerciseCode ? exerciseDisplayName(exerciseCode) : '动作变化'}</Text>
        <Text className='page-hero__description'>{message}</Text>
        {latest && (
          <View className='trend-hero__latest'>
            <Text className='trend-hero__latest-label'>最近一次最高重量</Text>
            <Text className='trend-hero__latest-value data-number'>{latest.weightLabel}</Text>
          </View>
        )}
      </View>

      {rows.length > 0 && (
        <View className='surface-card trend-overview'>
          <View className='trend-overview__metric'>
            <Text className='trend-overview__value data-number'>{rows.length}</Text>
            <Text className='trend-overview__label'>次有效训练</Text>
          </View>
          <View className='trend-overview__divider' />
          <View className='trend-overview__copy'>
            <Text className='trend-overview__title'>用连续表现判断进步</Text>
            <Text className='trend-overview__description'>单次波动不会直接改变计划，系统会结合多次训练反馈再给出建议。</Text>
          </View>
        </View>
      )}

      <View className='section-heading'>
        <Text className='section-heading__title'>训练轨迹</Text>
        <Text className='section-heading__meta'>{rows.length ? `${rows.length} 条记录` : '等待数据'}</Text>
      </View>

      {rows.length > 0 && (
        <View className='surface-card trend-timeline'>
          {rows.map((row, index) => (
            <View className='trend-row' key={row.id}>
              <View className='trend-row__rail'>
                <View className={index === rows.length - 1 ? 'trend-row__dot trend-row__dot--latest' : 'trend-row__dot'} />
                {index < rows.length - 1 && <View className='trend-row__line' />}
              </View>
              <View className='trend-row__content'>
                <View className='trend-row__heading'>
                  <Text className='trend-row__weight data-number'>{row.weightLabel}</Text>
                  <Text className='trend-row__time'>{row.timeLabel}</Text>
                </View>
                <Text className='trend-row__volume'>{row.volumeLabel}</Text>
              </View>
            </View>
          ))}
        </View>
      )}

      {rows.length === 0 && !message.includes('正在') && (
        <View className='surface-card empty-state trend-empty'>
          <View className='trend-empty__mark'>
            <View className='trend-empty__line trend-empty__line--one' />
            <View className='trend-empty__line trend-empty__line--two' />
            <View className='trend-empty__line trend-empty__line--three' />
          </View>
          <Text className='section-title'>趋势需要时间形成</Text>
          <Text className='subtitle'>完成几次训练后，你会看到重量和完成量的连续变化。</Text>
        </View>
      )}
      {message.includes('无法加载') && <Button className='secondary-action trend-action' loading={loading} onClick={() => void load()}>重新加载</Button>}
      <Button className='trend-back' onClick={() => void application.navigation.replace('HISTORY')}>返回训练进展</Button>
    </View>
  )
}
