export type WorkoutCompletionType = 'FULL' | 'EARLY_END'

export interface WorkoutCompletionResult {
  readonly session: { readonly id: string; readonly status: 'COMPLETED' | 'ABORTED'; readonly version: number }
  readonly completedWorkSets: number
  readonly complete: boolean
  readonly automaticProgressionEligible: boolean
}

export interface WorkoutCompletionPort {
  completeWorkout(
    sessionId: string,
    request: { readonly expectedVersion: number; readonly completionType: WorkoutCompletionType },
    idempotencyKey: string,
  ): Promise<WorkoutCompletionResult>
}
