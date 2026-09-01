import type { PageNavigationPort } from '../../../application/navigation'
import type { OnboardingDraft } from '../../../application/onboarding'
import type { PlanEditorState } from '../../../application/planEditor'
import { createFitnessApplication, type FitnessApplication } from '../../../application/useCases'
import { createRetryableLazyValue } from '../retryableLazy'
import { getWeappFeatureCore } from '../featureCore'
import type { UserGenerationLease } from '../sharedPlatformKernel'
import { createWechatTelemetryReporter } from '../WechatTelemetryReporter'
import { createWeappAiRuntime } from './aiRuntime'

interface PlanningGenerationOperationsDependencies {
  readonly fitness: Pick<
    FitnessApplication,
    | 'completeOnboarding'
    | 'selectPlanPreset'
    | 'activateCandidate'
    | 'openCandidateEditor'
    | 'saveEditor'
  >
  readonly userGeneration: UserGenerationLease
  readonly navigation: Pick<PageNavigationPort, 'open' | 'replace'>
  readonly telemetry: {
    track(event: string, payload?: Readonly<Record<string, unknown>>): void
  }
}

export function createPlanningGenerationOperations(
  dependencies: PlanningGenerationOperationsDependencies,
) {
  function assertCurrent(generation: number): void {
    dependencies.userGeneration.assertCurrent(generation)
  }

  async function awaitCurrent<T>(generation: number, operation: () => Promise<T>): Promise<T> {
    assertCurrent(generation)
    try {
      const result = await operation()
      assertCurrent(generation)
      return result
    } catch (error) {
      assertCurrent(generation)
      throw error
    }
  }

  function wasInvalidated(generation: number): boolean {
    try {
      assertCurrent(generation)
      return false
    } catch {
      return true
    }
  }

  async function runCurrent<T>(
    generation: number,
    operation: () => Promise<T>,
  ): Promise<T | null> {
    try {
      return await operation()
    } catch (error) {
      if (wasInvalidated(generation)) return null
      throw error
    }
  }

  return {
    async completeOnboardingAndOpenCandidates(draft: OnboardingDraft) {
      const generation = dependencies.userGeneration.capture()
      return runCurrent(generation, async () => {
        const candidate = await awaitCurrent(
          generation,
          () => dependencies.fitness.completeOnboarding(draft),
        )
        assertCurrent(generation)
        dependencies.telemetry.track('onboarding_completed', {
          daysPerWeek: draft.weeklyFrequency!,
          sessionMinutes: draft.sessionMinutes!,
        })
        dependencies.telemetry.track('plan_generated', {
          result: candidate.status === 'READY' ? 'ready' : 'needs_adjustment',
          issueCount: candidate.status === 'READY' ? 0 : 1,
        })
        assertCurrent(generation)
        await awaitCurrent(
          generation,
          () => Promise.resolve(dependencies.navigation.open('PLAN_CANDIDATES')),
        )
        return candidate
      })
    },

    async selectPlanPresetAndOpenCandidates(presetCode: string) {
      const generation = dependencies.userGeneration.capture()
      return runCurrent(generation, async () => {
        const candidate = await awaitCurrent(
          generation,
          () => dependencies.fitness.selectPlanPreset(presetCode),
        )
        if (candidate.canContinue) {
          await awaitCurrent(
            generation,
            () => Promise.resolve(dependencies.navigation.open('PLAN_CANDIDATES')),
          )
        }
        return candidate
      })
    },

    async activateCandidateAndOpenWorkoutPreparation() {
      const generation = dependencies.userGeneration.capture()
      return runCurrent(generation, async () => {
        const activePlan = await awaitCurrent(
          generation,
          () => dependencies.fitness.activateCandidate(),
        )
        assertCurrent(generation)
        dependencies.telemetry.track('plan_confirmed', {
          versionNumber: activePlan.activeVersion.versionNumber,
        })
        assertCurrent(generation)
        await awaitCurrent(
          generation,
          () => Promise.resolve(dependencies.navigation.replace('WORKOUT_PREPARE')),
        )
        return activePlan
      })
    },

    async activateCandidateAndOpenEditor(): Promise<PlanEditorState | null> {
      const generation = dependencies.userGeneration.capture()
      return runCurrent(generation, async () => {
        const editor = dependencies.fitness.openCandidateEditor()
        assertCurrent(generation)
        await awaitCurrent(
          generation,
          () => Promise.resolve(dependencies.navigation.open('PLAN_EDITOR')),
        )
        return editor
      })
    },

    async saveEditorAndOpenPlan(previousVersion: number): Promise<PlanEditorState | null> {
      const generation = dependencies.userGeneration.capture()
      return runCurrent(generation, async () => {
        const current = await awaitCurrent(generation, () => dependencies.fitness.saveEditor())
        const versionAdvanced = current.baseVersion > previousVersion
        const canOpenPlan = versionAdvanced
          && !current.warningConfirmationToken
          && !current.conflict
          && !current.validationResult.validationIssues.some((issue) => issue.severity === 'ERROR')
        if (versionAdvanced) {
          assertCurrent(generation)
          dependencies.telemetry.track('plan_confirmed', { versionNumber: current.baseVersion })
          assertCurrent(generation)
        }
        if (canOpenPlan) {
          await awaitCurrent(
            generation,
            () => Promise.resolve(dependencies.navigation.replace('PLAN')),
          )
        }
        return current
      })
    },
  }
}

function createPlanningApplication() {
  const core = getWeappFeatureCore()
  const ai = createWeappAiRuntime(core.userGeneration)
  const fitness = createFitnessApplication(core.api, core.api, ai.plan)
  core.userScopedState.register(() => fitness.clearUserState())
  const telemetry = createWechatTelemetryReporter()
  const generationOperations = createPlanningGenerationOperations({
    fitness,
    userGeneration: core.userGeneration,
    navigation: core.navigation,
    telemetry,
  })

  return {
    ...fitness,
    ...generationOperations,
    aiPlanGenerationAvailable: ai.planGenerationAvailable,
    navigation: core.navigation,
    telemetry,
    listExercises: () => core.api.listExercises(),
    getExercisePreferences: () => core.api.getPreferences(),
    requestPlanExplanation: (candidateId: string) => {
      if (!ai.enabled) return core.api.requestPlanExplanation(candidateId)
      const candidate = fitness.getCandidate()
      if (!candidate || candidate.candidateId !== candidateId) {
        return core.api.requestPlanExplanation(candidateId)
      }
      return ai.content.generate(
        'PLAN_EXPLANATION',
        {
          candidateId,
          exercises: candidate.days.flatMap((day) => day.exercises.map((exercise) => ({
            exerciseCode: exercise.exerciseCode,
            workSets: exercise.workSets,
          }))),
        },
        () => core.api.requestPlanExplanation(candidateId),
      )
    },
  }
}

export const getPlanningApplication = createRetryableLazyValue(createPlanningApplication)
