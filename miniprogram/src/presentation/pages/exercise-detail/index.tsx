import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import type { ExerciseContent } from '../../../application/content'
import { getWeappApplication } from '../../../platform/weapp/compositionRoot'

import './index.scss'

const application = getWeappApplication()

const difficultyLabels: Record<ExerciseContent['difficulty'], string> = {
  BEGINNER: '基础',
  INTERMEDIATE: '进阶',
  ADVANCED: '高级',
}

const muscleLabels: Record<string, string> = {
  BACK: '背部',
  BICEPS: '肱二头肌',
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
  const [exercise, setExercise] = useState<ExerciseContent | null>(null)
  const [message, setMessage] = useState('正在读取动作说明…')
  const exerciseCode = application.routeParameter('exerciseCode')

  useEffect(() => {
    if (!exerciseCode) {
      setMessage('缺少动作信息，请返回上一页重试。')
      return
    }
    application.getExercise(exerciseCode)
      .then((value) => {
        setExercise(value)
        setMessage('')
      })
      .catch(() => setMessage('动作说明暂时无法读取，请检查网络后重试。'))
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
          <View className='exercise-visual' aria-label={`${exercise.name}动作示意区域`}>
            <View className='exercise-visual__figure'>
              <View className='exercise-visual__head' />
              <View className='exercise-visual__body' />
              <View className='exercise-visual__base' />
            </View>
            <Text className='exercise-visual__label'>动作示意资源待专业审核后替换</Text>
          </View>

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

      <Button className='secondary-action exercise-detail-back' onClick={() => void application.navigation.back()}>
        返回训练
      </Button>
    </View>
  )
}
