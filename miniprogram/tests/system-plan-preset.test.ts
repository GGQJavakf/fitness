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
  weeklyFrequency: 5,
  sessionMinutes: 45,
  location: 'GYM' as const,
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
    ruleReference: { ruleVersion: '1.4.0', templateVersion: '1.6.0', contentVersion: '1.8.0' },
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
  navigation: { open: vi.fn() },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/compositionRoot', () => ({
  getWeappApplication: () => pageApplication,
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
    let renderer: ReactTestRenderer | undefined

    await act(async () => {
      renderer = TestRenderer.create(createElement(PlanPresetsPage))
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    if (!renderer) throw new Error('preset page did not render')

    const copy = renderedText(renderer)
    expect(copy).toContain('一周完整增肌增重训练计划')
    expect(copy).toContain('周末双休')
    expect(copy).toContain('选择预设不会直接覆盖当前计划')

    await act(async () => {
      button(renderer!, '预览并选择此计划').props.onClick()
      await new Promise((resolve) => setTimeout(resolve, 0))
    })

    expect(pageApplication.selectPlanPreset).toHaveBeenCalledWith(preset.code)
    expect(pageApplication.navigation.open).toHaveBeenCalledWith('PLAN_CANDIDATES')
  })
})
