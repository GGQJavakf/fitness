import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApplicationError } from '../src/application/errors'

const application = vi.hoisted(() => ({
  getCandidate: vi.fn(),
  requestPlanExplanation: vi.fn(),
  activateCandidate: vi.fn(),
  activateCandidateAndOpenWorkoutPreparation: vi.fn(),
  activateCandidateAndOpenEditor: vi.fn(),
  openCandidateEditor: vi.fn(),
  resumeOnboarding: vi.fn(),
  openPlanEditor: vi.fn(),
  getActivePlan: vi.fn(),
  loadActivePlan: vi.fn(),
  navigation: {
    open: vi.fn(),
    replace: vi.fn(),
  },
  telemetry: {
    track: vi.fn(),
  },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/featureRoots/planningCompositionRoot', () => ({
  getPlanningApplication: () => application,
}))

const { default: PlanCandidatesPage } = await import(
  '../src/presentation/pages/plan-candidates'
)
const { default: PlanPage } = await import('../src/presentation/pages/plan')

const candidate = {
  candidateId: 'candidate-runtime',
  status: 'READY' as const,
  canContinue: true,
  generationSource: 'FALLBACK_RULE_PLAN' as const,
  generationLabel: '规则生成计划 · 已通过安全校验',
  name: '全身基础训练候选',
  trainingSplit: 'FULL_BODY' as const,
  executionRules: ['动作按计划顺序完成。'],
  progressionRules: ['达到目标次数上限后再增加重量。'],
  explanationMessage: '依据训练目标、频率和器械条件生成。',
  notices: [],
  days: [{
    code: 'DAY_A',
    name: '训练日 A',
    notes: ['候选训练日提示'],
    exercises: [{
      exerciseCode: 'GOBLET_SQUAT',
      workSets: 3,
      repRange: '8～12 次',
      restLabel: '休息 90 秒',
      weightLabel: '首次训练中校准',
      notes: ['候选动作提示'],
    }],
  }],
}

const activePlan = {
  planId: 'plan-runtime',
  activeVersion: {
    id: 'version-runtime',
    planId: 'plan-runtime',
    versionNumber: 1,
    sourceType: 'INITIAL',
    plan: {
      templateCode: 'FULL_BODY_3_DAY_V1',
      trainingSplit: 'FULL_BODY' as const,
      name: '全身基础训练',
      days: [{
        code: 'DAY_A',
        name: '训练日 A',
        notes: ['活动计划训练日提示'],
        exercises: [{
          exerciseCode: 'GOBLET_SQUAT',
          workSets: 3,
          repMin: 8,
          repMax: 12,
          restSeconds: 90,
          weightStatus: 'NEEDS_CALIBRATION',
          notes: ['活动计划动作提示'],
        }],
      }],
      locks: {},
    },
    ruleReference: {
      ruleVersion: '1.4.0',
      templateVersion: '1.6.0',
      contentVersion: '1.6.0',
    },
    confirmedWarningCodes: [],
    createdAt: '2026-08-12T00:00:00Z',
  },
}

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button' && node.props.children === label,
  )
}

function renderedText(renderer: ReactTestRenderer): string {
  return JSON.stringify(renderer.toJSON())
}

