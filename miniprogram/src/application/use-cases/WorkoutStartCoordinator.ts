import { WorkoutRecoveryConfirmationRequiredError, WorkoutStartTerminalReplayError } from '../errors'
import type { WorkoutRecoveryAssessment } from '../ports/WorkoutRecoveryPort'
import {
  PendingWorkoutStartError,
  sameWorkoutStartSource,
  workoutStartIntentSchemaVersion,
  type WorkoutStartIntentStore,
} from '../ports/WorkoutStartIntentStore'
import type {
  StartOrResumeWorkoutInput,
  StartOrResumeWorkoutResult,
  WorkoutGenerationFence,
  WorkoutFlowService,
} from './WorkoutFlowService'
import type { WorkoutFlowState } from '../workoutFlow'

export type CoordinatedWorkoutStartInput = StartOrResumeWorkoutInput

export type CoordinatedWorkoutStartResult = StartOrResumeWorkoutResult
  | {
    kind: 'RECOVERY_CONFIRMATION_REQUIRED'
    assessment: WorkoutRecoveryAssessment
    confirmationToken: string
    confirmationExpiresAt: string
  }
  | { kind: 'TERMINAL_REPLAY' }

export type WorkoutStartRuntime = Pick<WorkoutFlowService, 'loadStatus' | 'startOrResume'>
  & Partial<Pick<WorkoutFlowService, 'replaceActiveAndStart'>>
type WorkoutStartSourceInput = Pick<
  CoordinatedWorkoutStartInput,
  'clientSessionKey' | 'planId' | 'planVersionNo' | 'planDayId'
>

/** Keeps server-issued recovery confirmation and duplicate taps coordinated around one start request. */
export class WorkoutStartCoordinator {
  private readonly inFlight = new Map<string, Promise<CoordinatedWorkoutStartResult>>()

  constructor(
    private readonly workouts: WorkoutStartRuntime,
    private readonly intents?: WorkoutStartIntentStore,
    private readonly userGeneration?: WorkoutGenerationFence,
  ) {}

  async cancelUncreatedStart(input: WorkoutStartSourceInput): Promise<void> {
    const generation = this.captureGeneration()
    if (!this.intents) return
    const existing = await this.awaitCurrent(generation, () => this.intents!.claim({
        schemaVersion: workoutStartIntentSchemaVersion,
        clientSessionKey: input.clientSessionKey,
        planId: input.planId,
        planVersionNo: input.planVersionNo,
        planDayId: input.planDayId,
      }))
    if (!sameWorkoutStartSource(existing, input)) throw new PendingWorkoutStartError(existing)
    await this.awaitCurrent(generation, () => this.intents!.clear(existing.clientSessionKey))
  }

  start(input: CoordinatedWorkoutStartInput): Promise<CoordinatedWorkoutStartResult> {
    return this.startWithDurableIntent(input, this.captureGeneration())
  }

  replaceActive(
    state: WorkoutFlowState,
    input: CoordinatedWorkoutStartInput,
  ): Promise<CoordinatedWorkoutStartResult> {
    return this.startWithDurableIntent(input, this.captureGeneration(), state)
  }

  private async startWithDurableIntent(
    input: CoordinatedWorkoutStartInput,
    generation: number | undefined,
    replacementState?: WorkoutFlowState,
  ): Promise<CoordinatedWorkoutStartResult> {
    const effective = await this.awaitCurrent(
      generation,
      () => this.resolveIntent(input, generation),
    )
    const key = [
      generation ?? 'unbound',
      effective.clientSessionKey,
      effective.planId,
      effective.planVersionNo,
      effective.planDayId,
      effective.activeDraftDecision ?? '',
      effective.recoveryConfirmationToken ?? '',
      replacementState?.clientSessionKey ?? '',
    ].join('|')
    const pending = this.inFlight.get(key)
    if (pending) return pending

    const result = this.startOnce(effective, generation, replacementState).then(async (value) => {
      if (value.kind !== 'RECOVERY_CONFIRMATION_REQUIRED') {
        await this.awaitCurrent(
          generation,
          () => this.intents?.clear(effective.clientSessionKey) ?? Promise.resolve(),
        )
      }
      this.assertCurrent(generation)
      return value
    })
    this.inFlight.set(key, result)
    result.then(
      () => this.clearIfCurrent(key, result),
      () => this.clearIfCurrent(key, result),
    )
    return result
  }

  private async resolveIntent(
    input: CoordinatedWorkoutStartInput,
    generation: number | undefined,
  ): Promise<CoordinatedWorkoutStartInput> {
    if (!this.intents) return input
    const existing = await this.awaitCurrent(generation, () => this.intents!.claim({
        schemaVersion: workoutStartIntentSchemaVersion,
        clientSessionKey: input.clientSessionKey,
        planId: input.planId,
        planVersionNo: input.planVersionNo,
        planDayId: input.planDayId,
      }))
    if (!sameWorkoutStartSource(existing, input)) throw new PendingWorkoutStartError(existing)
    return { ...input, clientSessionKey: existing.clientSessionKey }
  }

  private async startOnce(
    input: CoordinatedWorkoutStartInput,
    generation: number | undefined,
    replacementState?: WorkoutFlowState,
  ): Promise<CoordinatedWorkoutStartResult> {
    if (replacementState) {
      if (!this.workouts.replaceActiveAndStart) {
        throw new Error('authoritative workout replacement is unavailable')
      }
      try {
        return await this.awaitCurrent(
          generation,
          () => this.workouts.replaceActiveAndStart!(replacementState, input),
        )
      } catch (error) {
        return this.mapStartError(error, generation)
      }
    }
    const status = await this.awaitCurrent(generation, () => this.workouts.loadStatus())
    if (status.kind !== 'NONE') {
      return this.awaitCurrent(generation, () => this.workouts.startOrResume(input))
    }
    try {
      return await this.awaitCurrent(generation, () => this.workouts.startOrResume(input))
    } catch (error) {
      this.assertCurrent(generation)
      return this.mapStartError(error, generation)
    }
  }

  private mapStartError(
    error: unknown,
    generation: number | undefined,
  ): CoordinatedWorkoutStartResult {
    this.assertCurrent(generation)
    if (error instanceof WorkoutRecoveryConfirmationRequiredError) {
      return {
        kind: 'RECOVERY_CONFIRMATION_REQUIRED',
        assessment: error.assessment,
        confirmationToken: error.confirmationToken,
        confirmationExpiresAt: error.confirmationExpiresAt,
      }
    }
    if (error instanceof WorkoutStartTerminalReplayError) {
      return { kind: 'TERMINAL_REPLAY' }
    }
    throw error
  }

  private clearIfCurrent(
    key: string,
    result: Promise<CoordinatedWorkoutStartResult>,
  ): void {
    if (this.inFlight.get(key) === result) this.inFlight.delete(key)
  }

  private captureGeneration(): number | undefined {
    return this.userGeneration?.capture()
  }

  private assertCurrent(generation: number | undefined): void {
    if (generation !== undefined) this.userGeneration?.assertCurrent(generation)
  }

  private async awaitCurrent<T>(
    generation: number | undefined,
    operation: () => Promise<T>,
  ): Promise<T> {
    this.assertCurrent(generation)
    try {
      const value = await operation()
      this.assertCurrent(generation)
      return value
    } catch (error) {
      this.assertCurrent(generation)
      throw error
    }
  }
}
