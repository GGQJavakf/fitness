export interface ExerciseContent {
  readonly id: string
  readonly code: string
  readonly name: string
  readonly plainLanguage: string
  readonly movementPattern: string
  readonly difficulty: 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
  readonly equipment: readonly string[]
  readonly primaryMuscles: readonly string[]
  readonly instructions: readonly string[]
  readonly safetyCues: readonly string[]
  readonly image: {
    readonly primaryRef: string
    readonly fallbackRef: string
  }
  readonly alternatives: readonly {
    readonly exerciseCode: string
    readonly rank: number
  }[]
  readonly contentVersion: string
}

export interface ExercisePreferenceProfile {
  readonly items: readonly {
    readonly exerciseId: string
    readonly preferenceType: 'PREFERRED' | 'EXCLUDED'
  }[]
  readonly version: number
}
