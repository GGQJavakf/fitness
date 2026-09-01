import type { AiGeneratedContent } from '../../../application/ai'
import {
  chooseAutomaticWorkoutWeight,
} from '../../../application/automaticWorkoutWeight'
import type { WorkoutHistoryPage } from '../../../application/history'
import type { ActivePlanData, PlanDay } from '../../../application/models'
import type { WorkoutCompletionResult, WorkoutCompletionType } from '../../../application/ports/WorkoutCompletionPort'
import type { ExerciseTrendData } from '../../../application/progression'
import { selectNextTrainingDayCode } from '../../../application/selectNextTrainingDay'
import type {
  CoordinatedWorkoutStartInput,
  CoordinatedWorkoutStartResult,
  WorkoutStartCoordinator,
} from '../../../application/use-cases/WorkoutStartCoordinator'
import type {
  WorkoutFlowService,
  WorkoutLocalResumeResult,
} from '../../../application/use-cases/WorkoutFlowService'
import {
  WorkoutReplacementWorkflowError,
  type WorkoutReplacementPhase,
  type WorkoutReplacementProgressObserver,
} from '../../../application/workoutReplacementWorkflow'
import {
  isWorkoutPrescriptionFinished,
  summarizeWorkout,
  type RecordWorkoutSetInput,
  type WorkoutFlowState,
} from '../../../application/workoutFlow'
import type { UserGenerationLease } from '../sharedPlatformKernel'

type WorkoutSummary = ReturnType<typeof summarizeWorkout>

export interface WorkoutLocalFirstRecoveryResult {
  readonly resumed: WorkoutLocalResumeResult
  readonly synchronization: Promise<WorkoutSynchronizationResult>
}

export type WorkoutSessionLaunchMode = 'FRESH_START' | 'RESUME_INTERRUPTED'

export type WorkoutSessionLaunchContext =
  | {
    readonly launchMode: 'FRESH_START'
    readonly clientSessionKey: string
  }
  | {
    readonly launchMode: 'RESUME_INTERRUPTED'
    readonly clientSessionKey?: string
  }

export type WorkoutSessionResumeResult =
  | { readonly kind: 'NONE' }
  | { readonly kind: 'RECOVERY_REQUIRED' }
  | { readonly kind: 'SESSION_MISMATCH' }
  | {
    readonly kind: 'ACTIVE'
    readonly launchMode: WorkoutSessionLaunchMode
    readonly resumed: WorkoutLocalResumeResult
    readonly synchronization: Promise<WorkoutSynchronizationResult>
    readonly openSummary: (() => Promise<void>) | null
  }

export interface WorkoutRecordRecoveryResult {
  readonly state: WorkoutFlowState
  readonly resumed: WorkoutLocalResumeResult | null
  readonly synchronization: Promise<WorkoutSynchronizationResult> | null
  readonly openSummary: (() => Promise<void>) | null
}

export type WorkoutSynchronizationResult =
  | { readonly kind: 'SYNCED'; readonly state: WorkoutFlowState }
  | { readonly kind: 'FAILED'; readonly error: unknown }
  | { readonly kind: 'CANCELLED' }

export type WorkoutRecordOperationResult =
  | {
    readonly kind: 'RECORDED'
    readonly state: WorkoutFlowState
    readonly synchronization: Promise<WorkoutSynchronizationResult>
    readonly openSummary: (() => Promise<void>) | null
  }
  | {
    readonly kind: 'RECORD_FAILED'
    readonly error: unknown
    readonly recovery: WorkoutRecordRecoveryResult | null
  }

export interface WorkoutPreparationLoadResult {
  readonly plan: ActivePlanData | null
  readonly history: WorkoutHistoryPage
  readonly historyUnavailable: boolean
  readonly rememberedTrainingDayCode?: string
}

export type WorkoutSummaryWorkflowResult =
  | { readonly kind: 'EMPTY' }
  | {
    readonly kind: 'LOADED'
    readonly state: WorkoutFlowState
    readonly summary: WorkoutSummary
  }
  | {
    readonly kind: 'SETTLEMENT_FAILED'
    readonly state: WorkoutFlowState
    readonly summary: WorkoutSummary
    readonly error: unknown
  }
  | {
    readonly kind: 'SETTLED'
    readonly state: WorkoutFlowState
    readonly summary: WorkoutSummary
    readonly completion: WorkoutCompletionResult
    readonly generatedSummary: AiGeneratedContent | null
  }

