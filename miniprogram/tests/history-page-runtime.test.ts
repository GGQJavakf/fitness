import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApplicationError } from '../src/application/errors'
import {
  completeGeneralWarmup,
  createWorkoutFlow,
  recordWorkoutSet,
} from '../src/application/workoutFlow'

const application = vi.hoisted(() => ({
  listWorkoutHistory: vi.fn(),
  listProgressionRecommendations: vi.fn(),
  loadActivePlan: vi.fn(),
  applyProgressionRecommendation: vi.fn(),
  dismissProgressionRecommendation: vi.fn(),
  navigation: {
    open: vi.fn(),
    replace: vi.fn(),
  },
  telemetry: {
    track: vi.fn(),
  },
  workouts: {
    load: vi.fn(),
  },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Input: 'input',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/compositionRoot', () => ({
  getWeappApplication: () => application,
}))

const { default: HistoryPage } = await import('../src/presentation/pages/history')

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function pendingCompletedWorkout() {
  const ready = completeGeneralWarmup(createWorkoutFlow({
    clientSessionKey: 'history-pending-session',
    planVersionId: 'history-plan-version',
    exercises: [{
      snapshotExerciseKey: 'history-pending-exercise',
      exerciseCode: 'DUMBBELL_BICEPS_CURL',
      name: '哑铃弯举',
      targetWorkSets: 1,
      targetReps: 12,
      restSeconds: 60,
      weightStatus: 'KNOWN',
      targetWeightKg: 10,
    }],
  }))
  return recordWorkoutSet(ready, {
    clientSetKey: 'history-pending-set',
    exerciseIndex: 0,
    setType: 'WORK',
    status: 'COMPLETED',
    actualWeightKg: 10,
    actualReps: 12,
    rir: '2',
  })
}

