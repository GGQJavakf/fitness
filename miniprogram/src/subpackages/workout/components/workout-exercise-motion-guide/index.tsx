import { Button, Text, View } from '@tarojs/components'
import {
  Component,
  type ComponentType,
  type ReactNode,
  useEffect,
  useState,
} from 'react'

import { recordStartupFailure } from '../../../../platform/weapp/startupDiagnostics'

interface WorkoutExerciseMotionGuideProps {
  readonly exerciseCode: string
  readonly exerciseName: string
  readonly primaryRef?: string
  readonly fallbackRef?: string
  readonly compact?: boolean
}

interface ExerciseMotionGuideModule {
  readonly default?: ComponentType<WorkoutExerciseMotionGuideProps>
}

type ExerciseMotionGuideLoader = () => Promise<ExerciseMotionGuideModule>

interface ExerciseGuideSubpackageRuntime {
  loadSubpackage?(options: {
    name: string
    success: () => void
    fail: (error: unknown) => void
  }): unknown
}

declare const wx: ExerciseGuideSubpackageRuntime | undefined

interface GuideRenderBoundaryProps {
  readonly children: ReactNode
  readonly fallback: ReactNode
}

interface GuideRenderBoundaryState {
  readonly failed: boolean
}

class GuideRenderBoundary extends Component<
  GuideRenderBoundaryProps,
  GuideRenderBoundaryState
> {
  state: GuideRenderBoundaryState = { failed: false }

  static getDerivedStateFromError(): GuideRenderBoundaryState {
    return { failed: true }
  }

  componentDidCatch(): void {
    recordStartupFailure('WORKOUT_MOTION_GUIDE_RENDER', 'RENDER')
  }

  render(): ReactNode {
    return this.state.failed ? this.props.fallback : this.props.children
  }
}

function GuideFallback(props: WorkoutExerciseMotionGuideProps & {
  readonly failureCode?: 'WL-G01' | 'WL-G02'
  readonly loading?: boolean
  readonly onRetry?: () => void
}) {
  const failed = Boolean(props.failureCode)
  return (
    <View
      className={[
        'motion-guide',
        props.compact ? 'motion-guide--compact' : '',
      ].filter(Boolean).join(' ')}
      aria-label={`${props.exerciseName}动作示例${failed ? '加载失败' : '加载中'}`}
    >
      <View className='motion-guide__stage'>
        <View className='motion-guide__fallback'>
          <Text className='motion-guide__fallback-title'>
            {props.loading ? '正在加载动作示例…' : '动作示例暂时无法显示'}
          </Text>
          <Text className='motion-guide__fallback-note'>
            训练记录和计时不受影响，可继续按下方文字步骤练习
          </Text>
          {props.failureCode && (
            <Text className='motion-guide__fallback-code'>
              诊断码：{props.failureCode}
            </Text>
          )}
          {props.onRetry && (
            <Button
              className='motion-guide__stage-tab'
              onClick={props.onRetry}
            >
              重新加载动作示例
            </Button>
          )}
        </View>
      </View>
    </View>
  )
}

export async function loadWorkoutExerciseMotionGuide(
  loadGuide: ExerciseMotionGuideLoader,
  runtime: ExerciseGuideSubpackageRuntime | undefined = typeof wx === 'undefined' ? undefined : wx,
): Promise<ExerciseMotionGuideModule> {
  const loadSubpackage = runtime?.loadSubpackage
  if (loadSubpackage) {
    await new Promise<void>((resolve, reject) => {
      loadSubpackage.call(runtime, {
        name: 'exercise-guide',
        success: resolve,
        fail: reject,
      })
    })
  }
  return loadGuide()
}

export function createWorkoutExerciseMotionGuide(
  loadGuide: ExerciseMotionGuideLoader,
): ComponentType<WorkoutExerciseMotionGuideProps> {
  let loadedGuide: ComponentType<WorkoutExerciseMotionGuideProps> | undefined
  let loadingGuide: Promise<ComponentType<WorkoutExerciseMotionGuideProps>> | undefined

  function load(): Promise<ComponentType<WorkoutExerciseMotionGuideProps>> {
    if (loadedGuide) return Promise.resolve(loadedGuide)
    if (loadingGuide) return loadingGuide
    loadingGuide = loadWorkoutExerciseMotionGuide(loadGuide)
      .then((module) => {
        if (typeof module.default !== 'function') {
          throw new Error('exercise motion guide module is missing its default component')
        }
        loadedGuide = module.default
        loadingGuide = undefined
        return loadedGuide
      })
      .catch((error: unknown) => {
        loadingGuide = undefined
        throw error
      })
    return loadingGuide
  }

  function WorkoutExerciseMotionGuideContent(props: WorkoutExerciseMotionGuideProps) {
    const [Guide, setGuide] = useState<
      ComponentType<WorkoutExerciseMotionGuideProps> | undefined
    >(loadedGuide)
    const [failed, setFailed] = useState(false)
    const [attempt, setAttempt] = useState(0)

    useEffect(() => {
      if (Guide) return
      let active = true
      setFailed(false)
      void load()
        .then((component) => {
          if (active) setGuide(() => component)
        })
        .catch(() => {
          if (active) {
            recordStartupFailure('WORKOUT_MOTION_GUIDE_LOAD', 'MODULE_LOAD')
            setFailed(true)
          }
        })
      return () => {
        active = false
      }
    }, [Guide, attempt])

    if (Guide) {
      return (
        <GuideRenderBoundary
          key={`${props.exerciseCode}-${attempt}`}
          fallback={<GuideFallback {...props} failureCode='WL-G01' />}
        >
          <Guide {...props} />
        </GuideRenderBoundary>
      )
    }

    return <GuideFallback
      {...props}
      failureCode={failed ? 'WL-G02' : undefined}
      loading={!failed}
      onRetry={failed ? () => setAttempt((value) => value + 1) : undefined}
    />
  }

  return function WorkoutExerciseMotionGuide(props) {
    return (
      <GuideRenderBoundary
        key={`${props.exerciseCode}-surface`}
        fallback={<GuideFallback {...props} failureCode='WL-G01' />}
      >
        <WorkoutExerciseMotionGuideContent {...props} />
      </GuideRenderBoundary>
    )
  }
}

const WorkoutExerciseMotionGuide = createWorkoutExerciseMotionGuide(
  () => import(
    /* webpackChunkName: "subpackages/exercise-guide/async/detail" */
    '../../../exercise-guide/components/exercise-motion-guide'
  ),
)

export default WorkoutExerciseMotionGuide
