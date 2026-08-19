export const workoutStartIntentSchemaVersion = 1 as const

export interface WorkoutStartIntent {
  readonly schemaVersion: typeof workoutStartIntentSchemaVersion
  readonly clientSessionKey: string
  readonly planId: string
  readonly planVersionNo: number
  readonly planDayId: string
}

export interface WorkoutStartIntentStore {
  claim(intent: WorkoutStartIntent): Promise<WorkoutStartIntent>
  clear(expectedClientSessionKey: string): Promise<void>
}

export function sameWorkoutStartSource(
  intent: WorkoutStartIntent,
  input: { planId: string; planVersionNo: number; planDayId: string },
): boolean {
  return intent.planId === input.planId
    && intent.planVersionNo === input.planVersionNo
    && intent.planDayId === input.planDayId
}

export class PendingWorkoutStartError extends Error {
  constructor(readonly intent: WorkoutStartIntent) {
    super('a previous workout start must be recovered before starting another training day')
    this.name = 'PendingWorkoutStartError'
  }
}