interface WorkoutGenerationDependencies {
  readonly userGeneration: UserGenerationLease
  readonly workouts: Pick<
    WorkoutFlowService,
    | 'load'
    | 'loadStatus'
    | 'resumeLocal'
    | 'complete'
    | 'abandonActive'
    | 'replaceActiveAndStart'
    | 'discardOrphanedLocalWorkout'
    | 'adjustRest'
    | 'setExerciseWeight'
    | 'recordSet'
    | 'beginWorkSets'
    | 'flush'
    | 'chooseOptionalSet'
    | 'discardCorruptedDraft'
  >
  readonly workoutStart: Pick<WorkoutStartCoordinator, 'start' | 'replaceActive' | 'cancelUncreatedStart'>
  readonly nextTrainingDaySelection: {
    consume(): Promise<string | undefined>
    remember(trainingDayCode: string): Promise<void>
  }
  readonly api: {
    getActivePlan(): Promise<ActivePlanData | null>
    listHistory(cursor?: string, limit?: number): Promise<WorkoutHistoryPage>
    getExerciseTrend(exerciseCode: string): Promise<ExerciseTrendData>
  }
  readonly navigation: {
    replace(
      destination: 'PLAN' | 'WORKOUT_PREPARE' | 'WORKOUT_SESSION' | 'WORKOUT_SUMMARY',
      parameters?: Readonly<Record<string, string>>,
    ): Promise<void> | void
  }
  readonly requestWorkoutSummary: (sessionId: string) => Promise<AiGeneratedContent>
  readonly delay?: (milliseconds: number) => Promise<void>
}

const emptyHistory: WorkoutHistoryPage = {
  items: [],
  hasMore: false,
}

