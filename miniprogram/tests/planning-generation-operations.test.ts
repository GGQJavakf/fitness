import { describe, expect, it, vi } from 'vitest'

vi.mock('@tarojs/taro', () => ({ default: {} }))

import { createPlanningGenerationOperations } from '../src/platform/weapp/featureRoots/planningCompositionRoot'
import { createUserGenerationLease } from '../src/platform/weapp/sharedPlatformKernel'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

function createDependencies() {
  const userGeneration = createUserGenerationLease()
  return {
    userGeneration,
    fitness: {
      completeOnboarding: vi.fn(),
      selectPlanPreset: vi.fn(),
      activateCandidate: vi.fn(),
      openCandidateEditor: vi.fn(),
      saveEditor: vi.fn(),
    },
    navigation: {
      open: vi.fn().mockResolvedValue(undefined),
      replace: vi.fn().mockResolvedValue(undefined),
    },
    telemetry: { track: vi.fn() },
  }
}

const candidate = {
  status: 'READY' as const,
  canContinue: true,
  candidateId: 'candidate-a',
  explanationMessage: '',
  notices: [],
  days: [],
}

const activePlan = {
  planId: 'plan-a',
  activeVersion: { versionNumber: 2 },
}

const editor = {
  baseVersion: 2,
  warningConfirmationToken: undefined,
  conflict: undefined,
  validationResult: { validationIssues: [] },
}

describe('planning generation-composed operations', () => {
  it('does not emit onboarding telemetry or open candidates for a stale completion', async () => {
    const dependencies = createDependencies()
    const completed = deferred<typeof candidate>()
    dependencies.fitness.completeOnboarding.mockReturnValueOnce(completed.promise)
    const operations = createPlanningGenerationOperations(dependencies)

    const pending = operations.completeOnboardingAndOpenCandidates({
      adultConfirmed: true,
      safetyAccepted: true,
      weeklyFrequency: 3,
      sessionMinutes: 45,
      equipment: [],
      preferences: [],
      aiConsentGranted: false,
    })
    completed.resolve(candidate)
    dependencies.userGeneration.begin()

    await expect(pending).resolves.toBeNull()
    expect(dependencies.telemetry.track).not.toHaveBeenCalled()
    expect(dependencies.navigation.open).not.toHaveBeenCalled()
  })

  it('does not open candidates for a stale preset selection', async () => {
    const dependencies = createDependencies()
    const selected = deferred<typeof candidate>()
    dependencies.fitness.selectPlanPreset.mockReturnValueOnce(selected.promise)
    const operations = createPlanningGenerationOperations(dependencies)

    const pending = operations.selectPlanPresetAndOpenCandidates('PRESET_A')
    selected.resolve(candidate)
    dependencies.userGeneration.begin()

    await expect(pending).resolves.toBeNull()
    expect(dependencies.navigation.open).not.toHaveBeenCalled()
  })

  it('does not emit confirmation telemetry or open preparation for a stale activation', async () => {
    const dependencies = createDependencies()
    const activated = deferred<typeof activePlan>()
    dependencies.fitness.activateCandidate.mockReturnValueOnce(activated.promise)
    const operations = createPlanningGenerationOperations(dependencies)

    const pending = operations.activateCandidateAndOpenWorkoutPreparation()
    activated.resolve(activePlan)
    dependencies.userGeneration.begin()

    await expect(pending).resolves.toBeNull()
    expect(dependencies.telemetry.track).not.toHaveBeenCalled()
    expect(dependencies.navigation.replace).not.toHaveBeenCalled()
  })

  it('opens a candidate editor without activation and suppresses stale navigation completion', async () => {
    const dependencies = createDependencies()
    const navigation = deferred<void>()
    dependencies.fitness.openCandidateEditor.mockReturnValue(editor)
    dependencies.navigation.open.mockReturnValueOnce(navigation.promise)
    const operations = createPlanningGenerationOperations(dependencies)

    const pending = operations.activateCandidateAndOpenEditor()
    expect(dependencies.fitness.openCandidateEditor).toHaveBeenCalledOnce()
    expect(dependencies.fitness.activateCandidate).not.toHaveBeenCalled()
    dependencies.userGeneration.begin()
    navigation.resolve()

    await expect(pending).resolves.toBeNull()
    expect(dependencies.navigation.open).toHaveBeenCalledWith('PLAN_EDITOR')
  })

  it('does not update telemetry or open the plan for a stale editor save', async () => {
    const dependencies = createDependencies()
    const saved = deferred<typeof editor>()
    dependencies.fitness.saveEditor.mockReturnValueOnce(saved.promise)
    const operations = createPlanningGenerationOperations(dependencies)

    const pending = operations.saveEditorAndOpenPlan(1)
    saved.resolve(editor)
    dependencies.userGeneration.begin()

    await expect(pending).resolves.toBeNull()
    expect(dependencies.telemetry.track).not.toHaveBeenCalled()
    expect(dependencies.navigation.replace).not.toHaveBeenCalled()
  })
})
