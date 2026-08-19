import { Button, Image, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'

import { resolveExerciseGuide } from './assets'
import './index.scss'

interface ExerciseMotionGuideProps {
  readonly exerciseCode: string
  readonly exerciseName: string
  readonly primaryRef?: string
  readonly fallbackRef?: string
  readonly compact?: boolean
}

export default function ExerciseMotionGuide({
  exerciseCode,
  exerciseName,
  primaryRef,
  fallbackRef,
  compact = false,
}: ExerciseMotionGuideProps) {
  const guide = resolveExerciseGuide(primaryRef, exerciseCode, fallbackRef)
  const [activeStageIndex, setActiveStageIndex] = useState(0)
  const [failedStageIds, setFailedStageIds] = useState<readonly string[]>([])

  useEffect(() => {
    setActiveStageIndex(0)
    setFailedStageIds([])
  }, [exerciseCode, guide?.primaryRef])

  const activeStage = guide?.stages[activeStageIndex] ?? guide?.stages[0]
  const activeStageFailed = activeStage
    ? failedStageIds.includes(activeStage.id)
    : false

  return (
    <View
      className={[
        'motion-guide',
        compact ? 'motion-guide--compact' : '',
      ].filter(Boolean).join(' ')}
      aria-label={
        guide
          ? guide.kind === 'STATIC_COVER'
            ? `${exerciseName}合规静态封面`
            : `${exerciseName}动作步骤插画`
          : `${exerciseName}文字动作指导`
      }
    >
      <View className='motion-guide__stage'>
        {activeStage && !activeStageFailed ? (
          <Image
            key={`${exerciseCode}-${activeStage.id}`}
            className='motion-guide__image'
            lazyLoad={false}
            mode='aspectFit'
            src={activeStage.source}
            onError={() => setFailedStageIds((current) => (
              current.includes(activeStage.id)
                ? current
                : [...current, activeStage.id]
            ))}
          />
        ) : (
          <View className='motion-guide__fallback'>
            <Text className='motion-guide__fallback-title'>
              {guide ? '静态示例暂时无法显示' : '动作示例暂未补齐'}
            </Text>
            <Text className='motion-guide__fallback-note'>请继续按下方动作步骤练习</Text>
          </View>
        )}
        {activeStage && !activeStageFailed && (guide?.stages.length ?? 0) > 1 && (
          <Text className='motion-guide__badge'>
            {activeStageIndex + 1} / {guide?.stages.length}
          </Text>
        )}
      </View>

      {guide && guide.stages.length > 1 && (
        <View
          className={[
            'motion-guide__stage-tabs',
            `motion-guide__stage-tabs--${guide.stages.length}`,
          ].join(' ')}
        >
          {guide.stages.map((stage, index) => (
            <Button
              key={stage.id}
              id={`motion-guide-stage-${index + 1}`}
              className={[
                'motion-guide__stage-tab',
                index === activeStageIndex ? 'motion-guide__stage-tab--active' : '',
              ].filter(Boolean).join(' ')}
              hoverClass='motion-guide__stage-tab--pressed'
              hoverStayTime={80}
              aria-label={`查看${stage.label}姿势`}
              onClick={() => setActiveStageIndex(index)}
            >
              <Text className='motion-guide__stage-tab-index'>
                {String(index + 1).padStart(2, '0')}
              </Text>
              <Text className='motion-guide__stage-tab-label'>{stage.label}</Text>
            </Button>
          ))}
        </View>
      )}

      <View className='motion-guide__caption'>
        <Text className='motion-guide__title'>
          {guide
            ? guide.kind === 'STATIC_COVER'
              ? `合规静态封面 · ${exerciseName}`
              : `步骤插画 · ${exerciseName}`
            : `文字动作指导 · ${exerciseName}`}
        </Text>
        <Text className='motion-guide__note'>
          {activeStage && !activeStageFailed
            ? activeStage.description
            : '分解图不可用时仍可按动作步骤、呼吸提示和安全提醒完成练习。'}
        </Text>
      </View>
    </View>
  )
}
