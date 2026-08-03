import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'

import type { ActivePlanData } from '../src/application/models'
import type { OnboardingPersistencePort } from '../src/application/onboarding'
import type { PlanPersistencePort } from '../src/application/ports'
import { createFitnessApplication } from '../src/application/useCases'

const projectRoot = resolve(import.meta.dirname, '..')

function source(path: string): string {
  return readFileSync(resolve(projectRoot, path), 'utf8')
}

const candidate = {
  candidateId: 'candidate-recommended',
  plan: {
    templateCode: 'beginner-three-day',
    name: '全身基础训练',
    days: [{
      code: 'day-a',
      name: '训练日 A',
      exercises: [{
        exerciseCode: 'goblet-squat',
        workSets: 3,
        repMin: 8,
        repMax: 12,
        restSeconds: 120,
        weightStatus: 'NEEDS_CALIBRATION' as const,
      }],
    }],
    locks: {},
  },
  validationIssues: [],
  ruleReference: {
    ruleVersion: 'r1',
    templateVersion: 't1',
    contentVersion: 'c1',
  },
  lockedFieldOutcomes: {},
  explanationStatus: 'READY' as const,
  explanation: '依据你的目标、频率与器械条件生成。',
  expiresAt: '2026-08-03T00:00:00Z',
}

const activePlan: ActivePlanData = {
  planId: 'plan-recommended',
  activeVersion: {
    id: 'version-1',
    planId: 'plan-recommended',
    versionNumber: 1,
    sourceType: 'INITIAL',
    plan: candidate.plan,
    ruleReference: candidate.ruleReference,
    confirmedWarningCodes: [],
    createdAt: '2026-08-02T00:00:00Z',
  },
}

function onboardingPort(): OnboardingPersistencePort {
  return {
    getProfileVersion: vi.fn().mockResolvedValue(0),
    getEquipmentVersion: vi.fn().mockResolvedValue(0),
    getPreferencesVersion: vi.fn().mockResolvedValue(0),
    saveProfile: vi.fn().mockResolvedValue({ version: 1 }),
    saveEquipment: vi.fn().mockResolvedValue({ version: 1 }),
    savePreferences: vi.fn().mockResolvedValue({ version: 1 }),
    generateCandidate: vi.fn().mockResolvedValue({
      status: 'CANDIDATE_READY',
      candidate,
      validationIssues: [],
      lockedFieldOutcomes: {},
    }),
  }
}