describe('history page runtime interactions', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    application.listWorkoutHistory.mockResolvedValue({
      items: [{
        sessionId: 'history-session-exact',
        trainingDayCode: 'DAY_A',
        trainingDayName: '全身 A',
        status: 'COMPLETED',
        startedAt: '2026-08-12T08:00:00Z',
        completedAt: '2026-08-12T09:00:00Z',
        completedWorkSets: 5,
        completedVolumeKg: 1200,
        completedReps: 45,
        usesExternalLoad: true,
      }],
      hasMore: false,
    })
    application.listProgressionRecommendations.mockResolvedValue({ items: [], hasMore: false })
    application.loadActivePlan.mockResolvedValue({
      activeVersion: { versionNumber: 3 },
    })
    application.applyProgressionRecommendation.mockResolvedValue({})
    application.dismissProgressionRecommendation.mockResolvedValue({})
    application.workouts.load.mockResolvedValue(undefined)
  })

  it('keeps a completed local workout visible as pending even when server history is unavailable', async () => {
    application.workouts.load.mockResolvedValue(pendingCompletedWorkout())
    application.listWorkoutHistory.mockRejectedValue(new Error('offline'))
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    const rendered = JSON.stringify(renderer.toJSON())
    expect(rendered).toContain('待同步训练')
    expect(rendered).toContain('1 组 · 120 KG·次')
    expect(rendered).toContain('本地训练记录仍然保留')
    button(renderer, '继续保存这次训练').props.onClick()
    expect(application.navigation.open).toHaveBeenCalledWith('WORKOUT_SUMMARY')
  })

  it('opens the selected workout review with its exact session id', async () => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('5 组 · 1200 KG·次')
    expect(JSON.stringify(renderer.toJSON())).toContain('全身 A')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('DAY_A')
    button(renderer, '查看训练回顾').props.onClick()
    expect(application.navigation.open).toHaveBeenCalledWith('WORKOUT_SUMMARY', {
      sessionId: 'history-session-exact',
    })
  })

  it('retries history through the visible control after a read failure', async () => {
    application.listWorkoutHistory
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ items: [], hasMore: false })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('训练记录暂时无法加载')
    await act(async () => {
      button(renderer!, '重新加载').props.onClick()
      await flushPage()
    })
    expect(application.listWorkoutHistory).toHaveBeenCalledTimes(2)
  })

  it('retries progression recommendations through a visible control', async () => {
    application.listProgressionRecommendations
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ items: [], hasMore: false })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('暂时无法读取调整建议')
    await act(async () => {
      button(renderer!, '重试调整建议').props.onClick()
      await flushPage()
    })

    expect(application.listProgressionRecommendations).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('继续训练，证据充分后')
  })

  it('coalesces rapid load-more taps and appends the next page once', async () => {
    let resolveNextPage: ((value: unknown) => void) | undefined
    application.listWorkoutHistory
      .mockResolvedValueOnce({
        items: [{
          sessionId: 'history-page-1',
          trainingDayCode: 'DAY_A',
          trainingDayName: '全身 A',
          status: 'COMPLETED',
          startedAt: '2026-08-12T08:00:00Z',
          completedAt: '2026-08-12T09:00:00Z',
          completedWorkSets: 5,
          completedVolumeKg: 1200,
          completedReps: 45,
          usesExternalLoad: true,
        }],
        nextCursor: 'cursor-next',
        hasMore: true,
      })
      .mockImplementationOnce(() => new Promise((resolve) => { resolveNextPage = resolve }))
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    act(() => {
      button(renderer!, '查看更多记录').props.onClick()
      button(renderer!, '查看更多记录').props.onClick()
    })
    expect(application.listWorkoutHistory).toHaveBeenCalledTimes(2)

    await act(async () => {
      resolveNextPage?.({
        items: [{
          sessionId: 'history-page-2',
          trainingDayCode: 'DAY_B',
          trainingDayName: '全身 B',
          status: 'COMPLETED',
          startedAt: '2026-08-10T08:00:00Z',
          completedAt: '2026-08-10T09:00:00Z',
          completedWorkSets: 4,
          completedVolumeKg: 900,
          completedReps: 36,
          usesExternalLoad: true,
        }],
        hasMore: false,
      })
      await flushPage()
    })

    const text = JSON.stringify(renderer.toJSON())
    expect(text).toContain('全身 A')
    expect(text).toContain('全身 B')
    expect(application.listWorkoutHistory).toHaveBeenLastCalledWith('cursor-next')
  })

  it('loads additional recommendation pages once without duplicating existing cards', async () => {
    const first = {
      id: 'recommendation-page-1', exerciseCode: 'GOBLET_SQUAT', status: 'PENDING', decision: 'INCREASE',
      reasonCode: 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR', currentWeightKg: 40,
      recommendedWeightKg: 42.5, algorithmVersion: 'double-progression-v1',
      createdAt: '2026-08-12T09:00:00Z',
    }
    const second = {
      ...first,
      id: 'recommendation-page-2',
      exerciseCode: 'SEATED_CABLE_ROW',
    }
    let resolveNextPage: ((value: unknown) => void) | undefined
    application.listProgressionRecommendations
      .mockResolvedValueOnce({ items: [first], nextCursor: 'recommendation-cursor', hasMore: true })
      .mockImplementationOnce(() => new Promise((resolve) => { resolveNextPage = resolve }))
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    act(() => {
      button(renderer!, '查看更多建议').props.onClick()
      button(renderer!, '查看更多建议').props.onClick()
    })
    expect(application.listProgressionRecommendations).toHaveBeenCalledTimes(2)

    await act(async () => {
      resolveNextPage?.({ items: [first, second], hasMore: false })
      await flushPage()
    })

    expect(application.listProgressionRecommendations).toHaveBeenLastCalledWith('recommendation-cursor')
    const text = JSON.stringify(renderer.toJSON())
    expect(text.match(/高脚杯深蹲/g)).toHaveLength(1)
    expect(text.match(/坐姿绳索划船/g)).toHaveLength(1)
  })

  it('retries the same recommendation cursor after a next-page read failure', async () => {
    const recommendation = {
      id: 'recommendation-page-1', exerciseCode: 'GOBLET_SQUAT', status: 'PENDING', decision: 'INCREASE',
      reasonCode: 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR', currentWeightKg: 40,
      recommendedWeightKg: 42.5, algorithmVersion: 'double-progression-v1',
      createdAt: '2026-08-12T09:00:00Z',
    }
    application.listProgressionRecommendations
      .mockResolvedValueOnce({ items: [recommendation], nextCursor: 'recommendation-cursor', hasMore: true })
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ items: [], hasMore: false })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    await act(async () => {
      button(renderer!, '查看更多建议').props.onClick()
      await flushPage()
    })
    expect(JSON.stringify(renderer.toJSON())).toContain('暂时无法读取调整建议')
    expect(renderer.root.findAll(
      (node) => node.type === 'button' && node.props.children === '查看更多建议',
    )).toHaveLength(0)

    await act(async () => {
      button(renderer!, '重试调整建议').props.onClick()
      await flushPage()
    })
    expect(application.listProgressionRecommendations.mock.calls.slice(1)).toEqual([
      ['recommendation-cursor'],
      ['recommendation-cursor'],
    ])
  })

  it('reuses the same recommendation idempotency key when a lost response is retried', async () => {
    application.listProgressionRecommendations.mockResolvedValue({ items: [{
      id: 'recommendation-1',
      exerciseCode: 'GOBLET_SQUAT',
      status: 'PENDING',
      decision: 'INCREASE',
      reasonCode: 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR',
      currentWeightKg: 40,
      recommendedWeightKg: 42.5,
      algorithmVersion: 'double-progression-v1',
      createdAt: '2026-08-12T09:00:00Z',
    }], hasMore: false })
    application.applyProgressionRecommendation
      .mockRejectedValueOnce(new ApplicationError('NETWORK_ERROR', 'response lost', { retryable: true }))
      .mockResolvedValueOnce({})
    application.loadActivePlan
      .mockResolvedValueOnce({ activeVersion: { versionNumber: 3 } })
      .mockResolvedValueOnce({ activeVersion: { versionNumber: 4 } })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    await act(async () => {
      button(renderer!, '采用建议').props.onClick()
      await flushPage()
    })
    await act(async () => {
      button(renderer!, '采用建议').props.onClick()
      await flushPage()
    })

    expect(application.applyProgressionRecommendation).toHaveBeenCalledTimes(2)
    expect(application.applyProgressionRecommendation.mock.calls[0][1]).toBe(3)
    expect(application.applyProgressionRecommendation.mock.calls[1][1]).toBe(3)
    expect(application.applyProgressionRecommendation.mock.calls[0][3])
      .toBe('progression-recommendation-1-42.5-v3')
    expect(application.applyProgressionRecommendation.mock.calls[1][3])
      .toBe(application.applyProgressionRecommendation.mock.calls[0][3])
    expect(application.loadActivePlan).toHaveBeenCalledOnce()
  })

  it('reconciles the authoritative recommendation list after a dismiss response is lost', async () => {
    const recommendation = {
      id: 'recommendation-dismiss',
      exerciseCode: 'GOBLET_SQUAT',
      status: 'PENDING',
      decision: 'INCREASE',
      reasonCode: 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR',
      currentWeightKg: 40,
      recommendedWeightKg: 42.5,
      algorithmVersion: 'double-progression-v1',
      createdAt: '2026-08-12T09:00:00Z',
    }
    application.listProgressionRecommendations
      .mockResolvedValueOnce({ items: [recommendation], hasMore: false })
      .mockResolvedValueOnce({ items: [], hasMore: false })
    application.dismissProgressionRecommendation.mockRejectedValueOnce(
      new ApplicationError('NETWORK_ERROR', 'response lost', { retryable: true }),
    )
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    await act(async () => {
      button(renderer!, '保持当前安排').props.onClick()
      await flushPage()
    })

    expect(application.dismissProgressionRecommendation).toHaveBeenCalledWith('recommendation-dismiss')
    expect(application.listProgressionRecommendations).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).not.toContain('高脚杯深蹲')
  })

  it('reloads the plan version and uses a new semantic idempotency key after a version conflict', async () => {
    const recommendation = {
      id: 'recommendation-version', exerciseCode: 'GOBLET_SQUAT', status: 'PENDING', decision: 'INCREASE',
      reasonCode: 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR', currentWeightKg: 40,
      recommendedWeightKg: 42.5, algorithmVersion: 'double-progression-v1',
      createdAt: '2026-08-12T09:00:00Z',
    }
    application.listProgressionRecommendations.mockResolvedValue({ items: [recommendation], hasMore: false })
    application.loadActivePlan
      .mockResolvedValueOnce({ activeVersion: { versionNumber: 3 } })
      .mockResolvedValueOnce({ activeVersion: { versionNumber: 4 } })
    application.applyProgressionRecommendation
      .mockRejectedValueOnce(new ApplicationError('VERSION_CONFLICT', 'stale plan'))
      .mockResolvedValueOnce({})
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    await act(async () => {
      button(renderer!, '采用建议').props.onClick()
      await flushPage()
    })
    expect(JSON.stringify(renderer.toJSON())).toContain('计划已在其他位置更新')
    await act(async () => {
      button(renderer!, '采用建议').props.onClick()
      await flushPage()
    })

    expect(application.loadActivePlan).toHaveBeenCalledTimes(2)
    expect(application.applyProgressionRecommendation.mock.calls.map((call) => [call[1], call[3]])).toEqual([
      [3, 'progression-recommendation-version-42.5-v3'],
      [4, 'progression-recommendation-version-42.5-v4'],
    ])
  })

  it('serializes actions across different recommendation cards that share the active plan', async () => {
    const first = {
      id: 'recommendation-serial-1', exerciseCode: 'GOBLET_SQUAT', status: 'PENDING', decision: 'INCREASE',
      reasonCode: 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR', currentWeightKg: 40,
      recommendedWeightKg: 42.5, algorithmVersion: 'double-progression-v1',
      createdAt: '2026-08-12T09:00:00Z',
    }
    const second = { ...first, id: 'recommendation-serial-2', exerciseCode: 'SEATED_CABLE_ROW' }
    application.listProgressionRecommendations.mockResolvedValue({ items: [first, second], hasMore: false })
    let resolvePlan: ((value: unknown) => void) | undefined
    application.loadActivePlan.mockImplementationOnce(() => new Promise((resolve) => { resolvePlan = resolve }))
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')

    const applyButtons = renderer.root.findAll(
      (node) => node.type === 'button' && node.props.children === '采用建议',
    )
    act(() => {
      applyButtons[0].props.onClick()
      applyButtons[1].props.onClick()
    })
    expect(application.loadActivePlan).toHaveBeenCalledOnce()
    expect(button(renderer, '采用建议').props.disabled).toBe(true)
    expect(renderer.root.findAll(
      (node) => node.type === 'button' && node.props.children === '保持当前安排',
    ).every((node) => node.props.disabled === true)).toBe(true)

    await act(async () => {
      resolvePlan?.({ activeVersion: { versionNumber: 3 } })
      await flushPage()
    })
    expect(application.applyProgressionRecommendation).toHaveBeenCalledOnce()
    expect(application.applyProgressionRecommendation.mock.calls[0][0]).toBe('recommendation-serial-1')
  })

  it('does not reconcile, emit telemetry or update page state after an action outlives unmount', async () => {
    const recommendation = {
      id: 'recommendation-unmount', exerciseCode: 'GOBLET_SQUAT', status: 'PENDING', decision: 'INCREASE',
      reasonCode: 'ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR', currentWeightKg: 40,
      recommendedWeightKg: 42.5, algorithmVersion: 'double-progression-v1',
      createdAt: '2026-08-12T09:00:00Z',
    }
    application.listProgressionRecommendations.mockResolvedValue({ items: [recommendation], hasMore: false })
    let rejectApply: ((reason: unknown) => void) | undefined
    application.applyProgressionRecommendation.mockImplementationOnce(
      () => new Promise((_, reject) => { rejectApply = reject }),
    )
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(HistoryPage))
      await flushPage()
    })
    if (!renderer) throw new Error('history page did not render')
    const recommendationReadsBeforeAction = application.listProgressionRecommendations.mock.calls.length
    const telemetryBeforeAction = application.telemetry.track.mock.calls.length

    act(() => {
      button(renderer!, '采用建议').props.onClick()
    })
    act(() => renderer!.unmount())
    await act(async () => {
      rejectApply?.(new ApplicationError('NETWORK_ERROR', 'response lost', { retryable: true }))
      await flushPage()
    })

    expect(application.listProgressionRecommendations).toHaveBeenCalledTimes(recommendationReadsBeforeAction)
    expect(application.telemetry.track).toHaveBeenCalledTimes(telemetryBeforeAction)
  })
})
