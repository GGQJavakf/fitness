import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { describe, expect, it, vi } from 'vitest'

import { createFitnessApplication } from '../src/application/useCases'
import type { OnboardingPersistencePort } from '../src/application/onboarding'
import type { PlanPersistencePort } from '../src/application/ports'

const preset = {
  code: 'PERSONAL_5_DAY_HYPERTROPHY_V1',
  version: '1.0.0',
  name: '一周完整增肌增重训练计划',
  goal: 'HYPERTROPHY' as const,
  experience: 'INTERMEDIATE' as const,
  weeklyFrequency: 5,
  sessionMinutes: 45,
  location: 'GYM' as const,
  contentStatus: 'AI_VALIDATED' as const,
  professionalReviewStatus: 'PENDING' as const,
  availabilityStatus: 'AVAILABLE' as const,
  introductoryPhase: {
    weeks: 2,
    workSets: 2,
    targetRirMin: 3,
    targetRirMax: 4,
    transitionCondition: '动作技术稳定且恢复良好后进入目标组数',
  },
  sources: [{
    id: 'ACSM_RT_POSITION_2026',
    title: 'ACSM Resistance Training Position Stand 2026',
    url: 'https://pubmed.ncbi.nlm.nih.gov/41843416/',
    usageBoundary: '支持健康成人通用阻力训练原则，不背书具体动作表。',
    sourceKind: 'PEER_REVIEWED_POSITION_STAND' as const,
  }],
  explanationSources: [{
    id: 'ACSM_RT_SUMMARY_20260317',
    title: 'ACSM 2026 Resistance Training Guidelines Summary',
    url: 'https://acsm.org/resistance-training-guidelines-update-2026/',
    usageBoundary: '用于公众解释，与立场声明属于同一证据链。',
    sourceKind: 'PROFESSIONAL_ORGANIZATION_SUMMARY' as const,
  }],
  matchStatus: 'EXACT' as const,
  recommended: true,
  mismatchFields: [],
  days: [{
    weekday: 'MONDAY' as const,
    name: '推：胸＋肩＋三头',
    focus: '胸、肩、三头',
    estimatedMinutesMin: 44,
    estimatedMinutesMax: 46,
    exerciseCount: 5,
  }],
}

const generated = {
  status: 'CANDIDATE_READY' as const,
  candidate: {
    candidateId: '00000000-0000-4000-8000-000000000101',
    generationSource: 'SYSTEM_PRESET' as const,
    plan: {
      templateCode: 'PERSONAL_5_DAY_HYPERTROPHY_V1',
      trainingSplit: 'BODY_PART_FIVE_DAY' as const,
      name: preset.name,
      presetCode: preset.code,
      presetVersion: preset.version,
      executionRules: ['复合动作保留约 2 次余力。'],
      progressionRules: ['使用双进阶法。'],
      days: [{
        code: 'MONDAY_PUSH',
        name: '周一｜推：胸＋肩＋三头',
        weekday: 'MONDAY' as const,
        focus: '胸、肩、三头',
        estimatedMinutesMin: 44,
        estimatedMinutesMax: 46,
        warmup: [{ instruction: '跑步机快走或慢跑', prescription: '1 分钟', optional: false }],
        notes: ['预热组只唤醒动作，不做到吃力'],
        exercises: [{
          exerciseCode: 'SMITH_FLAT_BENCH_PRESS',
          workSets: 4,
          repMin: 6,
          repMax: 10,
          restSeconds: 120,
          weightStatus: 'NEEDS_CALIBRATION' as const,
          targetRirMin: 2,
          targetRirMax: 2,
          eccentricSeconds: 2,
          notes: ['杠铃轨迹保持稳定'],
        }],
      }],
      locks: {},
    },
    validationIssues: [],
    ruleReference: { ruleVersion: '1.4.0', templateVersion: '1.6.0', contentVersion: '1.9.0' },
    lockedFieldOutcomes: {},
    explanationStatus: 'READY' as const,
    explanation: '固定系统预设。',
    expiresAt: '2026-08-22T00:00:00Z',
  },
  validationIssues: [],
  lockedFieldOutcomes: {},
}

