import { Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import { summarizeWorkout } from '../../../application/workoutFlow'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'

import './index.scss'

const application = getWeappApplication()
type Summary = ReturnType<typeof summarizeWorkout>

export default function WorkoutSummaryPage() {
  const [summary, setSummary] = useState<Summary | null>(null)
  useEffect(() => { void application.workouts.load().then((state) => setSummary(state ? summarizeWorkout(state) : null)) }, [])
  return <View className='screen'><View className='card'>
    <Text className='title'>本次训练事实</Text>
    {summary ? <>
      <Text className='summary-number'>{summary.completedWorkSets} 组</Text>
      <Text>有效正式组容量：{summary.completedVolumeKg} KG·次</Text>
      <Text>失败 {summary.failedSets} 组 · 跳过 {summary.skippedSets} 组</Text>
      <View className={summary.complete ? 'info-box' : 'warning-box'}>{summary.complete ? '训练已完整记录。' : '训练尚不完整，不会据此自动加重。'}</View>
    </> : <Text className='subtitle'>暂无可总结的训练草稿。</Text>}
  </View></View>
}