describe('recommended plan first-use flow', () => {
  it('starts from the scientific recommendation without exposing the plan editor', () => {
    const page = source('src/presentation/pages/plan-candidates/index.tsx')
    const config = source('src/presentation/pages/plan-candidates/index.config.ts')

    expect(config).toContain("navigationBarTitleText: '科学训练方案'")
    expect(page).toContain('你的科学训练方案')
    expect(page).toContain('开始第一次训练')
    expect(page).toContain('application.activateCandidate()')
    expect(page).toContain("navigation.replace('WORKOUT_PREPARE')")
    expect(page).not.toContain('openCandidateEditor')
    expect(page).not.toContain("'PLAN_EDITOR'")
    expect(page).not.toContain('查看并确认计划')
    expect(page).not.toMatch(/USER_LOCKED|RULE_LOCKED/)
  })

  it('moves manual plan editing out of the primary plan experience', () => {
    const page = source('src/presentation/pages/plan/index.tsx')

    expect(page).not.toContain('openPlanEditor')
    expect(page).not.toContain("'PLAN_EDITOR'")
    expect(page).not.toContain('重新优化（仅预览）')
    expect(page).toContain('训练反馈与调整建议')
    expect(page).toContain("navigation.replace('HISTORY')")
  })

  it('distinguishes loading, empty, and failed active-plan states', () => {
    const page = source('src/presentation/pages/plan/index.tsx')

    expect(page).toContain("loading ? '正在读取计划'")
    expect(page).toContain('完成基础档案后，系统会直接给出科学训练建议。')
    expect(page).toContain("error ? '重试加载'")
    expect(page).toContain('if (error)')
    expect(page).toContain('void loadPlan()')
    expect(page).toContain('disabled={loading}')
  })

  it('keeps the new recommendation surfaces safe-area aware and touch friendly', () => {
    const recommendationStyles = source('src/presentation/pages/plan-candidates/index.scss')
    const planStyles = source('src/presentation/pages/plan/index.scss')

    for (const token of ['#082f28', '#0b5c4d', '#55d6a6', '#f7f8f3']) {
      expect(recommendationStyles.toLowerCase()).toContain(token)
      expect(planStyles.toLowerCase()).toContain(token)
    }
    expect(recommendationStyles).toContain('env(safe-area-inset-bottom)')
    expect(recommendationStyles).toMatch(/recommendation-actions__primary[\s\S]*min-height:\s*92px/)
    expect(planStyles).toMatch(/plan-feedback__action[\s\S]*min-height:\s*88px/)
  })

  it('activates one candidate only once across repeated start attempts', async () => {
    let finishActivation: ((value: ActivePlanData) => void) | undefined
    const createInitialPlan = vi.fn(() => new Promise<ActivePlanData>((resolveActivation) => {
      finishActivation = resolveActivation
    }))
    const planPort: PlanPersistencePort = {
      validatePlan: vi.fn(),
      createInitialPlan,
      getActivePlan: vi.fn(),
      createPlanVersion: vi.fn(),
      previewRebalance: vi.fn(),
    }
    const application = createFitnessApplication(onboardingPort(), planPort)
    await application.completeOnboarding({
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS',
      experience: 'BEGINNER',
      weeklyFrequency: 3,
      sessionMinutes: 45,
      location: 'HOME',
      equipment: [],
      preferences: [],
    })

    const firstStart = application.activateCandidate()
    const repeatedStart = application.activateCandidate()

    expect(createInitialPlan).toHaveBeenCalledTimes(1)
    finishActivation?.(activePlan)
    await expect(Promise.all([firstStart, repeatedStart])).resolves.toEqual([activePlan, activePlan])
    await expect(application.activateCandidate()).resolves.toBe(activePlan)
    expect(createInitialPlan).toHaveBeenCalledTimes(1)
  })

  it('allows a safe retry when candidate activation fails', async () => {
    const createInitialPlan = vi.fn()
      .mockRejectedValueOnce(new Error('temporary network failure'))
      .mockResolvedValueOnce(activePlan)
    const application = createFitnessApplication(onboardingPort(), {
      validatePlan: vi.fn(),
      createInitialPlan,
      getActivePlan: vi.fn(),
      createPlanVersion: vi.fn(),
      previewRebalance: vi.fn(),
    })
    await application.completeOnboarding({
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS',
      experience: 'BEGINNER',
      weeklyFrequency: 3,
      sessionMinutes: 45,
      location: 'HOME',
      equipment: [],
      preferences: [],
    })

    await expect(application.activateCandidate()).rejects.toThrow('temporary network failure')
    await expect(application.activateCandidate()).resolves.toBe(activePlan)
    expect(createInitialPlan).toHaveBeenCalledTimes(2)
  })

  it('does not let an older candidate activation overwrite the latest candidate state', async () => {
    const olderCandidate = { ...candidate, candidateId: 'candidate-older' }
    const latestCandidate = { ...candidate, candidateId: 'candidate-latest' }
    const generatedCandidates = vi.fn()
      .mockResolvedValueOnce({
        status: 'CANDIDATE_READY',
        candidate: olderCandidate,
        validationIssues: [],
        lockedFieldOutcomes: {},
      })
      .mockResolvedValueOnce({
        status: 'CANDIDATE_READY',
        candidate: latestCandidate,
        validationIssues: [],
        lockedFieldOutcomes: {},
      })
    const pending = new Map<string, (value: ActivePlanData) => void>()
    const createInitialPlan = vi.fn((candidateId: string) => new Promise<ActivePlanData>((resolveActivation) => {
      pending.set(candidateId, resolveActivation)
    }))
    const port = onboardingPort()
    port.generateCandidate = generatedCandidates
    const application = createFitnessApplication(port, {
      validatePlan: vi.fn(),
      createInitialPlan,
      getActivePlan: vi.fn(),
      createPlanVersion: vi.fn(),
      previewRebalance: vi.fn(),
    })
    const onboardingDraft = {
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS' as const,
      experience: 'BEGINNER' as const,
      weeklyFrequency: 3 as const,
      sessionMinutes: 45 as const,
      location: 'HOME' as const,
      equipment: [],
      preferences: [],
    }

    await application.completeOnboarding(onboardingDraft)
    const olderActivation = application.activateCandidate()
    await application.completeOnboarding(onboardingDraft)
    const latestActivation = application.activateCandidate()
    const latestPlan = {
      ...activePlan,
      planId: 'plan-latest',
      activeVersion: {
        ...activePlan.activeVersion,
        id: 'version-latest',
        planId: 'plan-latest',
      },
    }

    pending.get('candidate-latest')?.(latestPlan)
    await expect(latestActivation).resolves.toEqual(latestPlan)
    pending.get('candidate-older')?.(activePlan)
    await expect(olderActivation).resolves.toEqual(activePlan)

    expect(application.getCandidate()?.candidateId).toBe('candidate-latest')
    expect(application.getActivePlan()).toEqual(latestPlan)
  })

  it('cancels a stale candidate editor save before it can activate or version the latest candidate', async () => {
    const olderCandidate = { ...candidate, candidateId: 'candidate-editor-older' }
    const latestCandidate = {
      ...candidate,
      candidateId: 'candidate-editor-latest',
      plan: { ...candidate.plan, name: '新的科学计划' },
    }
    const port = onboardingPort()
    port.generateCandidate = vi.fn()
      .mockResolvedValueOnce({
        status: 'CANDIDATE_READY',
        candidate: olderCandidate,
        validationIssues: [],
        lockedFieldOutcomes: {},
      })
      .mockResolvedValueOnce({
        status: 'CANDIDATE_READY',
        candidate: latestCandidate,
        validationIssues: [],
        lockedFieldOutcomes: {},
      })
    let finishValidation: ((value: { valid: true; validationIssues: [] }) => void) | undefined
    const validatePlan = vi.fn(() => new Promise<{ valid: true; validationIssues: [] }>((resolveValidation) => {
      finishValidation = resolveValidation
    }))
    const createInitialPlan = vi.fn().mockResolvedValue(activePlan)
    const createPlanVersion = vi.fn()
    const application = createFitnessApplication(port, {
      validatePlan,
      createInitialPlan,
      getActivePlan: vi.fn(),
      createPlanVersion,
      previewRebalance: vi.fn(),
    })
    const onboardingDraft = {
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS' as const,
      experience: 'BEGINNER' as const,
      weeklyFrequency: 3 as const,
      sessionMinutes: 45 as const,
      location: 'HOME' as const,
      equipment: [],
      preferences: [],
    }

    await application.completeOnboarding(onboardingDraft)
    application.openCandidateEditor()
    application.editPlanNumber('day-a', 'goblet-squat', 'restSeconds', 150)
    const staleSave = application.saveEditor()
    await application.completeOnboarding(onboardingDraft)
    finishValidation?.({ valid: true, validationIssues: [] })

    await expect(staleSave).rejects.toThrow('推荐方案已更新，本次旧编辑未保存')
    expect(application.getCandidate()?.candidateId).toBe('candidate-editor-latest')
    expect(application.getActivePlan()).toBeNull()
    expect(createInitialPlan).not.toHaveBeenCalled()
    expect(createPlanVersion).not.toHaveBeenCalled()
  })
})
