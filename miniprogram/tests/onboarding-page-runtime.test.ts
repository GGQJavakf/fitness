import { createElement } from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { OnboardingState } from '../src/application/onboarding'

const application = vi.hoisted(() => ({
  resumeOnboarding: vi.fn(),
  listExercises: vi.fn(),
  getExercisePreferences: vi.fn(),
  completeOnboarding: vi.fn(),
  completeOnboardingAndOpenCandidates: vi.fn(),
  aiPlanGenerationAvailable: true,
  telemetry: { track: vi.fn() },
  navigation: { open: vi.fn() },
}))

vi.mock('@tarojs/components', () => ({
  Button: 'button',
  Text: 'text',
  View: 'view',
}))

vi.mock('../src/platform/weapp/featureRoots/planningCompositionRoot', () => ({
  getPlanningApplication: () => application,
}))

const { default: OnboardingPage } = await import('../src/presentation/pages/onboarding')

function completedGymState(): OnboardingState {
  return {
    stepIndex: 3,
    step: 'LOCATION_AND_EQUIPMENT',
    draft: {
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS',
      experience: 'BEGINNER',
      weeklyFrequency: 3,
      sessionMinutes: 45,
      location: 'GYM',
      equipment: [],
      preferences: [],
      aiConsentGranted: false,
    },
    errors: [],
  }
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

function simpleButton(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => {
      if (node.type !== 'button') return false
      const children = node.props.children
      return children === label
        || (Array.isArray(children)
          && children.every((child) => typeof child === 'string' || typeof child === 'number')
          && children.join('') === label)
    },
  )
}

function nestedButton(renderer: ReactTestRenderer, label: string) {
  return renderer.root.find(
    (node) => node.type === 'button'
      && node.findAllByType('text').some((text) => text.props.children === label),
  )
}

