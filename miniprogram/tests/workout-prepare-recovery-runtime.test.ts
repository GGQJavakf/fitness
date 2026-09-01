import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { WorkoutReplacementWorkflowError } from '../src/application/workoutReplacementWorkflow'

const application = vi.hoisted(() => ({
  routeParameter: vi.fn(),
  loadActivePlan: vi.fn(),
  listWorkoutHistory: vi.fn(),
  workoutStart: { start: vi.fn(), replaceActive: vi.fn(), cancelUncreatedStart: vi.fn() },
  workouts: { abandonActive: vi.fn() },
  nextTrainingDaySelection: { consume: vi.fn() },
  loadWorkoutPreparation: vi.fn(),
  startWorkout: vi.fn(),
  abandonAndStartWorkout: vi.fn(),
  cancelWorkoutStartAndOpenPlan: vi.fn(),
  navigation: { open: vi.fn(), replace: vi.fn() },
  telemetry: { track: vi.fn() },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/featureRoots/workoutCompositionRoot', () => ({
  getWorkoutApplication: () => application,
}))

const { default: WorkoutPreparePage } = await import('../src/presentation/pages/workout-prepare')

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

const activePlan = {
  planId: '00000000-0000-4000-8000-000000000001',
  activeVersion: {
    id: 'plan-version-1',
    planId: '00000000-0000-4000-8000-000000000001',
    versionNumber: 1,
    sourceType: 'INITIAL',
    plan: {
      templateCode: 'template-1',
      name: '基础计划',
      days: [{
        code: 'DAY_1',
        name: '上肢训练',
        exercises: [{
          exerciseCode: 'DUMBBELL_BENCH_PRESS',
          workSets: 3,
          repMin: 8,
          repMax: 12,
          restSeconds: 90,
          weightStatus: 'KNOWN',
        }],
      }, {
        code: 'DAY_2',
        name: '下肢训练',
        exercises: [{
          exerciseCode: 'GOBLET_SQUAT',
          workSets: 3,
          repMin: 8,
          repMax: 12,
          restSeconds: 90,
          weightStatus: 'KNOWN',
        }],
      }],
      locks: {},
    },
    ruleReference: { ruleVersion: 'rules-v1', templateVersion: 'template-v1', contentVersion: 'content-v1' },
    confirmedWarningCodes: [],
    createdAt: '2026-08-11T00:00:00Z',
  },
}

const warning = {
  decision: 'CONFIRMATION_REQUIRED',
  policyVersion: 'rules-v1',
  checkedAt: '2026-08-11T08:00:00Z',
  minimumRecoveryHours: 48,
  affectedMuscles: [{
    muscleGroup: 'CHEST',
    elapsedHours: 18,
    minimumRecoveryHours: 48,
    lastCompletedAt: '2026-08-10T14:00:00Z',
  }, {
    muscleGroup: 'CALVES',
    elapsedHours: 20,
    minimumRecoveryHours: 48,
    lastCompletedAt: '2026-08-10T12:00:00Z',
  }],
}

