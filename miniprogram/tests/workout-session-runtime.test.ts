import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ExerciseContent } from '../src/application/content'
import { ApplicationError } from '../src/application/errors'
import {
  chooseOptionalSet,
  completeGeneralWarmup,
  createWorkoutFlow,
  recordWorkoutSet,
  setWorkoutExerciseWeight,
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
    loadStatus: vi.fn(),
    resume: vi.fn(),
    recordSet: vi.fn(),
    chooseOptionalSet: vi.fn(),
    setExerciseWeight: vi.fn(),
    replacementCandidates: vi.fn(),
    replaceCurrentExercise: vi.fn(),
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
      targetRepMin: 8,
      targetRepMax: 12,
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

function createCompletedTuesdayOptionalWorkout(safetyFlag?: 'PAIN' | 'DIZZINESS'): WorkoutFlowState {
  let state = completeGeneralWarmup(createWorkoutFlow({
    clientSessionKey: 'session-tuesday-optional',
    planVersionId: 'plan-tuesday',
    startedAtUtc: '2099-08-21T00:00:00.000Z',
    exercises: [
      {
        snapshotExerciseKey: 'machine-row', exerciseCode: 'MACHINE_SEATED_ROW', name: '器械坐姿划船',
        targetWorkSets: 1, targetReps: 12, restSeconds: 90, weightStatus: 'BODYWEIGHT',
        optionalSetRule: {
          conditionCode: 'TUESDAY_UNDER_42_GOOD_STATE',
          exclusiveChoiceGroup: 'TUESDAY_BONUS',
          additionalSets: 1,
        },
      },
      {
        snapshotExerciseKey: 'dumbbell-curl', exerciseCode: 'DUMBBELL_CURL', name: '哑铃弯举',
        targetWorkSets: 1, targetReps: 12, restSeconds: 60, weightStatus: 'BODYWEIGHT',
        optionalSetRule: {
          conditionCode: 'TUESDAY_UNDER_42_GOOD_STATE',
          exclusiveChoiceGroup: 'TUESDAY_BONUS',
          additionalSets: 1,
        },
      },
    ],
  }))
  state = recordWorkoutSet(state, {
    clientSetKey: 'row-work', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
    actualWeightKg: 0, actualReps: 12,
  })
  return recordWorkoutSet(state, {
    clientSetKey: 'curl-work', exerciseIndex: 1, setType: 'WORK', status: 'COMPLETED',
    actualWeightKg: 0, actualReps: 12, safetyFlag,
  })
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
    application.getExerciseTrend.mockRejectedValue(new Error('offline'))
    application.workouts.setExerciseWeight.mockImplementation(
      async (state: WorkoutFlowState, exerciseIndex: number, weightKg: number) =>
        setWorkoutExerciseWeight(state, exerciseIndex, weightKg),
    )
  })

  it('lets the user choose and record exactly one Tuesday optional set', async () => {
    const initial = createCompletedTuesdayOptionalWorkout()
    application.workouts.loadStatus.mockResolvedValue({ kind: 'ACTIVE', state: initial })
    application.workouts.resume.mockResolvedValue({
      state: initial,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
    application.workouts.chooseOptionalSet.mockImplementation(
      async (state: WorkoutFlowState, group: string, exerciseIndex: number | null) =>
        chooseOptionalSet(state, group, exerciseIndex, new Date().toISOString()),
    )
    application.workouts.recordSet.mockImplementation(
      async (state: WorkoutFlowState, input: RecordWorkoutSetInput) => recordWorkoutSet(state, input),
    )
    application.workouts.flush.mockImplementation(async (state: WorkoutFlowState) => state)
    application.getExercise.mockRejectedValue(new Error('offline'))

    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSessionPage))
    })
    if (!renderer || !lifecycle.didShow) throw new Error('workout page did not initialize')
    const pageRenderer = renderer

    await act(async () => {
      lifecycle.didShow?.()
      await flushPage()
    })
    expect(JSON.stringify(pageRenderer.toJSON())).toContain('固定训练已完成')

    const chooseRow = pageRenderer.root.find(
      (node) => node.type === 'button'
        && JSON.stringify(node.props.children).includes('器械坐姿划船'),
    )
    await act(async () => {
      chooseRow.props.onClick()
      await flushPage()
    })
    expect(JSON.stringify(pageRenderer.toJSON())).toContain('OPTIONAL SET · 1 OF 1')
    expect(JSON.stringify(pageRenderer.toJSON())).toContain('这是本次已选择的唯一补充组')

    await act(async () => {
      completeButton(pageRenderer).props.onClick()
      await flushPage()
    })
    expect(application.workouts.recordSet).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ exerciseIndex: 0, setType: 'EXTRA' }),
    )
    expect(application.navigation.replace).toHaveBeenCalledWith('WORKOUT_SUMMARY')
  })

  it('shows the preserved repetition range and defaults the input to its upper bound', async () => {
    const initial = createReadyWorkout()
    application.workouts.loadStatus.mockResolvedValue({ kind: 'ACTIVE', state: initial })
    application.workouts.resume.mockResolvedValue({
      state: initial,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
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

    const rendered = JSON.stringify(renderer.toJSON())
    expect(rendered).toContain('目标 8～12 次')
    expect(repsInput(renderer).props.value).toBe('12')
  })

  it('renders an equal repetition target as one value instead of a duplicated range', async () => {
    const ready = createReadyWorkout()
    const initial: WorkoutFlowState = {
      ...ready,
      exercises: ready.exercises.map((exercise) => ({
        ...exercise,
        targetRepMin: 12,
        targetRepMax: 12,
      })),
    }
    application.workouts.loadStatus.mockResolvedValue({ kind: 'ACTIVE', state: initial })
    application.workouts.resume.mockResolvedValue({
      state: initial,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
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

    const rendered = JSON.stringify(renderer.toJSON())
    expect(rendered).toContain('目标 12 次')
    expect(rendered).not.toContain('目标 12～12 次')
  })

  it('renders the safety stop instead of an optional-set choice after the final fixed set', async () => {
    const stopped = createCompletedTuesdayOptionalWorkout('DIZZINESS')
    application.workouts.loadStatus.mockResolvedValue({ kind: 'ACTIVE', state: stopped })
    application.workouts.resume.mockResolvedValue({
      state: stopped,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
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

    const rendered = JSON.stringify(renderer.toJSON())
    expect(rendered).toContain('请立即停止训练并寻求身边帮助')
    expect(rendered).toContain('停止训练并查看总结')
    expect(rendered).not.toContain('固定训练已完成')
    expect(rendered).not.toContain('＋1组')
    expect(application.workouts.chooseOptionalSet).not.toHaveBeenCalled()
  })

  it('resets actual reps for the next set, preserves an empty edit, and keeps local guidance offline', async () => {
    const initial = createReadyWorkout()
    application.workouts.loadStatus.mockResolvedValue({ kind: 'ACTIVE', state: initial })
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
    const effortToggle = renderer.root.find(
      (node) => node.type === 'button'
        && node.findAllByType('text').some((text) => text.props.children === '训练余力（可选）'),
    )
    act(() => effortToggle.props.onClick())
    const oneRepInReserve = renderer.root.find(
      (node) => node.type === 'button' && node.props.children === '还能 1 次',
    )
    act(() => oneRepInReserve.props.onClick())
    await act(async () => {
      completeButton(renderer!).props.onClick()
      await flushPage()
    })

    expect(application.workouts.recordSet).toHaveBeenCalledTimes(1)
    expect(application.workouts.recordSet.mock.calls[0][1]).toMatchObject({
      actualReps: 8,
      status: 'COMPLETED',
      rir: '1',
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

  it('shows the typed reason when no reviewed replacement is available', async () => {
    const initial = createReadyWorkout()
    application.workouts.loadStatus.mockResolvedValue({ kind: 'ACTIVE', state: initial })
    application.workouts.resume.mockResolvedValue({
      state: initial,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
    application.workouts.replacementCandidates.mockRejectedValue(
      new ApplicationError('INSUFFICIENT_REPLACEMENTS', 'server detail'),
    )
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

    const replaceButton = renderer.root.find(
      (node) => node.type === 'button'
        && node.props.children === '这个动作今天不合适？更换动作',
    )
    await act(async () => {
      replaceButton.props.onClick()
      await flushPage()
    })

    expect(JSON.stringify(renderer.toJSON()))
      .toContain('没有经审核且同时匹配动作模式、主要肌群、难度和器械的替代动作')
  })

  it('uses the latest valid weight automatically without another confirmation', async () => {
    const bodyweightReady = createReadyWorkout()
    const initial: WorkoutFlowState = {
      ...bodyweightReady,
      clientSessionKey: 'session-formal-weight',
      exercises: bodyweightReady.exercises.map((exercise) => ({
        ...exercise,
        exerciseCode: 'DUMBBELL_SQUAT',
        name: '哑铃深蹲',
        weightStatus: 'KNOWN' as const,
        targetWeightKg: undefined,
        sessionWeightKg: undefined,
      })),
    }
    application.workouts.loadStatus.mockResolvedValue({ kind: 'ACTIVE', state: initial })
    application.workouts.resume.mockResolvedValue({
      state: initial,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
    application.getExerciseTrend.mockResolvedValue({
      points: [
        { completedAt: '2026-08-10T10:00:00Z', topWeightKg: 12.5 },
        { completedAt: '2026-08-12T10:00:00Z', topWeightKg: 14 },
      ],
    })
    application.workouts.recordSet.mockImplementation(
      async (state: WorkoutFlowState, input: RecordWorkoutSetInput) => ({
        ...recordWorkoutSet(state, input),
        restTimer: null,
      }),
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

    expect(application.workouts.setExerciseWeight).toHaveBeenCalledWith(initial, 0, 14)
    const confirmedWeight = renderer.root.find(
      (node) => node.props.className === 'session-formal-weight__value data-number',
    )
    expect(confirmedWeight.props.children.join('')).toBe('14 KG')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('沿用')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('保存本次重量')
    expect(completeButton(renderer).props.disabled).toBe(false)

    await act(async () => {
      completeButton(renderer!).props.onClick()
      await flushPage()
    })
    expect(application.workouts.recordSet).toHaveBeenCalledWith(
      expect.objectContaining({
        exercises: [expect.objectContaining({ sessionWeightKg: 14 })],
      }),
      expect.objectContaining({ actualWeightKg: 14 }),
    )
  })

  it('does not let a late response for the previous exercise replace the current guidance', async () => {
    const initial = createTwoExerciseWorkout()
    const staleResponse = deferred<ExerciseContent>()
    application.workouts.loadStatus.mockResolvedValue({ kind: 'ACTIVE', state: initial })
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

  it('records a typed severe safety flag and stops normal training without diagnosis', async () => {
    const ready = createReadyWorkout()
    const initial: WorkoutFlowState = {
      ...ready,
      clientSessionKey: 'session-safety-without-calibration',
      exercises: ready.exercises.map((exercise) => ({
        ...exercise,
        exerciseCode: 'DUMBBELL_SQUAT',
        name: '哑铃深蹲',
        weightStatus: 'NEEDS_CALIBRATION' as const,
        sessionWeightKg: undefined,
      })),
    }
    application.workouts.loadStatus.mockResolvedValue({ kind: 'ACTIVE', state: initial })
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
      }),
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

    const safetyToggle = renderer.root.find(
      (node) => node.type === 'button' && node.props.children === '疼痛或明显不适',
    )
    act(() => repsInput(renderer!).props.onInput({ detail: { value: '' } }))
    expect(safetyToggle.props.disabled).toBe(false)
    act(() => safetyToggle.props.onClick())
    const severeButton = renderer.root.find(
      (node) => node.type === 'button' && node.props.children === '严重不适',
    )
    await act(async () => {
      severeButton.props.onClick()
      await flushPage()
    })

    expect(application.workouts.recordSet).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        status: 'FAILED', safetyFlag: 'SEVERE_UNWELL', actualWeightKg: 2.5, actualReps: 0,
      }),
    )
    const rendered = JSON.stringify(renderer.toJSON())
    expect(rendered).toContain('请立即停止训练并寻求身边帮助')
    expect(rendered).toContain('当地急救服务')
    expect(rendered).toContain('本提示不作诊断')
    expect(rendered).toContain('停止训练并查看总结')
    expect(renderer.root.findAll(
      (node) => node.type === 'button' && node.props.children === '完成本组',
    )).toHaveLength(0)
  })

  it('keeps a failed draft read retryable and coalesces rapid retry clicks', async () => {
    const initial = createReadyWorkout()
    application.workouts.loadStatus.mockRejectedValueOnce(new Error('storage unavailable'))
    application.workouts.resume.mockResolvedValue({
      state: initial,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
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

    expect(JSON.stringify(renderer.toJSON())).toContain('本地草稿未作改动')
    const retry = renderer.root.find(
      (node) => node.type === 'button' && node.props.children === '重新读取训练记录',
    )
    const pendingLoad = deferred<{ kind: 'ACTIVE'; state: WorkoutFlowState }>()
    application.workouts.loadStatus.mockImplementationOnce(() => pendingLoad.promise)

    act(() => {
      retry.props.onClick()
      retry.props.onClick()
    })
    expect(application.workouts.loadStatus).toHaveBeenCalledTimes(2)

    await act(async () => {
      pendingLoad.resolve({ kind: 'ACTIVE', state: initial })
      await flushPage()
    })

    expect(JSON.stringify(renderer.toJSON())).toContain('自重深蹲')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('重新读取训练记录')
  })

  it('clears a previously rendered workout when foreground recovery reports no active draft', async () => {
    const initial = createReadyWorkout()
    application.workouts.loadStatus
      .mockResolvedValueOnce({ kind: 'ACTIVE', state: initial })
      .mockResolvedValueOnce({ kind: 'NONE' })
    application.workouts.resume.mockResolvedValue({
      state: initial,
      remainingSeconds: 0,
      warmupRemainingSeconds: 0,
      clockRollbackDetected: false,
      syncFailed: false,
    })
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
    expect(JSON.stringify(renderer.toJSON())).toContain('自重深蹲')

    await act(async () => {
      lifecycle.didShow?.()
      await flushPage()
    })

    const rendered = JSON.stringify(renderer.toJSON())
    expect(rendered).toContain('没有可恢复的训练')
    expect(rendered).not.toContain('完成本组')
    expect(rendered).not.toContain('自重深蹲')
  })
})
