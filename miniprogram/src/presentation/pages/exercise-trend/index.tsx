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
  const [message, setMessage] = useState('正在读取有效正式组…')

  async function load(): Promise<void> {
    if (!exerciseCode) {
      setMessage('缺少动作标识，请从训练历史或建议卡片重新进入。')
      return
    }
    setMessage('正在读取有效正式组…')
    try {
      const trend = await application.getExerciseTrend(exerciseCode)
      const nextRows = toExerciseTrendRows(trend.points)
      setRows(nextRows)
      setMessage(nextRows.length ? '仅统计完整训练中的有效正式组。' : '还没有可用于趋势的有效正式组。')
    } catch {
      setMessage('趋势暂时无法加载，请检查网络后重试。')
    }
  }

  useEffect(() => { void load() }, [exerciseCode])

  return <View className='screen'>
    <View className='card'>
      <Text className='title'>{exerciseCode ? exerciseDisplayName(exerciseCode) : '动作'}趋势</Text>
      {exerciseCode && <Text className='code-label'>{exerciseCode}</Text>}
      <Text className='subtitle'>{message}</Text>
    </View>
    {rows.map((row) => <View className='card trend-row' key={row.id}>
      <View className='trend-row__heading'>
        <Text className='section-title'>{row.weightLabel}</Text>
        <Text className='subtitle'>{row.timeLabel}</Text>
      </View>
      <Text>{row.volumeLabel}</Text>
    </View>)}
    {rows.length === 0 && !message.includes('正在') && <View className='card empty-state'><Text>完成训练后才会形成趋势，提前结束或异常记录不会误导进阶决策。</Text></View>}
    {message.includes('无法加载') && <Button className='secondary-action' onClick={() => void load()}>重试</Button>}
    <Button className='secondary-action' onClick={() => void application.navigation.replace('HISTORY')}>返回训练历史</Button>
  </View>
}
