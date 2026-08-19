import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  changeNumericField,
  createPlanEditorState,
  type PlanEditorState,
} from '../src/application/planEditor'

const application = vi.hoisted(() => ({
  getPlanEditor: vi.fn(),
  editPlanNumber: vi.fn(),
  setPlanFieldLock: vi.fn(),
  telemetry: { track: vi.fn() },
  loadActivePlan: vi.fn(),
  openPlanEditor: vi.fn(),
  listPlanExerciseOptions: vi.fn(),
  listPlanExerciseReplacements: vi.fn(),
  listPlanDayOptions: vi.fn(),
  movePlanDay: vi.fn(),
  removePlanDay: vi.fn(),
  movePlanExercise: vi.fn(),
  replacePlanExercise: vi.fn(),
  addPlanExercise: vi.fn(),
  removePlanExercise: vi.fn(),
  addPlanDay: vi.fn(),
  validateEditor: vi.fn(),
  previewRebalance: vi.fn(),
  saveEditor: vi.fn(),
  confirmEditorWarnings: vi.fn(),
  navigation: { replace: vi.fn() },
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

const { default: PlanEditorPage } = await import('../src/presentation/pages/plan-editor')

function initialEditor(): PlanEditorState {
  return createPlanEditorState({
    planId: 'plan-1',
    baseVersion: 1,
    plan: {
      templateCode: 'full-body',
      name: '全身训练',
      days: [{
        code: 'DAY_1',
        name: '训练日一',
        exercises: [{
          exerciseCode: 'GOBLET_SQUAT',
          workSets: 3,
          repMin: 8,
          repMax: 12,
          restSeconds: 90,
          targetWeightKg: 12,
          weightStatus: 'KNOWN',
        }],
      }],
      locks: {},
    },
    validationResult: { valid: true, validationIssues: [] },
  })
}

function expandedEditor(): PlanEditorState {
  const editor = initialEditor()
  editor.workingCopy.days[0].exercises.push({
    exerciseCode: 'DUMBBELL_BENCH_PRESS',
    workSets: 3,
    repMin: 8,
    repMax: 12,
    restSeconds: 90,
    targetWeightKg: 10,
    weightStatus: 'KNOWN',
  })
  editor.workingCopy.days.push({
    code: 'DAY_2',
    name: '训练日二',
    exercises: [{
      exerciseCode: 'DUMBBELL_ROW',
      workSets: 3,
      repMin: 8,
      repMax: 12,
      restSeconds: 90,
      targetWeightKg: 12,
      weightStatus: 'KNOWN',
    }],
  })
  return editor
}

function workSetsInput(renderer: ReactTestRenderer) {
  return renderer.root.findAllByType('input')[0]
}

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

describe('live plan editor number input', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    const editor = initialEditor()
    application.getPlanEditor.mockReturnValue(editor)
    application.editPlanNumber.mockImplementation(
      (dayCode: string, exerciseCode: string, field: string, value: number) =>
        changeNumericField(editor, dayCode, exerciseCode, field as 'workSets', value),
    )
    application.loadActivePlan.mockResolvedValue({})
    application.openPlanEditor.mockReturnValue(editor)
  })

  it('keeps a cleared value visible, blocks the invalid edit, and accepts the retyped number', () => {
    let renderer: ReactTestRenderer | undefined
    act(() => {
      renderer = TestRenderer.create(createElement(PlanEditorPage))
    })
    if (!renderer) throw new Error('plan editor page did not render')

    expect(workSetsInput(renderer).props.value).toBe('3')

    act(() => workSetsInput(renderer!).props.onInput({ detail: { value: '' } }))

    expect(workSetsInput(renderer).props.value).toBe('')
    expect(application.editPlanNumber).not.toHaveBeenCalled()
    expect(JSON.stringify(renderer.toJSON())).toContain('请输入工作组')

    act(() => workSetsInput(renderer!).props.onInput({ detail: { value: '4' } }))

    expect(workSetsInput(renderer).props.value).toBe('4')
    expect(application.editPlanNumber).toHaveBeenCalledWith(
      'DAY_1',
      'GOBLET_SQUAT',
      'workSets',
      4,
    )
    expect(JSON.stringify(renderer.toJSON())).not.toContain('请输入工作组')
  })

  it('retries the initial active-plan load without forcing the user to leave the editor', async () => {
    const editor = initialEditor()
    application.getPlanEditor.mockReturnValue(null)
    application.loadActivePlan
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({})
    application.openPlanEditor.mockReturnValue(editor)
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanEditorPage))
      await flushPage()
    })
    if (!renderer) throw new Error('plan editor page did not render')

    expect(JSON.stringify(renderer.toJSON())).toContain('活动计划加载失败')
    await act(async () => {
      button(renderer!, '重新读取计划').props.onClick()
      await flushPage()
    })

    expect(application.loadActivePlan).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(renderer.toJSON())).toContain('全身训练')
  })

  it('coalesces rapid plan-version saves before the busy state renders', async () => {
    const current = initialEditor()
    const next = { ...current, baseVersion: 2 }
    const save = deferred<PlanEditorState>()
    application.saveEditor.mockReturnValueOnce(save.promise)
    let renderer: ReactTestRenderer | undefined
    act(() => {
      renderer = TestRenderer.create(createElement(PlanEditorPage))
    })
    if (!renderer) throw new Error('plan editor page did not render')

    const action = button(renderer, '保存新版本')
    act(() => {
      action.props.onClick()
      action.props.onClick()
    })
    expect(application.saveEditor).toHaveBeenCalledOnce()

    await act(async () => {
      save.resolve(next)
      await flushPage()
    })
    expect(application.navigation.replace).toHaveBeenCalledWith('PLAN')
  })

  it('loads source-equivalent replacements separately from add-action options', async () => {
    const replacement = {
      exerciseCode: 'DUMBBELL_FRONT_SQUAT',
      name: '双哑铃前蹲',
      workSets: 3,
      repMin: 8,
      repMax: 12,
      restSeconds: 90,
      weightStatus: 'KNOWN',
      targetWeightKg: 12,
      movementPattern: 'SQUAT',
      primaryMuscles: ['GLUTES', 'QUADRICEPS'],
      equipment: ['DUMBBELL'],
      matchReason: 'SAME_PATTERN_MUSCLES_DIFFICULTY',
    }
    const replaced = initialEditor()
    replaced.workingCopy.days[0].exercises[0].exerciseCode = replacement.exerciseCode
    application.listPlanExerciseReplacements.mockResolvedValue([replacement])
    application.replacePlanExercise.mockReturnValue(replaced)
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(PlanEditorPage))
    })
    await act(async () => {
      button(renderer!, '替换').props.onClick()
      await flushPage()
    })
    if (!renderer) throw new Error('plan editor page did not render')

    expect(application.listPlanExerciseReplacements)
      .toHaveBeenCalledWith('DAY_1', 'GOBLET_SQUAT')
    expect(application.listPlanExerciseOptions).not.toHaveBeenCalled()
    expect(JSON.stringify(renderer.toJSON())).toContain('同动作模式、同主要肌群、同难度')

    const replacementButton = renderer.root.find(
      (node) => node.type === 'button'
        && node.findAllByType('text').some((text) => text.props.children === '双哑铃前蹲'),
    )
    act(() => replacementButton.props.onClick())
    expect(application.replacePlanExercise)
      .toHaveBeenCalledWith('DAY_1', 'GOBLET_SQUAT', replacement)
  })

  it('explains an empty reviewed replacement set without pretending the request failed', async () => {
    application.listPlanExerciseReplacements.mockResolvedValue([])
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(PlanEditorPage))
    })
    await act(async () => {
      button(renderer!, '替换').props.onClick()
      await flushPage()
    })
    if (!renderer) throw new Error('plan editor page did not render')

    expect(JSON.stringify(renderer.toJSON()))
      .toContain('当前没有与原动作模式、主要肌群和难度一致且符合器械条件的替换动作')
    expect(application.listPlanExerciseOptions).not.toHaveBeenCalled()
  })

  it('progressively discloses one day, one exercise, and optional advanced tools', () => {
    application.getPlanEditor.mockReturnValue(expandedEditor())
    let renderer: ReactTestRenderer | undefined

    act(() => {
      renderer = TestRenderer.create(createElement(PlanEditorPage))
    })
    if (!renderer) throw new Error('plan editor page did not render')

    const daySummaries = renderer.root.findAll(
      (node) => node.type === 'button'
        && String(node.props.className).split(' ').includes('editor-day-summary'),
    )
    const exerciseSummaries = renderer.root.findAll(
      (node) => node.type === 'button'
        && String(node.props.className).split(' ').includes('editor-exercise-summary'),
    )
    expect(daySummaries).toHaveLength(2)
    expect(exerciseSummaries).toHaveLength(2)
    expect(renderer.root.findAllByType('input')).toHaveLength(5)
    expect(renderer.root.findAll(
      (node) => node.props.className === 'section-title'
        && node.children.join('') === '训练日二 · 动作设置',
    )).toHaveLength(0)

    act(() => daySummaries[1].props.onClick())
    expect(renderer.root.findAllByType('input')).toHaveLength(5)
    expect(renderer.root.findAll(
      (node) => node.props.className === 'section-title'
        && node.children.join('') === '训练日二 · 动作设置',
    )).toHaveLength(1)

    expect(renderer.root.findAll(
      (node) => node.type === 'button' && node.props.className === 'editor-advanced-toggle',
    )).toHaveLength(1)
    expect(JSON.stringify(renderer.toJSON())).not.toContain('查看系统调整建议')

    act(() => renderer!.root.find(
      (node) => node.type === 'button' && node.props.className === 'editor-advanced-toggle',
    ).props.onClick())
    expect(JSON.stringify(renderer.toJSON())).toContain('查看系统调整建议')

    const sticky = renderer.root.find(
      (node) => node.type === 'view' && node.props.className === 'action-row action-row--sticky',
    )
    expect(sticky.findAllByType('button')).toHaveLength(1)
  })
})
