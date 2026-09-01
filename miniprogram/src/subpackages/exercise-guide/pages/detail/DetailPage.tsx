import { Button, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState } from 'react'

import type { ExerciseContent } from '../../../../application/content'
import { getExerciseGuideApplication } from '../../../../platform/weapp/featureRoots/exerciseGuideCompositionRoot'
import ExerciseMotionGuide from '../../components/exercise-motion-guide'

// The page entry loads this feature implementation after its visible recovery shell renders.

const difficultyLabels: Record<ExerciseContent['difficulty'], string> = {
  BEGINNER: '基础',
  INTERMEDIATE: '进阶',
  ADVANCED: '高级',
}

const muscleLabels: Record<string, string> = {
  BACK: '背部',
  BICEPS: '肱二头肌',
  CALVES: '小腿',
  CHEST: '胸部',
  CORE: '核心',
  GLUTES: '臀部',
  HAMSTRINGS: '大腿后侧',
  LATS: '背阔肌',
  QUADRICEPS: '大腿前侧',
  SHOULDERS: '肩部',
  TRICEPS: '肱三头肌',
}

export default function ExerciseDetailPage() {
  const application = getExerciseGuideApplication()
  const [exercise, setExercise] = useState<ExerciseContent | null>(null)
  const [message, setMessage] = useState('正在读取动作说明…')
  const [loading, setLoading] = useState(false)
  const requestIdRef = useRef(0)
  const exerciseCode = application.routeParameter('exerciseCode')

  async function load(): Promise<void> {
    const requestId = ++requestIdRef.current
    if (!exerciseCode) {
      setMessage('缺少动作信息，请返回上一页重试。')
      return
    }
    setLoading(true)
    setMessage('正在读取动作说明…')
    try {
      const value = await application.getExercise(exerciseCode)
      if (requestId !== requestIdRef.current) return
      setExercise(value)
      setMessage('')
    } catch {
      if (requestId !== requestIdRef.current) return
      setMessage('动作说明暂时无法读取，请检查网络后重试。')
    } finally {
      if (requestId === requestIdRef.current) setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    return () => { requestIdRef.current += 1 }
  }, [exerciseCode])

  return (
    <View className='screen exercise-detail-page'>
      <View className='page-hero exercise-detail-hero'>
        <Text className='page-hero__eyebrow'>MOVEMENT GUIDE</Text>
        <Text className='page-hero__title'>{exercise?.name ?? '动作说明'}</Text>
        <Text className='page-hero__description'>{exercise?.plainLanguage ?? message}</Text>
        {exercise && (
          <View className='exercise-detail-hero__meta'>
            <Text>{difficultyLabels[exercise.difficulty]}</Text>
            <Text>{exercise.primaryMuscles.map((item) => muscleLabels[item] ?? item).join(' · ')}</Text>
          </View>
        )}
      </View>

      {exercise && (
        <>
          <ExerciseMotionGuide
            exerciseCode={exercise.code}
            exerciseName={exercise.name}
            primaryRef={exercise.image.primaryRef}
            fallbackRef={exercise.image.fallbackRef}
          />

          <View className='section-heading'>
            <Text className='section-heading__title'>动作步骤</Text>
            <Text className='section-heading__meta'>{exercise.instructions.length} 个要点</Text>
          </View>
          <View className='surface-card exercise-steps'>
            {exercise.instructions.map((instruction, index) => (
              <View className='exercise-step' key={`${index}-${instruction}`}>
                <Text className='exercise-step__index data-number'>{String(index + 1).padStart(2, '0')}</Text>
                <Text className='exercise-step__text'>{instruction}</Text>
              </View>
            ))}
          </View>

          <View className='surface-card exercise-safety'>
            <Text className='exercise-safety__title'>安全提醒</Text>
            {exercise.safetyCues.map((cue) => (
              <Text className='exercise-safety__cue' key={cue}>· {cue}</Text>
            ))}
            <Text className='exercise-safety__boundary'>出现疼痛、眩晕或明显不适时停止训练；本说明不替代医疗建议。</Text>
          </View>
        </>
      )}

      {!exercise && message.includes('无法读取') && (
        <Button className='secondary-action exercise-detail-retry' loading={loading} onClick={() => void load()}>重新加载</Button>
      )}
      <Button className='secondary-action exercise-detail-back' onClick={() => void application.navigation.back()}>
        返回训练
      </Button>
    </View>
  )
}
