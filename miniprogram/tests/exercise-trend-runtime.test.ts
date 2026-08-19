import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const application = vi.hoisted(() => ({
  routeParameter: vi.fn(),
  getExerciseTrend: vi.fn(),
  navigation: { replace: vi.fn() },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/compositionRoot', () => ({
  getWeappApplication: () => application,
}))

const { default: ExerciseTrendPage } = await import(
  '../src/presentation/pages/exercise-trend'
)

function renderedText(renderer: ReactTestRenderer): string {
  return JSON.stringify(renderer.toJSON())
}

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

async function renderPage(): Promise<ReactTestRenderer> {
  let renderer: ReactTestRenderer | undefined
  await act(async () => {
    renderer = TestRenderer.create(createElement(ExerciseTrendPage))
    await new Promise((resolve) => setTimeout(resolve, 0))
  })
  if (!renderer) throw new Error('exercise trend page did not render')
  return renderer
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

describe('exercise trend page runtime', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    application.routeParameter.mockReturnValue('STANDING_WALL_CALF_RAISE')
  })

  it('loads the requested exercise, localizes its name, and renders chronological facts', async () => {
    application.getExerciseTrend.mockResolvedValue({
      exerciseCode: 'STANDING_WALL_CALF_RAISE',
      unit: 'KG',
      points: [
        {
          sessionId: 'session-1',
          completedAt: '2026-08-10T09:00:00Z',
          topWeightKg: 10,
          totalReps: 30,
          workSetCount: 3,
        },
        {
          sessionId: 'session-2',
          completedAt: '2026-08-12T09:00:00Z',
          topWeightKg: 12.5,
          totalReps: 24,
          workSetCount: 3,
        },
      ],
    })

    const renderer = await renderPage()
    const text = renderedText(renderer)
    expect(application.getExerciseTrend).toHaveBeenCalledWith('STANDING_WALL_CALF_RAISE')
    expect(text).toContain('扶墙站姿提踵')
    expect(text).toContain('12.5 KG')
    expect(renderer.root.findByProps({ className: 'trend-overview__value data-number' }).props.children).toBe(2)
    expect(text).toContain('次有效训练')
    expect(text).toContain('3 个有效正式组 · 共 24 次')

    act(() => button(renderer, '返回训练进展').props.onClick())
    expect(application.navigation.replace).toHaveBeenCalledWith('HISTORY')
  })

  it('retries after a failed load and replaces the failure state with recovered data', async () => {
    application.getExerciseTrend
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({
        exerciseCode: 'STANDING_WALL_CALF_RAISE',
        unit: 'KG',
        points: [{
          sessionId: 'session-recovered',
          completedAt: '2026-08-12T09:00:00Z',
          topWeightKg: 8,
          totalReps: 20,
          workSetCount: 2,
        }],
      })

    const renderer = await renderPage()
    expect(renderedText(renderer)).toContain('训练趋势暂时无法加载')
    await act(async () => {
      button(renderer, '重新加载').props.onClick()
      await new Promise((resolve) => setTimeout(resolve, 0))
    })

    expect(application.getExerciseTrend).toHaveBeenCalledTimes(2)
    expect(renderedText(renderer)).toContain('8 KG')
    expect(renderedText(renderer)).not.toContain('训练趋势暂时无法加载')
  })

  it('coalesces rapid trend retry clicks', async () => {
    application.getExerciseTrend.mockRejectedValueOnce(new Error('offline'))
    const retry = deferred<{ exerciseCode: string; unit: string; points: never[] }>()
    application.getExerciseTrend.mockReturnValueOnce(retry.promise)
    const renderer = await renderPage()

    const action = button(renderer, '重新加载')
    act(() => {
      action.props.onClick()
      action.props.onClick()
    })
    expect(application.getExerciseTrend).toHaveBeenCalledTimes(2)

    await act(async () => {
      retry.resolve({ exerciseCode: 'STANDING_WALL_CALF_RAISE', unit: 'KG', points: [] })
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  })
})
