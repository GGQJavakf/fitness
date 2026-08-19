import type { WeightStatus } from '../models'
import type { WorkoutWarmupPrescriptionSnapshot } from '../workoutFlow'

export interface StartWorkoutSessionRequest {
  clientSessionKey: string
  planId: string
  planVersionNo: number
  planDayId: string
  recoveryConfirmationToken?: string
}

export interface StartedWorkoutSession {
  id: string
  planVersionId: string
  version: number
  warmupPrescription?: WorkoutWarmupPrescriptionSnapshot
  exercises: readonly {
    id: string
    exerciseCode: string
    exerciseName: string
    prescription: {
      workSets: number
      repMax: number
      restSeconds: number
      weightStatus: WeightStatus
      targetWeightKg?: number
    }
  }[]
}

export interface RecoverableWorkoutSet {
  setId: string
  sessionExerciseId: string
  clientSetKey: string
  clientOperationSeq: number
  setType: 'WARMUP' | 'WORK' | 'EXTRA'
  setOrder: number
  target: { weight: { value: number; unit: 'KG' }; reps: number }
  actual: { weight: { value: number; unit: 'KG' }; reps: number }
  remainingReps?: number
  completionStatus: 'COMPLETED' | 'FAILED' | 'SKIPPED'
  completedAt?: string
  serverRevision: number
  sessionVersion: number
  safetyFlag?: 'PAIN' | 'INJURY' | 'CHEST_DISCOMFORT' | 'DIZZINESS' | 'SEVERE_UNWELL'
  anomalyStatus?: 'CONFIRMED_EXCLUDED'
  syncStatus: 'APPLIED'
}

export interface RecoverableActiveWorkout {
  session: StartedWorkoutSession & {
    clientSessionKey: string
    status: 'CREATED' | 'IN_PROGRESS' | 'PAUSED' | 'COMPLETING'
  }
  sets: readonly RecoverableWorkoutSet[]
}

export interface WorkoutSessionStartPort {
  startWorkoutSession(request: StartWorkoutSessionRequest): Promise<StartedWorkoutSession>
  activateWorkoutSession?(
    sessionId: string,
    expectedVersion: number,
  ): Promise<StartedWorkoutSession>
}
