export type WorkoutConflictResolution = 'KEEP_LOCAL' | 'KEEP_SERVER' | 'KEEP_BOTH'

export interface WorkoutConflictResolutionIntent {
  conflictId: string
  clientKey: string
  resolution: WorkoutConflictResolution
  expectedConflictVersion: number
}

/**
 * Stable client contract required to converge a local operation after conflict resolution.
 * authoritativePayload is always present so the queue can converge without guessing.
 */
export interface WorkoutConflictResolutionResult {
  conflictId: string
  clientOperationSeq: number
  clientKey: string
  resolution: WorkoutConflictResolution
  outcome: 'ACKNOWLEDGED' | 'REBUILT' | 'ABANDONED'
  authoritativeSessionVersion: number
  authoritativePayload: Readonly<Record<string, unknown>>
  rebuiltPayload?: Readonly<Record<string, unknown>>
}

export interface WorkoutConflictResolutionPort {
  resolveWorkoutConflict(
    conflictId: string,
    resolution: WorkoutConflictResolution,
    expectedConflictVersion: number,
  ): Promise<WorkoutConflictResolutionResult>
}
