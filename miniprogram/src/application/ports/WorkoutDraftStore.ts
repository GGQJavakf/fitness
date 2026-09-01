import type { OperationQueue } from '../../domain/sync/OperationQueue'

export const workoutDraftSchemaVersion = 1 as const

export interface WorkoutDraft {
  schemaVersion: typeof workoutDraftSchemaVersion
  draftId: string
  revision: number
  clientSessionKey: string
  sessionId: string | null
  planSnapshot: Readonly<Record<string, unknown>>
  currentExerciseIndex: number
  currentSetIndex: number
  setRecords: readonly Readonly<Record<string, unknown>>[]
  restTimer: Readonly<Record<string, unknown>> | null
  queue: OperationQueue
  lastServerVersion: number
  updatedAtUtc: string
}

export interface WorkoutDraftStore {
  loadActive(): Promise<WorkoutDraft | null>
  save(draft: WorkoutDraft, expectedRevision?: number | null): Promise<void>
  /** Atomically switches the active pointer from the expected draft to a fully persisted replacement. */
  replaceActive?(expectedDraftId: string, replacement: WorkoutDraft): Promise<void>
  clearActive(expectedDraftId: string): Promise<void>
  discardCorrupted?(): Promise<void>
}

export class WorkoutDraftCorruptedError extends Error {
  constructor(message = 'workout draft integrity check failed') {
    super(message)
    this.name = 'WorkoutDraftCorruptedError'
  }
}

export class WorkoutDraftStorageFullError extends Error {
  constructor() {
    super('workout draft storage is full; the active draft was preserved')
    this.name = 'WorkoutDraftStorageFullError'
  }
}

export class WorkoutDraftRecoveryRequiredError extends Error {
  constructor(message = 'workout draft recovery is required') {
    super(message)
    this.name = 'WorkoutDraftRecoveryRequiredError'
  }
}

export class WorkoutDraftRevisionConflictError extends Error {
  constructor() {
    super('workout draft revision changed before save')
    this.name = 'WorkoutDraftRevisionConflictError'
  }
}
