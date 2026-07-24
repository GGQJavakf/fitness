export interface ExerciseReplacementCandidate {
  readonly id: string
  readonly code: string
  readonly name: string
  readonly movementPattern: string
  readonly difficulty: string
  readonly equipment: readonly string[]
  readonly primaryMuscles: readonly string[]
}

export interface ReplacedWorkoutSession {
  readonly version: number
  readonly exercises: readonly {
    readonly id: string
    readonly exerciseCode: string
    readonly exerciseName: string
    readonly prescription: { readonly workSets: number; readonly repMax: number; readonly restSeconds: number }
  }[]
}

export interface WorkoutReplacementPort {
  listExerciseReplacements(sourceCode: string): Promise<readonly ExerciseReplacementCandidate[]>
  replaceWorkoutExercise(
    sessionId: string, snapshotId: string, replacementCode: string, expectedVersion: number,
  ): Promise<ReplacedWorkoutSession>
}
