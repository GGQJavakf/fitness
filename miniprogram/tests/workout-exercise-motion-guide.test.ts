import { createElement, type ComponentType } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  createWorkoutExerciseMotionGuide,
  loadWorkoutExerciseMotionGuide,
} from '../src/subpackages/workout/components/workout-exercise-motion-guide'

const diagnostics = vi.hoisted(() => ({
  recordStartupFailure: vi.fn(),
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Image: 'image',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/startupDiagnostics', () => ({
  recordStartupFailure: diagnostics.recordStartupFailure,
}))

const { default: ExerciseMotionGuide } = await import(
  '../src/subpackages/exercise-guide/components/exercise-motion-guide'
)

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => {
    resolve = complete
  })
  return { promise, resolve }
}

function renderedText(renderer: ReactTestRenderer): string {
  return renderer.root.findAllByType('text')
    .flatMap((node) => node.children)
    .join('')
}

interface GuideProps {
  readonly exerciseCode: string
  readonly exerciseName: string
  readonly primaryRef?: string
  readonly fallbackRef?: string
  readonly compact?: boolean
}

describe('workout exercise motion guide loader', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads the exercise-guide owner subpackage before evaluating its optional module', async () => {
    const order: string[] = []
    const runtime = {
      loadSubpackage: vi.fn((options: { success: () => void }) => {
        order.push('subpackage')
        options.success()
      }),
    }
    const loadGuide = vi.fn(async () => {
      order.push('module')
      return { default: (() => null) as ComponentType<GuideProps> }
    })
    const WorkoutGuide = createWorkoutExerciseMotionGuide(loadGuide)
    vi.stubGlobal('wx', runtime)

    try {
      await act(async () => {
        TestRenderer.create(createElement(WorkoutGuide, {
          exerciseCode: 'BODYWEIGHT_SQUAT',
          exerciseName: '自重深蹲',
        }))
        await Promise.resolve()
        await Promise.resolve()
      })
    } finally {
      vi.unstubAllGlobals()
    }

    expect(runtime.loadSubpackage).toHaveBeenCalledWith({
      name: 'exercise-guide',
      success: expect.any(Function),
      fail: expect.any(Function),
    })
    expect(order).toEqual(['subpackage', 'module'])
  })

  it('does not evaluate the optional module when the owner subpackage fails to load', async () => {
    const runtime = {
      loadSubpackage: vi.fn((options: { fail: (error: Error) => void }) => {
        options.fail(new Error('exercise-guide subpackage unavailable'))
      }),
    }
    const loadGuide = vi.fn(async () => ({
      default: (() => null) as ComponentType<GuideProps>,
    }))

    await expect(loadWorkoutExerciseMotionGuide(loadGuide, runtime))
      .rejects.toThrow('exercise-guide subpackage unavailable')
    expect(loadGuide).not.toHaveBeenCalled()
  })

  it('renders the workout placeholder before loading the exercise-guide owner chunk', async () => {
    const pending = deferred<{ default: ComponentType<GuideProps> }>()
    const loadGuide = vi.fn(() => pending.promise)
    const LoadedGuide = (props: GuideProps) => createElement('loaded-guide', props)
    const WorkoutGuide = createWorkoutExerciseMotionGuide(loadGuide)
    let renderer!: ReactTestRenderer

    act(() => {
      renderer = TestRenderer.create(createElement(WorkoutGuide, {
        exerciseCode: 'BODYWEIGHT_SQUAT',
        exerciseName: '自重深蹲',
        compact: true,
      }))
    })

    expect(loadGuide).toHaveBeenCalledTimes(1)
    expect(renderedText(renderer)).toContain('正在加载动作示例')
    expect(renderer.root.findAllByType(LoadedGuide)).toHaveLength(0)

    await act(async () => {
      pending.resolve({ default: LoadedGuide })
      await pending.promise
    })

    expect(renderer.root.findByType(LoadedGuide).props).toMatchObject({
      exerciseCode: 'BODYWEIGHT_SQUAT',
      exerciseName: '自重深蹲',
      compact: true,
    })
  })

  it('keeps text guidance usable after load failure and retries with a fresh attempt', async () => {
    const retry = deferred<{ default: ComponentType<GuideProps> }>()
    const LoadedGuide = () => createElement('loaded-guide')
    const loadGuide = vi.fn()
      .mockRejectedValueOnce(new Error('subpackage unavailable'))
      .mockImplementationOnce(() => retry.promise)
    const WorkoutGuide = createWorkoutExerciseMotionGuide(loadGuide)
    let renderer!: ReactTestRenderer

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutGuide, {
        exerciseCode: 'PLANK',
        exerciseName: '平板支撑',
      }))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(renderedText(renderer)).toContain('动作示例暂时无法显示')
    expect(renderedText(renderer)).toContain('诊断码：WL-G02')
    expect(renderedText(renderer)).toContain('训练记录和计时不受影响')
    expect(diagnostics.recordStartupFailure).toHaveBeenCalledWith(
      'WORKOUT_MOTION_GUIDE_LOAD',
      'MODULE_LOAD',
    )

    await act(async () => {
      renderer.root.findByType('button').props.onClick()
      await Promise.resolve()
    })
    expect(loadGuide).toHaveBeenCalledTimes(2)

    await act(async () => {
      retry.resolve({ default: LoadedGuide })
      await retry.promise
    })

    expect(renderer.root.findAllByType(LoadedGuide)).toHaveLength(1)
  })

  it('treats a malformed loaded module as a local guide failure instead of hanging', async () => {
    const WorkoutGuide = createWorkoutExerciseMotionGuide(
      async () => ({}),
    )
    let renderer!: ReactTestRenderer

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutGuide, {
        exerciseCode: 'PLANK',
        exerciseName: '平板支撑',
      }))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(renderedText(renderer)).toContain('动作示例暂时无法显示')
    expect(renderedText(renderer)).toContain('诊断码：WL-G02')
    expect(diagnostics.recordStartupFailure).toHaveBeenCalledWith(
      'WORKOUT_MOTION_GUIDE_LOAD',
      'MODULE_LOAD',
    )
  })

  it('renders the real motion guide after its optional chunk becomes available', async () => {
    const WorkoutGuide = createWorkoutExerciseMotionGuide(
      async () => ({ default: ExerciseMotionGuide }),
    )
    let renderer!: ReactTestRenderer

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutGuide, {
        exerciseCode: 'BODYWEIGHT_SQUAT',
        exerciseName: '自重深蹲',
        compact: true,
      }))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(renderer.root.findAllByType('image')).toHaveLength(1)
    expect(renderedText(renderer)).toContain('步骤插画')
    expect(diagnostics.recordStartupFailure).not.toHaveBeenCalled()
  })

  it('contains a loaded guide render failure inside the optional guide surface', async () => {
    const BrokenGuide = () => {
      throw new Error('private render details must not escape')
    }
    const WorkoutGuide = createWorkoutExerciseMotionGuide(
      async () => ({ default: BrokenGuide }),
    )
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    let renderer!: ReactTestRenderer

    try {
      await act(async () => {
        renderer = TestRenderer.create(createElement(WorkoutGuide, {
          exerciseCode: 'PLANK',
          exerciseName: '平板支撑',
        }))
        await Promise.resolve()
        await Promise.resolve()
      })

      expect(renderedText(renderer)).toContain('动作示例暂时无法显示')
      expect(renderedText(renderer)).toContain('诊断码：WL-G01')
      expect(renderedText(renderer)).toContain('训练记录和计时不受影响')
      expect(diagnostics.recordStartupFailure).toHaveBeenCalledWith(
        'WORKOUT_MOTION_GUIDE_RENDER',
        'RENDER',
      )
    } finally {
      consoleError.mockRestore()
    }
  })
})
