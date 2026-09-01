import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const application = vi.hoisted(() => ({
  listExercises: vi.fn(),
  getExercisePreferences: vi.fn(),
  saveExercisePreferences: vi.fn(),
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/featureRoots/accountCompositionRoot', () => ({
  getAccountApplication: () => application,
}))

const { default: ExercisePreferencesPage } = await import(
  '../src/presentation/pages/exercise-preferences'
)

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

function exerciseButton(renderer: ReactTestRenderer, name: string) {
  return renderer.root.find(
    (node) => node.type === 'button'
      && node.findAllByType('text').some((text) => text.props.children === name),
  )
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

describe('live exercise preference editor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    application.listExercises.mockResolvedValue([{
      id: 'exercise-squat',
      code: 'BODYWEIGHT_SQUAT',
      name: '自重深蹲',
      plainLanguage: '保持躯干稳定完成下蹲。',
      movementPattern: 'SQUAT',
      difficulty: 'BEGINNER',
      equipment: ['BODYWEIGHT'],
      primaryMuscles: ['QUADRICEPS'],
      instructions: ['屈髋屈膝下蹲'],
      safetyCues: ['膝盖方向与脚尖一致'],
      image: {
        primaryRef: 'asset://exercise-guides/bodyweight-squat-01-setup.jpg',
        fallbackRef: 'asset://exercise-placeholder',
      },
      alternatives: [],
      contentVersion: '1.6.0',
    }])
    application.getExercisePreferences.mockResolvedValue({ version: 4, items: [] })
    application.saveExercisePreferences.mockResolvedValue({ version: 5 })
  })

  it('saves the selected exclusion against the loaded profile version', async () => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(ExercisePreferencesPage))
      await flushPage()
    })
    if (!renderer) throw new Error('exercise preference page did not render')

    act(() => exerciseButton(renderer!, '自重深蹲').props.onClick())
    expect(exerciseButton(renderer, '自重深蹲').props.className).toContain('preference-item--selected')

    await act(async () => {
      button(renderer!, '保存动作偏好').props.onClick()
      await flushPage()
    })

    expect(application.saveExercisePreferences).toHaveBeenCalledWith({
      expectedVersion: 4,
      items: [{ exerciseId: 'exercise-squat', preferenceType: 'EXCLUDED' }],
    })
    expect(JSON.stringify(renderer.toJSON())).toContain('下次生成或调整计划时会避开这些动作')
  })

  it('keeps the selected exclusion retryable after a failed save', async () => {
    application.saveExercisePreferences
      .mockRejectedValueOnce(new Error('version conflict'))
      .mockResolvedValueOnce({ version: 5 })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(ExercisePreferencesPage))
      await flushPage()
    })
    if (!renderer) throw new Error('exercise preference page did not render')
    act(() => exerciseButton(renderer!, '自重深蹲').props.onClick())

    await act(async () => {
      button(renderer!, '保存动作偏好').props.onClick()
      await flushPage()
    })
    expect(JSON.stringify(renderer.toJSON())).toContain('保存失败')
    expect(button(renderer, '保存动作偏好').props.disabled).toBe(false)

    await act(async () => {
      button(renderer!, '保存动作偏好').props.onClick()
      await flushPage()
    })

    expect(application.saveExercisePreferences).toHaveBeenCalledTimes(2)
    expect(application.saveExercisePreferences).toHaveBeenLastCalledWith({
      expectedVersion: 4,
      items: [{ exerciseId: 'exercise-squat', preferenceType: 'EXCLUDED' }],
    })
    expect(JSON.stringify(renderer.toJSON())).toContain('已保存')
  })

  it('retries an initial read failure without treating it as an empty preference profile', async () => {
    application.listExercises
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce([{
        id: 'exercise-squat',
        code: 'BODYWEIGHT_SQUAT',
        name: '自重深蹲',
        plainLanguage: '保持躯干稳定完成下蹲。',
        movementPattern: 'SQUAT',
        difficulty: 'BEGINNER',
        equipment: ['BODYWEIGHT'],
        primaryMuscles: ['QUADRICEPS'],
        instructions: ['屈髋屈膝下蹲'],
        safetyCues: ['膝盖方向与脚尖一致'],
        image: { primaryRef: 'asset://exercise-guides/bodyweight-squat-01-setup.jpg', fallbackRef: 'asset://exercise-placeholder' },
        alternatives: [],
        contentVersion: '1.6.0',
      }])
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(ExercisePreferencesPage))
      await flushPage()
    })
    if (!renderer) throw new Error('exercise preference page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('动作偏好暂时无法读取')
    expect(application.saveExercisePreferences).not.toHaveBeenCalled()
    await act(async () => {
      button(renderer!, '重新加载动作偏好').props.onClick()
      await flushPage()
    })

    expect(application.listExercises).toHaveBeenCalledTimes(2)
    expect(application.getExercisePreferences).toHaveBeenCalledTimes(2)
    expect(exerciseButton(renderer, '自重深蹲')).toBeDefined()
  })

  it('coalesces rapid preference-save clicks before the disabled state renders', async () => {
    const save = deferred<{ version: number }>()
    application.saveExercisePreferences.mockReturnValueOnce(save.promise)
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(ExercisePreferencesPage))
      await flushPage()
    })
    if (!renderer) throw new Error('exercise preference page did not render')

    const action = button(renderer, '保存动作偏好')
    act(() => {
      action.props.onClick()
      action.props.onClick()
    })
    expect(application.saveExercisePreferences).toHaveBeenCalledOnce()

    await act(async () => {
      save.resolve({ version: 5 })
      await flushPage()
    })
  })
})
