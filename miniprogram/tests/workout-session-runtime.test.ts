import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ExerciseContent } from '../src/application/content'
import {
  completeGeneralWarmup,
  createWorkoutFlow,
  recordWorkoutSet,
  type RecordWorkoutSetInput,
  type WorkoutFlowState,
} from '../src/application/workoutFlow'

const lifecycle = vi.hoisted(() => ({
  didShow: null as (() => void) | null,
}))

const application = vi.hoisted(() => ({
  getExercise: vi.fn(),
  getExerciseTrend: vi.fn(),
  navigation: {
    open: vi.fn(),
    replace: vi.fn(),
  },
  telemetry: {
    track: vi.fn(),
  },
  workouts: {
    load: vi.fn(),
    resume: vi.fn(),
    recordSet: vi.fn(),
    flush: vi.fn(),
  },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Input: 'input',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/lifecycle', () => ({
  useWeappDidShow: (effect: () => void) => {
    lifecycle.didShow = effect
  },
  useWeappDidHide: vi.fn(),
}))

vi.mock('../src/platform/weapp/compositionRoot', () => ({
  getWeappApplication: () => application,
}))

vi.mock('../src/subpackages/exercise-guide/components/exercise-motion-guide', () => ({
  default: 'exercise-motion-guide',
}))

const { default: WorkoutSessionPage } = await import(
  '../src/presentation/pages/workout-session'
)

function createReadyWorkout(): WorkoutFlowState {
  return completeGeneralWarmup(createWorkoutFlow({
    clientSessionKey: 'session-reps-reset',
    planVersionId: 'plan-v1',
    exercises: [{
      snapshotExerciseKey: 'exercise-1',
      exerciseCode: 'BODYWEIGHT_SQUAT',
      name: '自重深蹲',
      targetWorkSets: 2,
      targetReps: 12,
      restSeconds: 90,
      weightStatus: 'BODYWEIGHT',
    }],
  }))
}

function createTwoExerciseWorkout(): WorkoutFlowState {
  return completeGeneralWarmup(createWorkoutFlow({
    clientSessionKey: 'session-guidance-race',
    planVersionId: 'plan-v1',
    exercises: [
      {
        snapshotExerciseKey: 'exercise-1',
        exerciseCode: 'BODYWEIGHT_SQUAT',
        name: '自重深蹲',
        targetWorkSets: 1,
        targetReps: 12,
        restSeconds: 90,
        weightStatus: 'BODYWEIGHT',
      },
      {
        snapshotExerciseKey: 'exercise-2',
        exerciseCode: 'PLANK',
        name: '平板支撑',
        targetWorkSets: 1,
        targetReps: 30,
        restSeconds: 60,
        weightStatus: 'BODYWEIGHT',
      },
    ],
  }))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => {
    resolve = complete
  })
  return { promise, resolve }
}

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function repsInput(renderer: ReactTestRenderer) {
  return renderer.root.find(
    (node) => node.type === 'input'
      && node.props.className === 'metric-input'
      && node.props.type === 'number'
  )
}

function completeButton(renderer: ReactTestRenderer) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === '完成本组'
  )
}

describe('live workout page behavior', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    lifecycle.didShow = null
  })

  it('resets actual reps for the next set, preserves an empty edit, and keeps local guidance offline', async () => {
    const initial = createReadyWorkout()
    application.workouts.load.mockResolvedValue(initial)
    application.workouts.resume.mockResolvedValue({
      state: initial,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
    application.workouts.recordSet.mockImplementation(
      async (state: WorkoutFlowState, input: RecordWorkoutSetInput) => ({
        ...recordWorkoutSet(state, input),
        restTimer: null,
      })
    )
    application.workouts.flush.mockImplementation(async (state: WorkoutFlowState) => state)
    application.getExercise.mockRejectedValue(new Error('offline'))

    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSessionPage))
    })
    if (!renderer || !lifecycle.didShow) throw new Error('workout page did not initialize')

    await act(async () => {
      lifecycle.didShow?.()
      await flushPage()
    })

    expect(repsInput(renderer).props.value).toBe('12')
    expect(JSON.stringify(renderer.toJSON())).toContain('膝盖方向与脚尖保持一致')
    expect(JSON.stringify(renderer.toJSON())).toContain('本地安全指导')

    act(() => repsInput(renderer!).props.onInput({ detail: { value: '8' } }))
    await act(async () => {
      completeButton(renderer!).props.onClick()
      await flushPage()
    })

    expect(application.workouts.recordSet).toHaveBeenCalledTimes(1)
    expect(application.workouts.recordSet.mock.calls[0][1]).toMatchObject({
      actualReps: 8,
      status: 'COMPLETED',
    })
    expect(repsInput(renderer).props.value).toBe('12')

    act(() => repsInput(renderer!).props.onInput({ detail: { value: '' } }))
    expect(repsInput(renderer).props.value).toBe('')
    await act(async () => {
      completeButton(renderer!).props.onClick()
      await flushPage()
    })

    expect(application.workouts.recordSet).toHaveBeenCalledTimes(1)
    expect(JSON.stringify(renderer.toJSON())).toContain('实际次数必须是正整数')
  })

  it('does not let a late response for the previous exercise replace the current guidance', async () => {
    const initial = createTwoExerciseWorkout()
    const staleResponse = deferred<ExerciseContent>()
    application.workouts.load.mockResolvedValue(initial)
    application.workouts.resume.mockResolvedValue({
      state: initial,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
    application.workouts.recordSet.mockImplementation(
      async (state: WorkoutFlowState, input: RecordWorkoutSetInput) => ({
        ...recordWorkoutSet(state, input),
        restTimer: null,
      })
    )
    application.workouts.flush.mockImplementation(async (state: WorkoutFlowState) => state)
    application.getExercise.mockImplementation((exerciseCode: string) =>
      exerciseCode === 'BODYWEIGHT_SQUAT'
        ? staleResponse.promise
        : Promise.reject(new Error('offline')))

    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSessionPage))
    })
    if (!renderer || !lifecycle.didShow) throw new Error('workout page did not initialize')
    await act(async () => {
      lifecycle.didShow?.()
      await flushPage()
    })

    await act(async () => {
      completeButton(renderer!).props.onClick()
      await flushPage()
    })
    expect(JSON.stringify(renderer.toJSON())).toContain('用前臂和脚尖支撑身体')

    await act(async () => {
      staleResponse.resolve({
        id: 'stale-exercise',
        code: 'BODYWEIGHT_SQUAT',
        name: '自重深蹲',
        plainLanguage: '迟到的旧动作说明',
        movementPattern: 'SQUAT',
        difficulty: 'BEGINNER',
        equipment: ['BODYWEIGHT'],
        primaryMuscles: ['LEGS'],
        instructions: ['迟到的旧动作步骤'],
        safetyCues: ['迟到的旧安全提醒'],
        image: {
          primaryRef: 'asset://exercise-guides/bodyweight-squat-01-setup.jpg',
          fallbackRef: 'asset://exercise-placeholder',
        },
        alternatives: [],
        contentVersion: 'test',
      })
      await flushPage()
    })

    const rendered = JSON.stringify(renderer.toJSON())
    expect(rendered).toContain('用前臂和脚尖支撑身体')
    expect(rendered).not.toContain('迟到的旧动作说明')
  })
})