async function flushPage(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

describe('plan pages runtime interactions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    application.getCandidate.mockReturnValue(candidate)
    application.activateCandidate.mockResolvedValue(activePlan)
    application.requestPlanExplanation.mockResolvedValue({ content: 'AI explanation' })
    application.getActivePlan.mockReturnValue(activePlan)
    application.loadActivePlan.mockResolvedValue(activePlan)
    application.activateCandidateAndOpenWorkoutPreparation.mockImplementation(async () => {
      const activated = await application.activateCandidate()
      application.telemetry.track('plan_confirmed', {
        versionNumber: activated.activeVersion.versionNumber,
      })
      await application.navigation.replace('WORKOUT_PREPARE')
      return activated
    })
    application.activateCandidateAndOpenEditor.mockImplementation(async () => {
      const editor = application.openCandidateEditor()
      await application.navigation.open('PLAN_EDITOR')
      return editor
    })
  })

  it('activates the rule-generated candidate and routes the first workout exactly once', async () => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanCandidatesPage))
    })
    if (!renderer) throw new Error('candidate page did not render')

    expect(renderedText(renderer)).toContain('你的规则生成训练方案')
    expect(renderedText(renderer)).toContain('规则生成计划 · 已通过安全校验')
    expect(renderedText(renderer)).toContain('训练提示')
    expect(renderedText(renderer)).toContain('动作提示')
    expect(renderedText(renderer)).toContain('候选训练日提示')
    expect(renderedText(renderer)).toContain('候选动作提示')
    expect(renderedText(renderer)).toContain('全身基础训练候选')
    expect(renderedText(renderer)).toContain('执行规则')
    expect(renderedText(renderer)).toContain('动作按计划顺序完成。')
    expect(renderedText(renderer)).toContain('进阶规则')
    expect(renderedText(renderer)).toContain('达到目标次数上限后再增加重量。')
    expect(renderedText(renderer)).toContain('确认启用并准备训练')
    expect(renderedText(renderer)).toContain('选择系统预设')
    expect(renderedText(renderer)).toContain('修改期间不会启用计划')
    expect(renderedText(renderer)).toContain('取消返回不会创建计划')
    expect(renderedText(renderer)).not.toContain('进入修改时会先保存初始版本')
    expect(application.requestPlanExplanation).not.toHaveBeenCalled()

    await act(async () => {
      button(renderer!, '确认启用并准备训练').props.onClick()
      await flushPage()
    })

    expect(application.activateCandidate).toHaveBeenCalledOnce()
    expect(application.telemetry.track).toHaveBeenCalledWith('plan_confirmed', {
      versionNumber: 1,
    })
    expect(application.navigation.replace).toHaveBeenCalledWith('WORKOUT_PREPARE')

    button(renderer, '选择系统预设').props.onClick()
    expect(application.navigation.open).toHaveBeenCalledWith('PLAN_PRESETS')
  })

  it('does not label every system preset as a five-day hypertrophy plan', async () => {
    application.getCandidate.mockReturnValue({
      ...candidate,
      generationSource: 'SYSTEM_PRESET',
      generationLabel: '系统个人预设 · 完整处方已载入',
    })

    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanCandidatesPage))
    })
    if (!renderer) throw new Error('system preset candidate page did not render')

    const copy = renderedText(renderer)
    expect(copy).toContain('你的系统预制训练方案')
    expect(copy).not.toContain('你的五日增肌个人预设')
  })

  it('coalesces rapid candidate activation clicks before React disables the action', async () => {
    const activation = deferred<typeof activePlan>()
    application.activateCandidate.mockReturnValueOnce(activation.promise)
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanCandidatesPage))
    })
    if (!renderer) throw new Error('candidate page did not render')

    const start = button(renderer, '确认启用并准备训练')
    act(() => {
      start.props.onClick()
      start.props.onClick()
    })
    expect(application.activateCandidate).toHaveBeenCalledOnce()

    await act(async () => {
      activation.resolve(activePlan)
      await flushPage()
    })
    expect(application.navigation.replace).toHaveBeenCalledOnce()
    expect(application.telemetry.track).toHaveBeenCalledOnce()
  })

  it('returns a recovery-blocked candidate directly to the schedule step', async () => {
    application.getCandidate.mockReturnValue({
      status: 'NO_CANDIDATE',
      canContinue: false,
      explanationMessage: '',
      notices: [],
      days: [],
      reason: '当前每周训练频率会让相同主肌群恢复不足，请降低训练频率后重试。',
      action: {
        label: '返回调整训练频率',
        route: 'ONBOARDING_SCHEDULE',
      },
    })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanCandidatesPage))
    })
    if (!renderer) throw new Error('candidate page did not render')

    expect(renderedText(renderer)).toContain('相同主肌群恢复不足')
    await act(async () => {
      button(renderer!, '返回调整训练频率').props.onClick()
      await flushPage()
    })

    expect(application.resumeOnboarding).toHaveBeenCalledWith('ONBOARDING_SCHEDULE')
    expect(application.navigation.replace).toHaveBeenCalledWith('ONBOARDING')
  })

  it('opens the candidate editor without activation and routes exercise guidance with its code', async () => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanCandidatesPage))
    })
    if (!renderer) throw new Error('candidate page did not render')

    button(renderer, '查看动作指导').props.onClick()
    expect(application.navigation.open).toHaveBeenCalledWith('EXERCISE_DETAIL', {
      exerciseCode: 'GOBLET_SQUAT',
    })

    await act(async () => {
      button(renderer!, '修改训练计划').props.onClick()
      await flushPage()
    })

    expect(application.activateCandidate).not.toHaveBeenCalled()
    expect(application.openCandidateEditor).toHaveBeenCalledOnce()
    expect(application.openPlanEditor).not.toHaveBeenCalled()
    expect(application.navigation.open).toHaveBeenCalledWith('PLAN_EDITOR')
  })

  it('opens the active plan editor, guidance, workout, and feedback destinations', async () => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPage))
      await flushPage()
    })
    if (!renderer) throw new Error('plan page did not render')

    const copy = renderedText(renderer)
    expect(copy).toContain('规则生成计划 · 已通过安全校验')
    expect(copy).toContain('确定性规则引擎生成')
    expect(copy).toContain('全身训练')
    expect(copy).toContain('训练分化')
    expect(copy).toContain('训练提示')
    expect(copy).toContain('动作提示')
    expect(copy).toContain('活动计划训练日提示')
    expect(copy).toContain('活动计划动作提示')
    expect(copy).not.toContain('基础保底计划')
    expect(copy).not.toContain('AI 生成暂不可用')

    button(renderer, '查看动作指导').props.onClick()
    button(renderer, '开始今日训练').props.onClick()
    button(renderer, '查看训练反馈与调整建议').props.onClick()
    await act(async () => {
      button(renderer!, '修改训练计划').props.onClick()
      await flushPage()
    })

    expect(application.navigation.open).toHaveBeenCalledWith('EXERCISE_DETAIL', {
      exerciseCode: 'GOBLET_SQUAT',
    })
    expect(application.navigation.open).toHaveBeenCalledWith('WORKOUT_PREPARE')
    expect(application.navigation.replace).toHaveBeenCalledWith('HISTORY')
    expect(application.openPlanEditor).toHaveBeenCalledOnce()
    expect(application.navigation.open).toHaveBeenCalledWith('PLAN_EDITOR')
  })

  it('keeps an activated system preset editable without claiming source endorsement', async () => {
    const presetPlan = {
      ...activePlan,
      activeVersion: {
        ...activePlan.activeVersion,
        plan: {
          ...activePlan.activeVersion.plan,
          presetCode: 'PERSONAL_5_DAY_HYPERTROPHY_V1',
          presetVersion: '1.0.0',
        },
      },
    }
    application.getActivePlan.mockReturnValue(presetPlan)
    application.loadActivePlan.mockResolvedValue(presetPlan)

    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPage))
      await flushPage()
    })
    if (!renderer) throw new Error('preset plan page did not render')

    const copy = renderedText(renderer)
    expect(copy).toContain('系统个人预设 · 已按确认版本启用')
    expect(copy).not.toContain('来源可追溯')
    expect(copy).not.toContain('处方已锁定')

    await act(async () => {
      button(renderer!, '修改训练计划').props.onClick()
      await flushPage()
    })

    expect(application.openPlanEditor).toHaveBeenCalledOnce()
    expect(application.navigation.open).toHaveBeenCalledWith('PLAN_EDITOR')
  })

  it('renders a failed load and retries through the same live action', async () => {
    application.getActivePlan.mockReturnValue(null)
    application.loadActivePlan
      .mockRejectedValueOnce(new ApplicationError('INTERNAL_ERROR', '服务暂时不可用，请稍后重试'))
      .mockResolvedValueOnce(activePlan)
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPage))
      await flushPage()
    })
    if (!renderer) throw new Error('plan page did not render')

    expect(renderedText(renderer)).toContain('计划暂时不可用')
    expect(renderedText(renderer)).toContain('服务暂时不可用，请稍后重试')
    await act(async () => {
      button(renderer!, '重试加载').props.onClick()
      await flushPage()
    })

    expect(application.loadActivePlan).toHaveBeenCalledTimes(2)
    expect(renderedText(renderer)).toContain('全身基础训练')
  })

  it('leaves authentication-failure navigation to the shared handler without exposing a network error', async () => {
    application.getActivePlan.mockReturnValue(null)
    application.loadActivePlan.mockRejectedValue(
      new ApplicationError('AUTHENTICATION_REQUIRED', '登录状态已失效，请重新登录'),
    )
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPage))
      await flushPage()
    })
    if (!renderer) throw new Error('plan page did not render')

    expect(application.navigation.replace).not.toHaveBeenCalled()
    expect(renderedText(renderer)).not.toContain('网络连接失败')
    expect(renderedText(renderer)).not.toContain('登录状态已失效')
  })
})