export function createWorkoutGenerationOperations(
  dependencies: WorkoutGenerationDependencies,
) {
  const delay = dependencies.delay ?? ((milliseconds: number) => new Promise<void>(
    (resolve) => setTimeout(resolve, milliseconds),
  ))

  function assertCurrent(generation: number): void {
    dependencies.userGeneration.assertCurrent(generation)
  }

  async function awaitCurrent<T>(
    generation: number,
    operation: () => Promise<T>,
  ): Promise<T> {
    assertCurrent(generation)
    try {
      const result = await operation()
      assertCurrent(generation)
      return result
    } catch (error) {
      // Generation invalidation wins over a same-time storage/network failure.
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

  async function runPreparationOperation<T>(
    generation: number,
    operation: () => Promise<T>,
  ): Promise<T | null> {
    try {
      return await operation()
    } catch (error) {
      // Preparation is an interactive UI workflow. A stale operation must not
      // update the replacement user's page or surface an obsolete error.
      if (wasInvalidated(generation)) return null
      throw error
    }
  }

  async function navigateToWorkoutWhenStarted(
    generation: number,
    result: CoordinatedWorkoutStartResult,
  ): Promise<void> {
    if (result.kind !== 'STARTED' && result.kind !== 'RESUMED') return
    await awaitCurrent(
      generation,
      () => Promise.resolve(dependencies.navigation.replace('WORKOUT_SESSION', {
        workoutLaunchMode: result.kind === 'STARTED' ? 'FRESH_START' : 'RESUME_INTERRUPTED',
        clientSessionKey: result.state.clientSessionKey,
      })),
    )
  }

  async function settleLoadedWorkout(
    generation: number,
    state: WorkoutFlowState,
    summary: WorkoutSummary,
    completionType: WorkoutCompletionType,
  ): Promise<WorkoutSummaryWorkflowResult> {
    let completion: WorkoutCompletionResult
    try {
      completion = await awaitCurrent(
        generation,
        () => dependencies.workouts.complete(state, completionType),
      )
    } catch (error) {
      assertCurrent(generation)
      return { kind: 'SETTLEMENT_FAILED', state, summary, error }
    }

    let generatedSummary: AiGeneratedContent | null = null
    try {
      generatedSummary = await awaitCurrent(
        generation,
        () => dependencies.requestWorkoutSummary(completion.session.id),
      )
    } catch {
      assertCurrent(generation)
      // Completion is authoritative even when the optional summary provider and
      // deterministic fallback are both temporarily unavailable.
    }

    return { kind: 'SETTLED', state, summary, completion, generatedSummary }
  }

  function createSummaryNavigation(generation: number): () => Promise<void> {
    return () => awaitCurrent(
      generation,
      () => Promise.resolve(dependencies.navigation.replace('WORKOUT_SUMMARY')),
    )
  }

  async function recoverWorkoutAfterRecordFailure(
    generation: number,
    failedState: WorkoutFlowState,
  ): Promise<WorkoutRecordRecoveryResult | null> {
    let recovered: WorkoutFlowState | null
    try {
      recovered = await awaitCurrent(generation, () => dependencies.workouts.load())
    } catch {
      assertCurrent(generation)
      return null
    }
    if (!recovered || recovered.clientSessionKey !== failedState.clientSessionKey) return null
    try {
      const localFirst = await resumeLocalFirst(generation, recovered)
      return {
        state: localFirst.resumed.state,
        resumed: localFirst.resumed,
        synchronization: localFirst.synchronization,
        openSummary: isWorkoutPrescriptionFinished(localFirst.resumed.state)
          ? createSummaryNavigation(generation)
          : null,
      }
    } catch {
      assertCurrent(generation)
      return {
        state: recovered,
        resumed: null,
        synchronization: createSynchronizationTask(generation, recovered),
        openSummary: isWorkoutPrescriptionFinished(recovered)
          ? createSummaryNavigation(generation)
          : null,
      }
    }
  }

  function startSynchronization(
    generation: number,
    state: WorkoutFlowState,
  ): Promise<WorkoutSynchronizationResult> {
    return awaitCurrent(generation, () => dependencies.workouts.flush(state))
      .then((synchronized) => ({ kind: 'SYNCED' as const, state: synchronized }))
      .catch((error): WorkoutSynchronizationResult => {
        if (wasInvalidated(generation)) return { kind: 'CANCELLED' }
        return { kind: 'FAILED', error }
      })
  }

  function createSynchronizationTask(
    generation: number,
    state: WorkoutFlowState,
  ): Promise<WorkoutSynchronizationResult> {
    return state.syncStatus === 'OFFLINE_PENDING'
      ? startSynchronization(generation, state)
      : Promise.resolve({ kind: 'SYNCED', state })
  }

  async function resumeLocalFirst(
    generation: number,
    state: WorkoutFlowState,
  ): Promise<WorkoutLocalFirstRecoveryResult> {
    const resumed = await awaitCurrent(
      generation,
      () => dependencies.workouts.resumeLocal(state),
    )
    return {
      resumed,
      synchronization: createSynchronizationTask(generation, resumed.state),
    }
  }

  async function runReplacementStage<T>(
    generation: number,
    phase: WorkoutReplacementPhase,
    operation: () => Promise<T>,
  ): Promise<T> {
    try {
      return await operation()
    } catch (error) {
      assertCurrent(generation)
      throw new WorkoutReplacementWorkflowError(phase, error)
    }
  }

  async function recordWithGeneration(
    generation: number,
    state: WorkoutFlowState,
    input: RecordWorkoutSetInput,
    beginWorkSets: boolean,
    navigateWhenFinished: boolean,
  ): Promise<WorkoutRecordOperationResult> {
    let updated: WorkoutFlowState
    try {
      updated = await awaitCurrent(
        generation,
        () => dependencies.workouts.recordSet(state, input),
      )
      if (beginWorkSets) {
        updated = await awaitCurrent(
          generation,
          () => dependencies.workouts.beginWorkSets(updated),
        )
      }
    } catch (error) {
      assertCurrent(generation)
      return {
        kind: 'RECORD_FAILED',
        error,
        recovery: await recoverWorkoutAfterRecordFailure(generation, state),
      }
    }

    return {
      kind: 'RECORDED',
      state: updated,
      synchronization: startSynchronization(generation, updated),
      openSummary: navigateWhenFinished && isWorkoutPrescriptionFinished(updated)
        ? createSummaryNavigation(generation)
        : null,
    }
  }

  return {
    async loadWorkoutPreparation(): Promise<WorkoutPreparationLoadResult> {
      const generation = dependencies.userGeneration.capture()
      let historyUnavailable = false
      const historyPromise = awaitCurrent(
        generation,
        () => dependencies.api.listHistory(undefined, 50),
      ).catch((error): WorkoutHistoryPage => {
        assertCurrent(generation)
        historyUnavailable = true
        return emptyHistory
      })
      const [plan, history] = await Promise.all([
        awaitCurrent(generation, () => dependencies.api.getActivePlan()),
        historyPromise,
      ])
      assertCurrent(generation)
      const rememberedTrainingDayCode = await awaitCurrent(
        generation,
        () => dependencies.nextTrainingDaySelection.consume(),
      )
      return { plan, history, historyUnavailable, rememberedTrainingDayCode }
    },

    async startWorkout(
      input: CoordinatedWorkoutStartInput,
    ): Promise<CoordinatedWorkoutStartResult | null> {
      const generation = dependencies.userGeneration.capture()
      return runPreparationOperation(generation, async () => {
        const result = await awaitCurrent(
          generation,
          () => dependencies.workoutStart.start(input),
        )
        await navigateToWorkoutWhenStarted(generation, result)
        return result
      })
    },

    async abandonAndStartWorkout(
      state: WorkoutFlowState,
      input: CoordinatedWorkoutStartInput,
      observer?: WorkoutReplacementProgressObserver,
    ): Promise<CoordinatedWorkoutStartResult | null> {
      const generation = dependencies.userGeneration.capture()
      return runPreparationOperation(generation, async () => {
        observer?.onPhaseChanged('ENDING_ACTIVE')
        const result = await runReplacementStage(
          generation,
          'ENDING_ACTIVE',
          () => awaitCurrent(generation, () => dependencies.workoutStart.replaceActive(state, input)),
        )
        if (result.kind !== 'STARTED' && result.kind !== 'RESUMED') return result
        observer?.onPhaseChanged('OPENING_NEW')
        return runReplacementStage(generation, 'OPENING_NEW', async () => {
          await navigateToWorkoutWhenStarted(generation, result)
          return result
        })
      })
    },

    async cancelWorkoutStartAndOpenPlan(
      input: Parameters<WorkoutStartCoordinator['cancelUncreatedStart']>[0],
    ): Promise<boolean> {
      const generation = dependencies.userGeneration.capture()
      const completed = await runPreparationOperation(generation, async () => {
        await awaitCurrent(
          generation,
          () => dependencies.workoutStart.cancelUncreatedStart(input),
        )
        await awaitCurrent(
          generation,
          () => Promise.resolve(dependencies.navigation.replace('PLAN')),
        )
        return true
      })
      return completed === true
    },

    async loadWorkoutSession(
      context: WorkoutSessionLaunchContext,
    ): Promise<WorkoutSessionResumeResult> {
      const generation = dependencies.userGeneration.capture()
      const status = await awaitCurrent(generation, () => dependencies.workouts.loadStatus())
      if (status.kind !== 'ACTIVE') return status
      if (context.launchMode === 'FRESH_START'
        && (!context.clientSessionKey || context.clientSessionKey !== status.state.clientSessionKey)) {
        return { kind: 'SESSION_MISMATCH' }
      }
      const localFirst = await resumeLocalFirst(generation, status.state)
      return {
        kind: 'ACTIVE',
        launchMode: context.launchMode,
        resumed: localFirst.resumed,
        synchronization: localFirst.synchronization,
        openSummary: isWorkoutPrescriptionFinished(localFirst.resumed.state)
          ? createSummaryNavigation(generation)
          : null,
      }
    },

    async recordWorkoutSetAndSync(
      failedState: WorkoutFlowState,
      input: RecordWorkoutSetInput,
    ): Promise<WorkoutRecordOperationResult> {
      const generation = dependencies.userGeneration.capture()
      return recordWithGeneration(generation, failedState, input, false, !input.safetyFlag)
    },

    async adjustAndResumeWorkout(
      state: WorkoutFlowState,
      seconds: 15 | -15,
    ): Promise<WorkoutLocalFirstRecoveryResult> {
      const generation = dependencies.userGeneration.capture()
      const updated = await awaitCurrent(
        generation,
        () => dependencies.workouts.adjustRest(state, seconds),
      )
      return resumeLocalFirst(generation, updated)
    },

    async setAutomaticWorkoutWeight(
      state: WorkoutFlowState,
      exerciseIndex: number,
      exerciseCode: string,
      plannedWeightKg?: number,
      shouldContinue: () => boolean = () => true,
    ): Promise<{ readonly state: WorkoutFlowState; readonly weightKg: number } | null> {
      const generation = dependencies.userGeneration.capture()
      let trendPoints: ExerciseTrendData['points'] = []
      try {
        trendPoints = (await awaitCurrent(
          generation,
          () => dependencies.api.getExerciseTrend(exerciseCode),
        )).points
      } catch {
        assertCurrent(generation)
      }
      assertCurrent(generation)
      if (!shouldContinue()) return null
      const weightKg = chooseAutomaticWorkoutWeight(trendPoints, plannedWeightKg)
      const updated = await awaitCurrent(
        generation,
        () => dependencies.workouts.setExerciseWeight(state, exerciseIndex, weightKg),
      )
      return { state: updated, weightKg }
    },

    async recordRampSetAndMaybeBeginWorkSets(
      state: WorkoutFlowState,
      input: RecordWorkoutSetInput,
      beginWorkSets: boolean,
    ): Promise<WorkoutRecordOperationResult> {
      const generation = dependencies.userGeneration.capture()
      return recordWithGeneration(generation, state, input, beginWorkSets, false)
    },

    async chooseOptionalSetAndMaybeOpenSummary(
      state: WorkoutFlowState,
      choiceGroup: string,
      exerciseIndex: number | null,
    ): Promise<{
      readonly state: WorkoutFlowState
      readonly openSummary: (() => Promise<void>) | null
    }> {
      const generation = dependencies.userGeneration.capture()
      const updated = await awaitCurrent(
        generation,
        () => dependencies.workouts.chooseOptionalSet(state, choiceGroup, exerciseIndex),
      )
      return {
        state: updated,
        openSummary: isWorkoutPrescriptionFinished(updated)
          ? createSummaryNavigation(generation)
          : null,
      }
    },

    async discardCorruptedDraftAndOpenPlan(): Promise<void> {
      const generation = dependencies.userGeneration.capture()
      await awaitCurrent(generation, () => dependencies.workouts.discardCorruptedDraft())
      await awaitCurrent(
        generation,
        () => Promise.resolve(dependencies.navigation.replace('PLAN')),
      )
    },

    async loadAndSettleWorkout(
      completionType?: WorkoutCompletionType,
    ): Promise<WorkoutSummaryWorkflowResult> {
      const generation = dependencies.userGeneration.capture()
      const state = await awaitCurrent(generation, () => dependencies.workouts.load())
      if (!state) return { kind: 'EMPTY' }
      const summary = summarizeWorkout(state)
      const effectiveCompletionType = completionType
        ?? (summary.complete ? 'FULL' : undefined)
      if (!effectiveCompletionType) return { kind: 'LOADED', state, summary }
      return settleLoadedWorkout(generation, state, summary, effectiveCompletionType)
    },

    async discardOrphanedWorkout(): Promise<void> {
      const generation = dependencies.userGeneration.capture()
      const state = await awaitCurrent(generation, () => dependencies.workouts.load())
      if (!state) throw new Error('the orphaned local workout is no longer active')
      await awaitCurrent(
        generation,
        () => dependencies.workouts.discardOrphanedLocalWorkout(state),
      )
      await awaitCurrent(
        generation,
        () => Promise.resolve(dependencies.navigation.replace('PLAN')),
      )
    },

    async loadNextTrainingDay(completedDayCode?: string): Promise<PlanDay | null> {
      const generation = dependencies.userGeneration.capture()
      for (let attempt = 0; attempt < 3; attempt += 1) {
        try {
          const activePlan = await awaitCurrent(
            generation,
            () => dependencies.api.getActivePlan(),
          )
          if (!activePlan) throw new Error('active plan is not visible after workout completion')
          const days = activePlan.activeVersion.plan.days
          const completedIndex = completedDayCode
            ? days.findIndex((day) => day.code === completedDayCode)
            : -1
          const nextCode = completedIndex >= 0
            ? days[(completedIndex + 1) % days.length]?.code ?? ''
            : selectNextTrainingDayCode(
                days,
                (await awaitCurrent(
                  generation,
                  () => dependencies.api.listHistory(undefined, 50),
                )).items,
              )
          return days.find((day) => day.code === nextCode) ?? null
        } catch {
          assertCurrent(generation)
          if (attempt >= 2) return null
          await awaitCurrent(generation, () => delay(150 * (attempt + 1)))
        }
      }
      return null
    },

    async prepareNextTrainingDay(trainingDayCode: string): Promise<void> {
      const generation = dependencies.userGeneration.capture()
      await awaitCurrent(
        generation,
        () => dependencies.nextTrainingDaySelection.remember(trainingDayCode),
      )
      await awaitCurrent(
        generation,
        () => Promise.resolve(dependencies.navigation.replace('WORKOUT_PREPARE', {
          trainingDayCode,
        })),
      )
    },
  }
}