describe('workout prepare recovery interaction', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    application.nextTrainingDaySelection.consume.mockResolvedValue(undefined)
    application.routeParameter.mockReturnValue(undefined)
    application.loadActivePlan.mockResolvedValue(activePlan)
    application.listWorkoutHistory.mockResolvedValue({ items: [] })
    application.loadWorkoutPreparation.mockImplementation(async () => {
      let historyUnavailable = false
      const [plan, history] = await Promise.all([
        application.loadActivePlan(),
        application.listWorkoutHistory(undefined, 50).catch(() => {
          historyUnavailable = true
          return { items: [] }
        }),
      ])
      const rememberedTrainingDayCode = await application.nextTrainingDaySelection.consume()
      return { plan, history, historyUnavailable, rememberedTrainingDayCode }
    })
    application.startWorkout.mockImplementation(async (input) => {
      const result = await application.workoutStart.start(input)
      if (result.kind === 'STARTED' || result.kind === 'RESUMED') {
        await application.navigation.replace('WORKOUT_SESSION')
      }
      return result
    })
    application.abandonAndStartWorkout.mockImplementation(async (state, input, observer) => {
      observer?.onPhaseChanged('ENDING_ACTIVE')
      const result = await application.workoutStart.replaceActive(state, input)
      if (result.kind === 'STARTED' || result.kind === 'RESUMED') {
        observer?.onPhaseChanged('OPENING_NEW')
        await application.navigation.replace('WORKOUT_SESSION')
      }
      return result
    })
    application.cancelWorkoutStartAndOpenPlan.mockImplementation(async (input) => {
      await application.workoutStart.cancelUncreatedStart(input)
      await application.navigation.replace('PLAN')
      return true
    })
  })

  it('uses the next training day explicitly passed by the completed workout summary', async () => {
    application.nextTrainingDaySelection.consume.mockResolvedValueOnce('DAY_2')
    application.listWorkoutHistory.mockResolvedValue({
      items: [{
        sessionId: 'latest-day-2',
        trainingDayCode: 'DAY_2',
        trainingDayName: '下肢训练',
        status: 'COMPLETED',
        startedAt: '2026-08-15T08:00:00Z',
        completedAt: '2026-08-15T09:00:00Z',
        completedWorkSets: 3,
        completedVolumeKg: 300,
        completedReps: 36,
        usesExternalLoad: true,
      }],
    })
    application.workoutStart.start.mockResolvedValue({
      kind: 'STARTED',
      state: { exercises: [{}] },
    })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('下肢训练')
    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })
    expect(application.workoutStart.start).toHaveBeenCalledWith(expect.objectContaining({
      planDayId: 'DAY_2',
    }))
    expect(application.nextTrainingDaySelection.consume).toHaveBeenCalledOnce()
  })

  it('captures the routed training day before asynchronous plan loading loses the page context', async () => {
    const planRead = deferred<typeof activePlan>()
    let routeContextAvailable = true
    application.loadActivePlan.mockReturnValueOnce(planRead.promise)
    application.routeParameter.mockImplementation(() => routeContextAvailable ? 'DAY_2' : undefined)
    application.workoutStart.start.mockResolvedValue({
      kind: 'STARTED',
      state: { exercises: [{}] },
    })

    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    routeContextAvailable = false
    await act(async () => {
      planRead.resolve(activePlan)
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })
    expect(application.workoutStart.start).toHaveBeenCalledWith(expect.objectContaining({
      planDayId: 'DAY_2',
    }))
  })

  it('distinguishes a plan read failure from a genuinely missing plan', async () => {
    application.loadActivePlan.mockRejectedValueOnce(new Error('offline'))
    let failedRenderer: ReactTestRenderer | undefined
    await act(async () => {
      failedRenderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!failedRenderer) throw new Error('workout prepare page did not render')

    const failed = JSON.stringify(failedRenderer.toJSON())
    expect(failed).toContain('训练计划读取失败')
    expect(failed).not.toContain('还没有可开始的计划')
    act(() => failedRenderer!.unmount())

    application.loadActivePlan.mockResolvedValueOnce(null)
    let emptyRenderer: ReactTestRenderer | undefined
    await act(async () => {
      emptyRenderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!emptyRenderer) throw new Error('workout prepare page did not render')
    expect(JSON.stringify(emptyRenderer.toJSON())).toContain('还没有可开始的计划')
  })

  it('shows all three explicit choices and creates nothing until continue is confirmed', async () => {
    application.workoutStart.start
      .mockResolvedValueOnce({
        kind: 'RECOVERY_CONFIRMATION_REQUIRED',
        assessment: warning,
        confirmationToken: 'recovery-confirmation-token',
        confirmationExpiresAt: '2026-08-11T08:05:00Z',
      })
      .mockResolvedValueOnce({ kind: 'STARTED', state: { exercises: [{}] } })
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })

    expect(application.navigation.replace).not.toHaveBeenCalledWith('WORKOUT_SESSION')
    expect(JSON.stringify(renderer.toJSON())).toContain('胸部')
    expect(JSON.stringify(renderer.toJSON())).toContain('小腿')
    expect(JSON.stringify(renderer.toJSON())).toContain('已过 18 小时，规则最低 48 小时')
    expect(() => button(renderer!, '选择其他训练日')).not.toThrow()
    expect(() => button(renderer!, '暂不训练')).not.toThrow()
    expect(() => button(renderer!, '仍继续本次训练')).not.toThrow()

    await act(async () => {
      button(renderer!, '仍继续本次训练').props.onClick()
      await flushPage()
    })

    expect(application.workoutStart.start).toHaveBeenNthCalledWith(2, expect.objectContaining({
      recoveryConfirmationToken: 'recovery-confirmation-token',
    }))
    expect(application.navigation.replace).toHaveBeenCalledWith('WORKOUT_SESSION')
  })

  it('clears an uncreated recovery challenge before selecting another training day', async () => {
    application.workoutStart.start.mockResolvedValueOnce({
      kind: 'RECOVERY_CONFIRMATION_REQUIRED',
      assessment: warning,
      confirmationToken: 'recovery-confirmation-token',
      confirmationExpiresAt: '2026-08-11T08:05:00Z',
    })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')
    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })

    await act(async () => {
      button(renderer!, '选择其他训练日').props.onClick()
      await flushPage()
    })

    expect(application.workoutStart.cancelUncreatedStart).toHaveBeenCalledWith(expect.objectContaining({
      planId: activePlan.planId,
      planVersionNo: activePlan.activeVersion.versionNumber,
      planDayId: 'DAY_1',
    }))
    expect(JSON.stringify(renderer.toJSON())).toContain('请选择其他训练日')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('恢复窗口尚未满足')
  })

  it('keeps the recovery challenge visible when the uncreated start intent cannot be cleared', async () => {
    application.workoutStart.start.mockResolvedValueOnce({
      kind: 'RECOVERY_CONFIRMATION_REQUIRED',
      assessment: warning,
      confirmationToken: 'recovery-confirmation-token',
      confirmationExpiresAt: '2026-08-11T08:05:00Z',
    })
    application.workoutStart.cancelUncreatedStart.mockRejectedValueOnce(new Error('storage unavailable'))
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')
    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })

    await act(async () => {
      button(renderer!, '选择其他训练日').props.onClick()
      await flushPage()
    })

    expect(application.navigation.replace).not.toHaveBeenCalledWith('PLAN')
    expect(JSON.stringify(renderer.toJSON())).toContain('恢复窗口尚未满足')
    expect(JSON.stringify(renderer.toJSON())).toContain('暂时无法安全清除上次待启动记录')
  })

  it('starts the training day explicitly selected by the user', async () => {
    application.workoutStart.start.mockResolvedValue({
      kind: 'STARTED',
      state: { exercises: [{ exerciseCode: 'GOBLET_SQUAT' }] },
    })
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    const lowerBodyDay = renderer.root.findAllByType('button').find((candidate) =>
      candidate.findAllByType('text').some((text) => text.props.children === '下肢训练'))
    if (!lowerBodyDay) throw new Error('second training day was not rendered')

    act(() => lowerBodyDay.props.onClick())

    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })

    expect(application.workoutStart.start).toHaveBeenCalledTimes(1)
    expect(application.workoutStart.start).toHaveBeenCalledWith(expect.objectContaining({
      planId: activePlan.planId,
      planVersionNo: activePlan.activeVersion.versionNumber,
      planDayId: 'DAY_2',
    }))
    expect(application.navigation.replace).toHaveBeenCalledWith('WORKOUT_SESSION')
  })

  it('asks for one fresh click after a durable start key points to an ended workout', async () => {
    application.workoutStart.start.mockResolvedValueOnce({ kind: 'TERMINAL_REPLAY' })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })

    expect(application.navigation.replace).not.toHaveBeenCalledWith('WORKOUT_SESSION')
    expect(JSON.stringify(renderer.toJSON())).toContain('上次训练已经结束')
    expect(application.workoutStart.start).toHaveBeenCalledOnce()
  })

  it('does not guess the first day when history is unavailable and requires retry or an explicit choice', async () => {
    application.listWorkoutHistory
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({
        items: [{
          sessionId: 'previous-day-1',
          trainingDayCode: 'DAY_1',
          trainingDayName: '上肢训练',
          status: 'COMPLETED',
          startedAt: '2026-08-10T08:00:00Z',
          completedAt: '2026-08-10T09:00:00Z',
          completedWorkSets: 3,
          completedVolumeKg: 300,
          completedReps: 24,
          usesExternalLoad: true,
        }],
        hasMore: false,
      })
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('没有自动猜测训练日')
    expect(button(renderer, '开始热身').props.disabled).toBe(true)
    act(() => button(renderer!, '开始热身').props.onClick())
    expect(application.workoutStart.start).not.toHaveBeenCalled()

    await act(async () => {
      button(renderer!, '重新判断训练日').props.onClick()
      await flushPage()
    })

    expect(application.listWorkoutHistory).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('下肢训练')
    expect(button(renderer, '开始热身').props.disabled).toBe(false)
  })

  it('coalesces rapid plan reloads instead of issuing duplicate history requests', async () => {
    application.listWorkoutHistory.mockRejectedValueOnce(new Error('offline'))
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    const retry = button(renderer, '重新判断训练日')
    let releaseHistory: ((value: { items: never[] }) => void) | undefined
    application.listWorkoutHistory.mockImplementationOnce(() => new Promise((resolve) => {
      releaseHistory = resolve
    }))

    act(() => {
      retry.props.onClick()
      retry.props.onClick()
    })
    expect(application.listWorkoutHistory).toHaveBeenCalledTimes(2)

    await act(async () => {
      releaseHistory?.({ items: [] })
      await flushPage()
    })
  })

  it('coalesces rapid start clicks before the disabled state renders', async () => {
    const start = deferred<{ kind: 'STARTED'; state: { exercises: never[] } }>()
    application.workoutStart.start.mockReturnValueOnce(start.promise)
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    const action = button(renderer, '开始热身')
    act(() => {
      action.props.onClick()
      action.props.onClick()
    })
    expect(application.workoutStart.start).toHaveBeenCalledOnce()

    await act(async () => {
      start.resolve({ kind: 'STARTED', state: { exercises: [] } })
      await flushPage()
    })
    expect(application.navigation.replace).toHaveBeenCalledWith('WORKOUT_SESSION')
  })

  it('keeps replacement and page-opening progress visible until navigation completes', async () => {
    application.workoutStart.start.mockResolvedValueOnce({
      kind: 'RESUME_REQUIRED',
      state: { clientSessionKey: 'unfinished-workout', exercises: [] },
    })
    const replacement = deferred<{ kind: 'STARTED'; state: { exercises: never[] } }>()
    application.abandonAndStartWorkout.mockReturnValueOnce(replacement.promise)
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })

    const replaceAction = button(renderer, '结束原训练并开始新的')
    act(() => {
      replaceAction.props.onClick()
      replaceAction.props.onClick()
    })
    const endingAction = button(renderer, '正在结束原训练…')
    expect(endingAction.props.loading).toBe(true)
    expect(endingAction.props.disabled).toBe(true)
    expect(application.abandonAndStartWorkout).toHaveBeenCalledOnce()

    const observer = application.abandonAndStartWorkout.mock.calls[0][2]
    act(() => observer.onPhaseChanged('OPENING_NEW'))
    const openingAction = button(renderer, '正在打开新训练…')
    expect(openingAction.props.loading).toBe(true)
    expect(openingAction.props.disabled).toBe(true)

    await act(async () => {
      replacement.resolve({ kind: 'STARTED', state: { exercises: [] } })
      await flushPage()
    })
    expect(JSON.stringify(renderer.toJSON())).not.toContain('正在打开新训练')
  })

  it('offers recovery when the replacement started but its page handoff failed', async () => {
    application.workoutStart.start.mockResolvedValueOnce({
      kind: 'RESUME_REQUIRED',
      state: { clientSessionKey: 'unfinished-workout', exercises: [] },
    })
    application.abandonAndStartWorkout.mockImplementationOnce(async (_state, _input, observer) => {
      observer.onPhaseChanged('OPENING_NEW')
      const failure = new Error('workout page handoff failed')
      throw new WorkoutReplacementWorkflowError('OPENING_NEW', failure)
    })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })
    await act(async () => {
      button(renderer!, '结束原训练并开始新的').props.onClick()
      await flushPage()
    })

    expect(JSON.stringify(renderer.toJSON())).not.toContain('结束原训练并开始新的')
    expect(JSON.stringify(renderer.toJSON())).toContain('新训练已创建')
    expect(button(renderer, '开始热身').props.disabled).toBe(false)

    application.workoutStart.start.mockResolvedValueOnce({
      kind: 'STARTED',
      state: { exercises: [] },
    })
    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })
    expect(application.navigation.replace).toHaveBeenCalledWith('WORKOUT_SESSION')
  })

  it('submits a replacement recovery confirmation through the atomic replacement path', async () => {
    application.workoutStart.start.mockResolvedValueOnce({
      kind: 'RESUME_REQUIRED',
      state: { clientSessionKey: 'unfinished-workout', exercises: [] },
    })
    application.workoutStart.replaceActive
      .mockResolvedValueOnce({
        kind: 'RECOVERY_CONFIRMATION_REQUIRED',
        assessment: warning,
        confirmationToken: 'replacement-confirmation-token',
        confirmationExpiresAt: '2026-08-11T08:05:00Z',
      })
      .mockResolvedValueOnce({ kind: 'STARTED', state: { exercises: [] } })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutPreparePage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout prepare page did not render')

    await act(async () => {
      button(renderer!, '开始热身').props.onClick()
      await flushPage()
    })
    await act(async () => {
      button(renderer!, '结束原训练并开始新的').props.onClick()
      await flushPage()
    })
    expect(JSON.stringify(renderer.toJSON())).toContain('恢复窗口尚未满足')

    await act(async () => {
      button(renderer!, '仍继续本次训练').props.onClick()
      await flushPage()
    })

    expect(application.workoutStart.replaceActive).toHaveBeenCalledTimes(2)
    expect(application.workoutStart.replaceActive).toHaveBeenLastCalledWith(
      expect.objectContaining({ clientSessionKey: 'unfinished-workout' }),
      expect.objectContaining({ recoveryConfirmationToken: 'replacement-confirmation-token' }),
    )
    expect(application.workoutStart.start).toHaveBeenCalledOnce()
    expect(application.navigation.replace).toHaveBeenCalledWith('WORKOUT_SESSION')
  })
})
