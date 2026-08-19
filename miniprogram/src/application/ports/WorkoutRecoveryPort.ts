export interface WorkoutRecoveryCheckRequest {
  planId: string
  planVersionNo: number
  trainingDayCode: string
}

export interface AffectedMuscleRecovery {
  muscleGroup: string
  elapsedHours: number
  minimumRecoveryHours: number
  lastCompletedAt: string
}

export interface WorkoutRecoveryAssessment {
  decision: 'READY' | 'CONFIRMATION_REQUIRED'
  policyVersion: string
  checkedAt: string
  minimumRecoveryHours: number
  affectedMuscles: readonly AffectedMuscleRecovery[]
}

export interface WorkoutRecoveryPort {
  checkWorkoutRecovery(request: WorkoutRecoveryCheckRequest): Promise<WorkoutRecoveryAssessment>
}
