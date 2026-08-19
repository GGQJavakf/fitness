import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  completeGeneralWarmup,
  createWorkoutFlow,
  recordWorkoutSet,
} from '../src/application/workoutFlow'
import { ApplicationError } from '../src/application/errors'

const application = vi.hoisted(() => ({
  routeParameter: vi.fn(),
  requestWorkoutSummary: vi.fn(),
  getWorkoutSessionSummary: vi.fn(),
  loadActivePlan: vi.fn(),
  listWorkoutHistory: vi.fn(),
  workouts: {
    load: vi.fn(),
    complete: vi.fn(),
    discardOrphanedLocalWorkout: vi.fn(),
  },
  telemetry: {
    track: vi.fn(),
  },
  nextTrainingDaySelection: {
    remember: vi.fn(),
  },
  navigation: {
    replace: vi.fn(),
  },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/compositionRoot', () => ({
  getWeappApplication: () => application,
}))

const { default: WorkoutSummaryPage } = await import(
  '../src/presentation/pages/workout-summary'
)

function completedWorkout() {
  const ready = completeGeneralWarmup(createWorkoutFlow({
    clientSessionKey: 'summary-client-session',
    planVersionId: 'plan-version-runtime',
    exercises: [{
      snapshotExerciseKey: 'exercise-runtime',
      exerciseCode: 'BODYWEIGHT_SQUAT',
      name: '自重深蹲',
      targetWorkSets: 1,
      targetReps: 12,
      restSeconds: 60,
      weightStatus: 'BODYWEIGHT',
    }],
  }))
  return recordWorkoutSet(ready, {
    clientSetKey: 'summary-set-runtime',
    exerciseIndex: 0,
    setType: 'WORK',
    status: 'COMPLETED',
    actualWeightKg: 0,
    actualReps: 12,
    rir: '2',
  })
}

function partialWorkout() {
  return completeGeneralWarmup(createWorkoutFlow({
    clientSessionKey: 'partial-client-session',
    planVersionId: 'plan-version-runtime',
    exercises: [{
      snapshotExerciseKey: 'partial-exercise-runtime',
      exerciseCode: 'BODYWEIGHT_SQUAT',
      name: '自重深蹲',
      targetWorkSets: 2,
      targetReps: 12,
      restSeconds: 60,
      weightStatus: 'BODYWEIGHT',
    }],
  }))
}

