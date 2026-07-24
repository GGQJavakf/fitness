import { Button, Text, View } from '@tarojs/components'
import { useState } from 'react'

import { getWeappApplication } from '../../../platform/weapp/compositionRoot'

import './index.scss'

const application = getWeappApplication()

export default function PlanCandidatesPage() {
  const [candidate] = useState(() => application.getCandidate())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function accept(): Promise<void> {
    setBusy(true)
    setError('')
    try {
      application.openCandidateEditor()
      await application.navigation.open('PLAN_EDITOR')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '候选计划暂时无法采用')
    } finally {
      setBusy(false)
    }
  }

  if (!candidate) {
    return (
      <View className='screen'>
        <View className='card'>
          <Text className='title'>候选已失效</Text>
          <Text className='subtitle'>请返回档案页重新生成，不会使用过期或本地猜测的计划。</Text>
          <Button className='primary-action' onClick={() => void application.navigation.replace('ONBOARDING')}>返回建档</Button>
        </View>
      </View>
    )
  }

  return (
    <View className='screen'>
      <View className='card'>
        <Text className='title'>{candidate.status === 'READY' ? '候选计划已生成' : '暂未生成候选'}</Text>
        {candidate.status === 'READY'
          ? <View className={candidate.explanationMessage.includes('暂不可用') ? 'warning-box' : 'info-box'}>{candidate.explanationMessage}</View>
          : <View className='error-box'>{candidate.reason}</View>}
      </View>

      {candidate.days.map((day) => (
        <View key={day.code} className='card candidate-day'>
          <Text className='section-title'>{day.name}</Text>
          {day.exercises.map((exercise) => (
            <View key={exercise.exerciseCode} className='candidate-exercise'>
              <Text>{exercise.exerciseCode}</Text>
              <Text>{exercise.workSets} 组 · {exercise.repRange} · {exercise.restLabel}</Text>
              <Text className='subtitle'>{exercise.weightLabel}</Text>
            </View>
          ))}
        </View>
      ))}

      {error && <View className='error-box'>{error}</View>}
      {candidate.canContinue
        ? <Button className='primary-action' loading={busy} onClick={() => void accept()}>采用候选并进入编辑</Button>
        : <Button className='primary-action' onClick={() => void application.navigation.replace('ONBOARDING')}>{candidate.action?.label ?? '返回调整档案'}</Button>}
      {candidate.canContinue && <Text className='subtitle'>进入编辑不会立即生效；最终保存时才创建首个不可变版本，手改会以新版本记录，不覆盖历史。</Text>}
    </View>
  )
}