function onboardingPort(): OnboardingPersistencePort {
  return {
    getProfileVersion: vi.fn().mockResolvedValue(7),
    getEquipmentVersion: vi.fn().mockResolvedValue(1),
    getPreferencesVersion: vi.fn().mockResolvedValue(1),
    saveProfile: vi.fn(),
    saveEquipment: vi.fn(),
    savePreferences: vi.fn(),
    listPlanPresets: vi.fn().mockResolvedValue([preset]),
    generateCandidate: vi.fn().mockResolvedValue(generated),
  }
}

function planPort(): PlanPersistencePort {
  return {
    validatePlan: vi.fn(),
    createInitialPlan: vi.fn(),
    getActivePlan: vi.fn(),
    commitCandidate: vi.fn(),
    createPlanVersion: vi.fn(),
    previewRebalance: vi.fn(),
  }
}

describe('system plan preset application flow', () => {
  it('lists and selects the fixed preset without activating or replacing the active plan', async () => {
    const onboarding = onboardingPort()
    const plans = planPort()
    const activePlan = {
      planId: 'existing-plan',
      activeVersion: {
        id: 'existing-version',
        planId: 'existing-plan',
        versionNumber: 3,
        sourceType: 'USER_EDIT' as const,
        plan: generated.candidate.plan,
        ruleReference: generated.candidate.ruleReference,
        confirmedWarningCodes: [],
        createdAt: '2026-08-21T00:00:00Z',
      },
    }
    vi.mocked(plans.getActivePlan).mockResolvedValue(activePlan)
    const application = createFitnessApplication(onboarding, plans)

    await application.loadActivePlan()
    await expect(application.listPlanPresets()).resolves.toEqual([preset])
    await expect(application.selectPlanPreset(preset.code)).resolves.toMatchObject({
      generationSource: 'SYSTEM_PRESET',
      generationLabel: '系统个人预设 · 完整处方已载入',
      name: preset.name,
      trainingSplit: 'BODY_PART_FIVE_DAY',
      executionRules: ['复合动作保留约 2 次余力。'],
      progressionRules: ['使用双进阶法。'],
      days: [{
        estimatedMinutesLabel: '44～46 分钟',
        warmup: [{ instruction: '跑步机快走或慢跑' }],
        notes: ['预热组只唤醒动作，不做到吃力'],
        exercises: [{
          targetRirLabel: 'RIR 2～2',
          eccentricLabel: '下放约 2 秒',
          notes: ['杠铃轨迹保持稳定'],
        }],
      }],
    })
    expect(onboarding.generateCandidate).toHaveBeenCalledWith({
      profileVersion: 7,
      presetCode: preset.code,
      lockedFields: {},
      fallbackAllowed: false,
    })
    expect(plans.createInitialPlan).not.toHaveBeenCalled()
    expect(application.getActivePlan()).toBe(activePlan)
  })

  it('propagates preset catalog failures instead of treating them as an empty catalog', async () => {
    const onboarding = onboardingPort()
    vi.mocked(onboarding.listPlanPresets).mockRejectedValue(new Error('preset catalog unavailable'))
    const application = createFitnessApplication(onboarding, planPort())

    await expect(application.listPlanPresets()).rejects.toThrow('preset catalog unavailable')
  })
})

const pageApplication = vi.hoisted(() => ({
  listPlanPresets: vi.fn(),
  selectPlanPreset: vi.fn(),
  selectPlanPresetAndOpenCandidates: vi.fn(),
  navigation: { open: vi.fn() },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  ScrollView: 'scroll-view',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/featureRoots/planningCompositionRoot', () => ({
  getPlanningApplication: () => pageApplication,
}))

const { default: PlanPresetsPage } = await import('../src/presentation/pages/plan-presets')

function renderedText(renderer: ReactTestRenderer): string {
  return JSON.stringify(renderer.toJSON())
}

function button(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find((node) => node.type === 'button' && node.props.children === label)
}