function completion() {
  return {
    session: {
      id: 'server-summary-session', status: 'COMPLETED' as const, version: 3, trainingDayCode: 'DAY_A',
    },
    completedWorkSets: 1,
    complete: true,
    automaticProgressionEligible: true,
  }
}

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('workout summary runtime interactions', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    application.nextTrainingDaySelection.remember.mockResolvedValue(undefined)
    application.routeParameter.mockReturnValue(undefined)
    application.workouts.load.mockResolvedValue(completedWorkout())
    application.workouts.complete.mockResolvedValue(completion())
    application.workouts.discardOrphanedLocalWorkout.mockResolvedValue(undefined)
    application.requestWorkoutSummary.mockResolvedValue({
      status: 'READY',
      content: '保持动作稳定，下次训练按计划继续。',
    })
    application.getWorkoutSessionSummary.mockResolvedValue({
      sessionId: 'history-session-runtime',
      status: 'COMPLETED',
      completedWorkSets: 4,
      completedVolumeKg: 720,
      completedReps: 48,
      usesExternalLoad: true,
    })
    application.loadActivePlan.mockResolvedValue({
      planId: 'active-plan',
      activeVersion: {
        versionNumber: 1,
        plan: {
          days: [
            { code: 'DAY_A', name: '推 A', exercises: [{ exerciseCode: 'DUMBBELL_BENCH_PRESS', workSets: 3 }] },
            { code: 'DAY_B', name: '拉 B', exercises: [{ exerciseCode: 'SEATED_CABLE_ROW', workSets: 3 }] },
          ],
        },
      },
    })
    application.listWorkoutHistory.mockResolvedValue({
      items: [{
        sessionId: 'server-summary-session', trainingDayCode: 'DAY_A', trainingDayName: '推 A',
        status: 'COMPLETED', startedAt: '2026-08-15T08:00:00Z', completedAt: '2026-08-15T09:00:00Z',
        completedWorkSets: 1, completedVolumeKg: 0, completedReps: 12, usesExternalLoad: false,
      }],
    })
  })

  it('automatically settles a complete workout once and requests its server summary', async () => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
      await flushPage()
    })
    if (!renderer) throw new Error('workout summary page did not render')

    expect(application.workouts.complete).toHaveBeenCalledOnce()
    expect(application.workouts.complete).toHaveBeenCalledWith(
      expect.objectContaining({ clientSessionKey: 'summary-client-session' }),
      'FULL',
    )
    expect(application.requestWorkoutSummary).toHaveBeenCalledWith('server-summary-session')
    expect(JSON.stringify(renderer.toJSON())).toContain('保持动作稳定')
    expect(JSON.stringify(renderer.toJSON())).toContain('下一次：')
    expect(JSON.stringify(renderer.toJSON())).toContain('拉 B')
    expect(JSON.stringify(renderer.toJSON())).toContain('坐姿绳索划船')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('重试保存训练')
    await act(async () => {
      button(renderer!, '准备下一训练日').props.onClick()
      await flushPage()
    })
    expect(application.nextTrainingDaySelection.remember).toHaveBeenCalledWith('DAY_B')
    expect(application.navigation.replace).toHaveBeenCalledWith('WORKOUT_PREPARE', {
      trainingDayCode: 'DAY_B',
    })
  })

  it('retries the next-day read when completion becomes visible slightly later', async () => {
    application.workouts.complete.mockResolvedValue({
      ...completion(),
      session: { id: 'server-summary-session', status: 'COMPLETED', version: 3 },
    })
    application.listWorkoutHistory
      .mockRejectedValueOnce(new Error('history not visible yet'))
      .mockResolvedValueOnce({
        items: [{
          sessionId: 'server-summary-session', trainingDayCode: 'DAY_A', trainingDayName: '推 A',
          status: 'COMPLETED', startedAt: '2026-08-15T08:00:00Z', completedAt: '2026-08-15T09:00:00Z',
          completedWorkSets: 1, completedVolumeKg: 0, completedReps: 12, usesExternalLoad: false,
        }],
      })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
      await new Promise((resolve) => setTimeout(resolve, 200))
    })
    if (!renderer) throw new Error('workout summary page did not render')

    expect(application.listWorkoutHistory).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('下一次：')
    expect(JSON.stringify(renderer.toJSON())).toContain('拉 B')
  })

  it('retries when the active plan is temporarily absent after completion', async () => {
    application.loadActivePlan
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce({
        planId: 'active-plan',
        activeVersion: {
          versionNumber: 1,
          plan: {
            days: [
              { code: 'DAY_A', name: '推 A', exercises: [{ exerciseCode: 'DUMBBELL_BENCH_PRESS', workSets: 3 }] },
              { code: 'DAY_B', name: '拉 B', exercises: [{ exerciseCode: 'SEATED_CABLE_ROW', workSets: 3 }] },
            ],
          },
        },
      })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
      await new Promise((resolve) => setTimeout(resolve, 200))
    })
    if (!renderer) throw new Error('workout summary page did not render')

    expect(application.loadActivePlan).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('下一次：')
    expect(JSON.stringify(renderer.toJSON())).toContain('拉 B')
  })

  it('keeps the complete workout retryable when settlement fails and succeeds on the live retry button', async () => {
    application.workouts.complete
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(completion())
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
      await flushPage()
    })
    if (!renderer) throw new Error('workout summary page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('第 1 次保存失败')
    await act(async () => {
      button(renderer!, '重新保存到训练记录').props.onClick()
      await flushPage()
      await flushPage()
    })

    expect(application.workouts.complete).toHaveBeenCalledTimes(2)
    expect(application.requestWorkoutSummary).toHaveBeenCalledWith('server-summary-session')
    expect(JSON.stringify(renderer.toJSON())).toContain('AI 生成回顾已准备好')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('重新保存到训练记录')
  })

  it('shows a visible new result after every failed save attempt and offers a return to the plan', async () => {
    application.workouts.complete.mockRejectedValue(new Error('offline'))
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
      await flushPage()
    })
    if (!renderer) throw new Error('workout summary page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('第 1 次保存失败')
    await act(async () => {
      button(renderer!, '重新保存到训练记录').props.onClick()
      await flushPage()
    })

    expect(application.workouts.complete).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('第 2 次保存失败')
    expect(JSON.stringify(renderer.toJSON())).toContain('本地训练记录仍然保留')
    button(renderer, '返回训练计划').props.onClick()
    expect(application.navigation.replace).toHaveBeenCalledWith('PLAN')
  })

  it('offers an explicit local discard only after the server confirms the workout session is missing', async () => {
    application.workouts.complete.mockRejectedValue(
      new ApplicationError('RESOURCE_NOT_FOUND', 'missing'),
    )
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
      await flushPage()
    })
    if (!renderer) throw new Error('workout summary page did not render')

    const discard = button(renderer, '放弃这条无法保存的本地记录')
    await act(async () => {
      discard.props.onClick()
      await flushPage()
    })

    expect(application.workouts.discardOrphanedLocalWorkout).toHaveBeenCalledWith(
      expect.objectContaining({ clientSessionKey: 'summary-client-session' }),
    )
    expect(application.navigation.replace).toHaveBeenCalledWith('PLAN')
  })

  it('does not auto-settle an incomplete workout and only ends it after explicit confirmation', async () => {
    application.workouts.load.mockResolvedValue(partialWorkout())
    application.workouts.complete.mockResolvedValue({
      session: { id: 'partial-server-session', status: 'ABORTED', version: 2, trainingDayCode: 'DAY_A' },
      completedWorkSets: 0,
      complete: false,
      automaticProgressionEligible: false,
    })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('partial summary page did not render')

    const initial = JSON.stringify(renderer.toJSON())
    expect(initial).toContain('本次训练已记录')
    expect(initial).toContain('保存并提前结束')
    expect(application.workouts.complete).not.toHaveBeenCalled()
    await act(async () => {
      button(renderer!, '保存并提前结束').props.onClick()
      await flushPage()
      await flushPage()
    })

    expect(application.workouts.complete).toHaveBeenCalledWith(
      expect.objectContaining({ clientSessionKey: 'partial-client-session' }),
      'EARLY_END',
    )
    expect(application.requestWorkoutSummary).toHaveBeenCalledWith('partial-server-session')
    expect(application.loadActivePlan).not.toHaveBeenCalled()
    expect(application.nextTrainingDaySelection.remember).not.toHaveBeenCalled()
    expect(JSON.stringify(renderer.toJSON())).not.toContain('下一次：')
  })

  it('loads and retries a historical AI summary with the exact route session id', async () => {
    application.routeParameter.mockReturnValue('history-session-runtime')
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('historical summary page did not render')

    expect(application.workouts.load).not.toHaveBeenCalled()
    expect(application.getWorkoutSessionSummary).toHaveBeenCalledWith('history-session-runtime')
    expect(application.requestWorkoutSummary).toHaveBeenCalledWith('history-session-runtime')
    expect(JSON.stringify(renderer.toJSON())).toContain('720')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('正在读取训练回顾')
    await act(async () => {
      button(renderer!, '重新生成训练总结').props.onClick()
      await flushPage()
    })

    expect(application.requestWorkoutSummary).toHaveBeenNthCalledWith(2, 'history-session-runtime')
    button(renderer, '查看训练进展').props.onClick()
    expect(application.navigation.replace).toHaveBeenCalledWith('HISTORY')
  })

  it('offers a real retry when historical workout metrics cannot be read', async () => {
    application.routeParameter.mockReturnValue('history-session-runtime')
    application.getWorkoutSessionSummary
      .mockRejectedValueOnce(new Error('history unavailable'))
      .mockResolvedValueOnce({
        sessionId: 'history-session-runtime',
        status: 'COMPLETED',
        completedWorkSets: 4,
        completedVolumeKg: 720,
        completedReps: 48,
        usesExternalLoad: true,
      })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
      await flushPage()
    })
    if (!renderer) throw new Error('historical summary page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('训练回顾暂时无法读取')
    await act(async () => {
      button(renderer!, '重新读取训练回顾').props.onClick()
      await flushPage()
      await flushPage()
    })

    expect(application.getWorkoutSessionSummary).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('720')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('重新读取训练回顾')
  })

  it('does not let a hanging AI summary block historical fact retry or repeat AI generation', async () => {
    application.routeParameter.mockReturnValue('history-session-runtime')
    application.getWorkoutSessionSummary.mockRejectedValueOnce(new Error('history unavailable'))
    application.requestWorkoutSummary.mockReturnValue(new Promise(() => undefined))
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('historical summary page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('重新读取训练回顾')
    await act(async () => {
      button(renderer!, '重新读取训练回顾').props.onClick()
      await flushPage()
    })
    expect(application.requestWorkoutSummary).toHaveBeenCalledTimes(1)
    expect(application.getWorkoutSessionSummary).toHaveBeenCalledTimes(2)
  })

  it('does not invent an uncompleted-set count for an aborted historical workout', async () => {
    application.routeParameter.mockReturnValue('history-session-runtime')
    application.getWorkoutSessionSummary.mockResolvedValue({
      sessionId: 'history-session-runtime',
      status: 'ABORTED',
      completedWorkSets: 2,
      completedVolumeKg: 120,
      completedReps: 24,
      usesExternalLoad: true,
    })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
      await flushPage()
    })
    if (!renderer) throw new Error('historical summary page did not render')

    const rendered = JSON.stringify(renderer.toJSON())
    expect(rendered).toContain('部分完成')
    expect(rendered).toContain('未完成组数不会被推测')
    expect(rendered).not.toContain('未完成或跳过')
  })

  it('offers a real retry when the local workout record cannot be read', async () => {
    application.workouts.load
      .mockRejectedValueOnce(new Error('storage unavailable'))
      .mockResolvedValueOnce(partialWorkout())
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout summary page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('训练记录暂时无法读取')
    await act(async () => {
      button(renderer!, '重新读取训练记录').props.onClick()
      await flushPage()
    })

    expect(application.workouts.load).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('本次训练已记录')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('重新读取训练记录')
  })

  it('labels degraded content as a rule template instead of claiming AI generation', async () => {
    application.routeParameter.mockReturnValue('template-summary-session')
    application.requestWorkoutSummary.mockResolvedValue({
      status: 'DEGRADED',
      content: '按当前规则保持动作稳定。',
    })
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('workout summary page did not render')

    const rendered = JSON.stringify(renderer.toJSON())
    expect(rendered).toContain('RULE TEMPLATE')
    expect(rendered).toContain('规则训练回顾')
    expect(rendered).not.toContain('AI 生成回顾')
    expect(application.telemetry.track).toHaveBeenCalledWith('ai_summary_viewed', { source: 'template' })
  })

  it('coalesces rapid summary regeneration clicks', async () => {
    application.routeParameter.mockReturnValue('single-flight-summary')
    let releaseInitial: ((value: { status: 'READY'; content: string }) => void) | undefined
    application.requestWorkoutSummary.mockImplementationOnce(() => new Promise((resolve) => {
      releaseInitial = resolve
    }))
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(WorkoutSummaryPage))
    })
    if (!renderer) throw new Error('workout summary page did not render')

    expect(application.requestWorkoutSummary).toHaveBeenCalledTimes(1)
    await act(async () => {
      releaseInitial?.({ status: 'READY', content: '初次回顾' })
      await flushPage()
    })

    let releaseRetry: ((value: { status: 'READY'; content: string }) => void) | undefined
    application.requestWorkoutSummary.mockImplementationOnce(() => new Promise((resolve) => {
      releaseRetry = resolve
    }))
    const retry = button(renderer, '重新生成训练总结')
    act(() => {
      retry.props.onClick()
      retry.props.onClick()
    })
    expect(application.requestWorkoutSummary).toHaveBeenCalledTimes(2)

    await act(async () => {
      releaseRetry?.({ status: 'READY', content: '更新回顾' })
      await flushPage()
    })
  })
})
