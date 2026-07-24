export interface SyncWorkoutOperation {
  clientOperationSeq: number
  operationType: 'UPSERT_SET'
  clientKey: string
  payload: Readonly<Record<string, unknown>>
}

export interface SyncWorkoutOperationResult {
  clientOperationSeq: number
  status: 'APPLIED' | 'DUPLICATE' | 'CONFLICT' | 'REJECTED'
  conflictId?: string
  reasonCode?: string
}

export interface WorkoutOperationSyncPort {
  syncWorkoutOperations(operations: readonly SyncWorkoutOperation[]): Promise<readonly SyncWorkoutOperationResult[]>
}