describe('system plan preset page', () => {
  it('shows the independent preset and opens its preview after selection', async () => {
    pageApplication.listPlanPresets.mockResolvedValue([preset])
    pageApplication.selectPlanPreset.mockResolvedValue({ canContinue: true })
    pageApplication.selectPlanPresetAndOpenCandidates.mockImplementation(async (presetCode) => {
      const candidate = await pageApplication.selectPlanPreset(presetCode)
      if (candidate.canContinue) await pageApplication.navigation.open('PLAN_CANDIDATES')
      return candidate
    })
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPresetsPage))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    if (!renderer) throw new Error('preset page did not render')

    const copy = renderedText(renderer)
    expect(copy).toContain('一周完整增肌增重训练计划')
    expect(copy).toContain('增肌')
    expect(copy).toContain('有训练经验')
    expect(copy).toContain('健身房')
    expect(copy).not.toContain('周末双休')
    expect(copy).toContain('选择预设不会直接覆盖当前计划')
    expect(copy).toContain('依据与适用边界')
    expect(copy).toContain('AI 已校验')
    expect(copy).toContain('专业审核待完成')
    expect(renderer.root.find((node) => node.props.className === 'preset-intro-phase__dose')
      .children.join('')).toBe('前 2 周 · 每个动作 2 组 · RIR 3～4')
    expect(copy).toContain('动作技术稳定且恢复良好后进入目标组数')
    expect(copy).toContain('ACSM Resistance Training Position Stand 2026')
    expect(copy).toContain('https://pubmed.ncbi.nlm.nih.gov/41843416/')
    expect(copy).toContain('支持健康成人通用阻力训练原则，不背书具体动作表。')
    expect(copy).toContain('解释来源')
    expect(copy).toContain('权威来源支持通用原则，不代表来源机构审核或背书本预设的具体动作、组次、休息与时长')
    expect(copy).not.toContain('专业审核已完成')
    expect(button(renderer, '预览并选择此计划').props.id).toBe(`plan-preset-${preset.code}`)

    await act(async () => {
      button(renderer!, '预览并选择此计划').props.onClick()
      await new Promise((resolve) => setTimeout(resolve, 0))
    })

    expect(pageApplication.selectPlanPreset).toHaveBeenCalledWith(preset.code)
    expect(pageApplication.navigation.open).toHaveBeenCalledWith('PLAN_CANDIDATES')
  })

  it('keeps every preset reachable while expanding only the selected plan', async () => {
    const alternatives = Array.from({ length: 5 }, (_, index) => ({
      ...preset,
      code: `PRESET_${index + 1}`,
      name: `计划 ${index + 1}`,
      days: [{ ...preset.days[0], name: `训练日 ${index + 1}` }],
    }))
    pageApplication.listPlanPresets.mockResolvedValue(alternatives)
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPresetsPage))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    if (!renderer) throw new Error('preset page did not render')

    alternatives.forEach((item) => {
      expect(button(renderer!, item.name).props.id).toBe(`plan-preset-selector-${item.code}`)
    })
    expect(button(renderer, '计划 1').props['aria-label']).toBe('计划 1，当前计划')
    expect(button(renderer, '计划 5').props['aria-label']).toBe('计划 5')
    expect(renderedText(renderer)).toContain('训练日 1')
    expect(renderedText(renderer)).not.toContain('训练日 5')

    await act(async () => {
      button(renderer!, '计划 5').props.onClick()
    })

    expect(renderedText(renderer)).toContain('训练日 5')
    expect(renderedText(renderer)).not.toContain('训练日 1')
    expect(button(renderer, '预览并选择此计划').props.id).toBe('plan-preset-PRESET_5')
    expect(button(renderer, '计划 5').props['aria-label']).toBe('计划 5，当前计划')
  })

  it('does not let the visible selection drift while a preset candidate is opening', async () => {
    const alternatives = [
      { ...preset, code: 'PRESET_A', name: '计划 A' },
      { ...preset, code: 'PRESET_B', name: '计划 B' },
    ]
    let resolveSelection: ((value: { canContinue: boolean }) => void) | undefined
    pageApplication.listPlanPresets.mockResolvedValue(alternatives)
    pageApplication.selectPlanPresetAndOpenCandidates.mockImplementation(() => (
      new Promise((resolve) => { resolveSelection = resolve })
    ))
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPresetsPage))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    if (!renderer) throw new Error('preset page did not render')

    await act(async () => {
      button(renderer!, '预览并选择此计划').props.onClick()
      await Promise.resolve()
    })

    expect(button(renderer, '计划 B').props.disabled).toBe(true)
    await act(async () => {
      button(renderer!, '计划 B').props.onClick()
    })
    expect(renderer.root.find((node) => node.type === 'button' && node.props.id === 'plan-preset-PRESET_A')).toBeTruthy()

    await act(async () => {
      resolveSelection?.({ canContinue: true })
      await Promise.resolve()
    })
    expect(button(renderer, '计划 B').props.disabled).toBe(false)
  })

  it('selects the recommended preset by default even when it is not the first item', async () => {
    const alternatives = [
      {
        ...preset,
        code: 'PARTIAL',
        name: '需调整计划',
        matchStatus: 'PARTIAL' as const,
        recommended: false,
        mismatchFields: ['SESSION_MINUTES' as const],
      },
      { ...preset, code: 'RECOMMENDED', name: '推荐计划' },
    ]
    pageApplication.listPlanPresets.mockResolvedValue(alternatives)
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPresetsPage))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    if (!renderer) throw new Error('preset page did not render')

    expect(renderedText(renderer)).toContain('推荐计划')
    expect(button(renderer, '推荐计划').props['aria-label']).toBe('推荐计划，当前计划')
    expect(renderer.root.find((node) => node.props.id === 'plan-preset-RECOMMENDED')).toBeTruthy()
  })

  it('explains partial mismatches and never requests a candidate that the backend will reject', async () => {
    const partial = {
      ...preset,
      code: 'PARTIAL',
      name: '需调整计划',
      matchStatus: 'PARTIAL' as const,
      mismatchFields: ['WEEKLY_FREQUENCY' as const, 'SESSION_MINUTES' as const],
    }
    pageApplication.listPlanPresets.mockResolvedValue([partial])
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPresetsPage))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    if (!renderer) throw new Error('preset page did not render')

    expect(renderedText(renderer)).toContain('每周训练天数')
    expect(renderedText(renderer)).toContain('单次训练时长')
    const action = button(renderer, '请先调整训练档案')
    expect(action.props.disabled).toBe(true)
    const requestCount = pageApplication.selectPlanPresetAndOpenCandidates.mock.calls.length

    await act(async () => {
      action.props.onClick()
      await Promise.resolve()
    })
    expect(pageApplication.selectPlanPresetAndOpenCandidates).toHaveBeenCalledTimes(requestCount)
  })

  it('shows a capability blocker without exposing or selecting the placeholder prescription', async () => {
    const blocked = {
      ...preset,
      code: 'BAND_PLAN_BLOCKED',
      name: '新手三日弹力带推拉腿（能力待补齐）',
      availabilityStatus: 'BLOCKED_CAPABILITY' as const,
      unavailableReason: '当前 Equipment Inventory V1 无法表达 BANDS 可用性与安全固定锚点',
      recommended: false,
    }
    pageApplication.listPlanPresets.mockResolvedValue([blocked])
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPresetsPage))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    if (!renderer) throw new Error('preset page did not render')

    const copy = renderedText(renderer)
    expect(copy).toContain('设备能力待补齐')
    expect(copy).toContain('BANDS')
    expect(copy).toContain('固定锚点')
    expect(copy).not.toContain('训练日 1')
    const action = button(renderer, '设备能力待补齐')
    expect(action.props.disabled).toBe(true)
    const requestCount = pageApplication.selectPlanPresetAndOpenCandidates.mock.calls.length

    await act(async () => {
      action.props.onClick()
      await Promise.resolve()
    })
    expect(pageApplication.selectPlanPresetAndOpenCandidates).toHaveBeenCalledTimes(requestCount)
  })
})