describe('live onboarding submission', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    application.aiPlanGenerationAvailable = true
    application.resumeOnboarding.mockReturnValue(completedGymState())
    application.listExercises.mockResolvedValue([])
    application.getExercisePreferences.mockResolvedValue({ items: [] })
    application.navigation.open.mockResolvedValue(undefined)
    application.completeOnboardingAndOpenCandidates.mockImplementation(async (draft) => {
      const candidate = await application.completeOnboarding(draft)
      application.telemetry.track('onboarding_completed', {
        daysPerWeek: draft.weeklyFrequency,
        sessionMinutes: draft.sessionMinutes,
      })
      application.telemetry.track('plan_generated', {
        result: candidate.status === 'READY' ? 'ready' : 'needs_adjustment',
        issueCount: candidate.status === 'READY' ? 0 : 1,
      })
      await application.navigation.open('PLAN_CANDIDATES')
      return candidate
    })
  })

  it('completes the four required steps through live controls and submits the selected schedule', async () => {
    application.resumeOnboarding.mockReturnValue({
      stepIndex: 0,
      step: 'SAFETY',
      draft: {
        adultConfirmed: false,
        safetyAccepted: false,
        equipment: [],
        preferences: [],
        aiConsentGranted: false,
      },
      errors: [],
    })
    application.completeOnboarding.mockResolvedValue({ status: 'READY' })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(OnboardingPage))
      await flushPage()
    })
    if (!renderer) throw new Error('onboarding page did not render')

    act(() => simpleButton(renderer!, '我已满 18 周岁，并理解这不是医疗或康复服务').props.onClick())
    await act(async () => {
      simpleButton(renderer!, '继续').props.onClick()
      await flushPage()
    })
    act(() => nestedButton(renderer!, '一般健身').props.onClick())
    act(() => simpleButton(renderer!, '刚开始训练').props.onClick())
    await act(async () => {
      simpleButton(renderer!, '继续').props.onClick()
      await flushPage()
    })
    act(() => renderer!.root.find(
      (node) => node.type === 'button' && node.props.id === 'onboarding-split-push_pull_legs',
    ).props.onClick())
    act(() => simpleButton(renderer!, '3 天').props.onClick())
    act(() => simpleButton(renderer!, '45 分钟').props.onClick())
    await act(async () => {
      simpleButton(renderer!, '继续').props.onClick()
      await flushPage()
    })
    act(() => nestedButton(renderer!, '健身房').props.onClick())
    await act(async () => {
      simpleButton(renderer!, '生成我的计划').props.onClick()
      await flushPage()
    })

    expect(application.completeOnboarding).toHaveBeenCalledWith(expect.objectContaining({
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS',
      experience: 'BEGINNER',
      trainingSplit: 'PUSH_PULL_LEGS',
      weeklyFrequency: 3,
      sessionMinutes: 45,
      location: 'GYM',
      equipment: expect.arrayContaining([
        expect.objectContaining({ equipmentType: 'DUMBBELL' }),
        expect.objectContaining({ equipmentType: 'CABLE' }),
      ]),
    }))
    expect(application.navigation.open).toHaveBeenCalledWith('PLAN_CANDIDATES')
    expect(JSON.stringify(renderer.toJSON())).not.toContain('额外训练偏好')
    expect(JSON.stringify(renderer.toJSON())).toContain('AI 个性化编排动作')
    expect(application.completeOnboarding).toHaveBeenCalledWith(expect.objectContaining({
      aiConsentGranted: false,
    }))
  })

  it('submits explicit AI consent only after the user selects the consent control', async () => {
    application.completeOnboarding.mockResolvedValue({ status: 'READY' })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(OnboardingPage))
      await flushPage()
    })
    if (!renderer) throw new Error('onboarding page did not render')

    act(() => simpleButton(renderer!, '同意使用 AI 个性化编排动作').props.onClick())
    await act(async () => {
      simpleButton(renderer!, '生成我的计划').props.onClick()
      await flushPage()
    })

    expect(application.completeOnboarding).toHaveBeenCalledWith(expect.objectContaining({
      aiConsentGranted: true,
    }))
  })

  it('keeps AI consent disabled when the verified release gates are not active', async () => {
    application.aiPlanGenerationAvailable = false
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(OnboardingPage))
      await flushPage()
    })
    if (!renderer) throw new Error('onboarding page did not render')

    const unavailable = simpleButton(renderer, 'AI 个性化编排暂未启用')
    expect(unavailable.props.disabled).toBe(true)
    expect(JSON.stringify(renderer.toJSON())).toContain('规则生成计划')
  })

  it('does not generate a plan until the equipment range for another location is explicit', async () => {
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(OnboardingPage))
      await flushPage()
    })
    if (!renderer) throw new Error('onboarding page did not render')

    act(() => nestedButton(renderer!, '其他场地').props.onClick())
    await act(async () => {
      simpleButton(renderer!, '生成我的计划').props.onClick()
      await flushPage()
    })

    expect(application.completeOnboarding).not.toHaveBeenCalled()
    expect(JSON.stringify(renderer.toJSON())).toContain('请选择在其他场地可使用自重训练还是基础器械')

    act(() => simpleButton(renderer!, '仅自重').props.onClick())
    application.completeOnboarding.mockResolvedValue({ status: 'READY' })
    await act(async () => {
      simpleButton(renderer!, '生成我的计划').props.onClick()
      await flushPage()
    })

    expect(application.completeOnboarding).toHaveBeenCalledTimes(1)
    expect(application.completeOnboarding).toHaveBeenCalledWith(expect.objectContaining({
      location: 'OTHER',
      equipment: [],
    }))
    expect(application.navigation.open).toHaveBeenCalledWith('PLAN_CANDIDATES')
  })

  it('coalesces repeated generate taps while the first submission is pending', async () => {
    const completion = deferred<{ status: 'READY' }>()
    application.completeOnboarding.mockReturnValue(completion.promise)
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(OnboardingPage))
      await flushPage()
    })
    if (!renderer) throw new Error('onboarding page did not render')

    const generate = simpleButton(renderer, '生成我的计划')
    act(() => {
      generate.props.onClick()
      generate.props.onClick()
    })

    expect(application.completeOnboarding).toHaveBeenCalledTimes(1)

    await act(async () => {
      completion.resolve({ status: 'READY' })
      await flushPage()
    })
    expect(application.navigation.open).toHaveBeenCalledTimes(1)
    expect(application.navigation.open).toHaveBeenCalledWith('PLAN_CANDIDATES')
  })

  it('submits an explicitly excluded exercise as a structured preference', async () => {
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
    application.completeOnboarding.mockResolvedValue({ status: 'READY' })
    let renderer: ReactTestRenderer | undefined
    await act(async () => {
      renderer = TestRenderer.create(createElement(OnboardingPage))
      await flushPage()
    })
    if (!renderer) throw new Error('onboarding page did not render')

    act(() => simpleButton(renderer!, '避开').props.onClick())
    await act(async () => {
      simpleButton(renderer!, '生成我的计划').props.onClick()
      await flushPage()
    })

    expect(application.completeOnboarding).toHaveBeenCalledWith(expect.objectContaining({
      preferencesTouched: true,
      preferences: [{ exerciseId: 'exercise-squat', preferenceType: 'EXCLUDED' }],
    }))
  })
})
