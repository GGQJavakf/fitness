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
  WorkoutFlowService,
} from './WorkoutFlowService'

export type CoordinatedWorkoutStartInput = StartOrResumeWorkoutInput

export type CoordinatedWorkoutStartResult = StartOrResumeWorkoutResult
  | {
    kind: 'RECOVERY_CONFIRMATION_REQUIRED'
    assessment: WorkoutRecoveryAssessment
    confirmationToken: string
    confirmationExpiresAt: string
  }
  | { kind: 'TERMINAL_REPLAY' }

type WorkoutStartRuntime = Pick<WorkoutFlowService, 'loadStatus' | 'startOrResume'>
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
  ) {}

  async cancelUncreatedStart(input: WorkoutStartSourceInput): Promise<void> {
    if (!this.intents) return
    const existing = await this.intents.claim({
      schemaVersion: workoutStartIntentSchemaVersion,
      clientSessionKey: input.clientSessionKey,
      planId: input.planId,
      planVersionNo: input.planVersionNo,
      planDayId: input.planDayId,
    })
    if (!sameWorkoutStartSource(existing, input)) throw new PendingWorkoutStartError(existing)
    await this.intents.clear(existing.clientSessionKey)
  }

  start(input: CoordinatedWorkoutStartInput): Promise<CoordinatedWorkoutStartResult> {
    return this.startWithDurableIntent(input)
  }

  private async startWithDurableIntent(
    input: CoordinatedWorkoutStartInput,
  ): Promise<CoordinatedWorkoutStartResult> {
    const effective = await this.resolveIntent(input)
    const key = [
      effective.clientSessionKey,
      effective.planId,
      effective.planVersionNo,
      effective.planDayId,
      effective.activeDraftDecision ?? '',
      effective.recoveryConfirmationToken ?? '',
    ].join('|')
    const pending = this.inFlight.get(key)
    if (pending) return pending

    const result = this.startOnce(effective).then(async (value) => {
      if (value.kind !== 'RECOVERY_CONFIRMATION_REQUIRED') {
        await this.intents?.clear(effective.clientSessionKey)
      }
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
  ): Promise<CoordinatedWorkoutStartInput> {
    if (!this.intents) return input
    const existing = await this.intents.claim({
      schemaVersion: workoutStartIntentSchemaVersion,
      clientSessionKey: input.clientSessionKey,
      planId: input.planId,
      planVersionNo: input.planVersionNo,
      planDayId: input.planDayId,
    })
    if (!sameWorkoutStartSource(existing, input)) throw new PendingWorkoutStartError(existing)
    return { ...input, clientSessionKey: existing.clientSessionKey }
  }

  private async startOnce(
    input: CoordinatedWorkoutStartInput,
  ): Promise<CoordinatedWorkoutStartResult> {
    const status = await this.workouts.loadStatus()
    if (status.kind !== 'NONE') return this.workouts.startOrResume(input)
    try {
      return await this.workouts.startOrResume(input)
    } catch (error) {
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
  }

  private clearIfCurrent(
    key: string,
    result: Promise<CoordinatedWorkoutStartResult>,
  ): void {
    if (this.inFlight.get(key) === result) this.inFlight.delete(key)
  }
}
