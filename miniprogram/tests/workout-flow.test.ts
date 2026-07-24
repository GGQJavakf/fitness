import { describe, expect, it } from 'vitest'

import {
  createWorkoutFlow,
  recordWorkoutSet,
  replaceExerciseForSession,
  summarizeWorkout,
} from '../src/application/workoutFlow'

const baseInput = {
  clientSessionKey: 'session-1',
  planVersionId: 'plan-version-7',
  exercises: [{
    snapshotExerciseKey: 'exercise-1',
    exerciseCode: 'goblet-squat',
    name: '高脚杯深蹲',
    targetWorkSets: 2,
    targetReps: 10,
    restSeconds: 60,
  }],
} as const

describe('workout execution state', () => {
  it('keeps missing RIR unknown and excludes warm-up, failed, and skipped sets from volume', () => {
    let state = createWorkoutFlow(baseInput)
    state = recordWorkoutSet(state, {
      clientSetKey: 'warmup-1', exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED',
      actualWeightKg: 10, actualReps: 10,
    })
    state = recordWorkoutSet(state, {
      clientSetKey: 'work-1', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 10,
    })
    state = recordWorkoutSet(state, {
      clientSetKey: 'work-2', exerciseIndex: 0, setType: 'WORK', status: 'FAILED',
      actualWeightKg: 20, actualReps: 4,
    })
    state = recordWorkoutSet(state, {
      clientSetKey: 'work-3', exerciseIndex: 0, setType: 'WORK', status: 'SKIPPED',
    })

    expect(state.exercises[0].sets[1].rir).toBe('UNKNOWN')
    expect(summarizeWorkout(state)).toMatchObject({
      completedWorkSets: 1,
      completedVolumeKg: 200,
      failedSets: 1,
      skippedSets: 1,
      complete: false,
    })
  })

  it('makes a repeated completion click idempotent by client set key', () => {
    const input = {
      clientSetKey: 'work-click', exerciseIndex: 0, setType: 'WORK' as const, status: 'COMPLETED' as const,
      actualWeightKg: 20, actualReps: 10,
    }
    const once = recordWorkoutSet(createWorkoutFlow(baseInput), input)
    const twice = recordWorkoutSet(once, input)

    expect(twice).toBe(once)
    expect(twice.exercises[0].sets).toHaveLength(1)
  })

  it('replaces an exercise only inside the session snapshot', () => {
    const state = createWorkoutFlow(baseInput)
    const replaced = replaceExerciseForSession(state, 0, {
      snapshotExerciseKey: 'exercise-1-replacement',
      exerciseCode: 'box-squat',
      name: '箱式深蹲',
      targetWorkSets: 2,
      targetReps: 10,
      restSeconds: 60,
    })

    expect(replaced.planVersionId).toBe('plan-version-7')
    expect(replaced.exercises[0]).toMatchObject({
      exerciseCode: 'box-squat',
      replacedExerciseCode: 'goblet-squat',
    })
    expect(state.exercises[0].exerciseCode).toBe('goblet-squat')
  })

  it('turns off automatic progression and exposes a safety message when pain is reported', () => {
    const state = recordWorkoutSet(createWorkoutFlow(baseInput), {
      clientSetKey: 'pain-set', exerciseIndex: 0, setType: 'WORK', status: 'FAILED',
      actualWeightKg: 20, actualReps: 2, discomfort: 'PAIN',
    })

    expect(state.automaticProgressionEligible).toBe(false)
    expect(state.safetyNotice).toMatch(/停止训练/)
  })
})
