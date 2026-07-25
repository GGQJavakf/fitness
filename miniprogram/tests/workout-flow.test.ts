import { describe, expect, it } from 'vitest'

import {
  createWorkoutFlow,
  beginWorkSets,
  completeGeneralWarmup,
  completedRampSets,
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

function createWorkFlow() {
  return beginWorkSets(completeGeneralWarmup(createWorkoutFlow(baseInput)))
}

describe('workout execution state', () => {
  it('moves explicitly through general warmup, ramp sets, and work sets', () => {
    const general = createWorkoutFlow({ ...baseInput, warmupDurationSeconds: 300 })
    expect(general.warmup).toMatchObject({ phase: 'GENERAL', generalDurationSeconds: 300, maximumRampSets: 3 })

    let ramp = completeGeneralWarmup(general)
    ramp = recordWorkoutSet(ramp, {
      clientSetKey: 'ramp-1', exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED',
      actualWeightKg: 10, actualReps: 8,
    })

    expect(completedRampSets(ramp)).toBe(1)
    expect(ramp.currentSetIndex).toBe(0)
    expect(beginWorkSets(ramp).warmup.phase).toBe('WORK')
  })

  it('rejects set records that bypass the warmup state machine', () => {
    const general = createWorkoutFlow(baseInput)
    expect(() => beginWorkSets(general)).toThrow(/general warmup/i)
    expect(() => recordWorkoutSet(general, {
      clientSetKey: 'work-too-early', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 10,
    })).toThrow(/after warmup/i)
  })

  it('enforces the deterministic three-set ramp warmup ceiling', () => {
    let state = completeGeneralWarmup(createWorkoutFlow(baseInput))
    for (let order = 1; order <= 3; order += 1) {
      state = recordWorkoutSet(state, {
        clientSetKey: `ramp-${order}`, exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED',
        actualWeightKg: order * 5, actualReps: 8,
      })
    }
    expect(() => recordWorkoutSet(state, {
      clientSetKey: 'ramp-4', exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 6,
    })).toThrow(/maximum ramp warmup sets/i)
  })

  it('keeps missing RIR unknown and excludes warm-up, failed, and skipped sets from volume', () => {
    let state = completeGeneralWarmup(createWorkoutFlow(baseInput))
    state = recordWorkoutSet(state, {
      clientSetKey: 'warmup-1', exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED',
      actualWeightKg: 10, actualReps: 10,
    })
    state = beginWorkSets(state)
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
    const once = recordWorkoutSet(createWorkFlow(), input)
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
    const state = recordWorkoutSet(createWorkFlow(), {
      clientSetKey: 'pain-set', exerciseIndex: 0, setType: 'WORK', status: 'FAILED',
      actualWeightKg: 20, actualReps: 2, discomfort: 'PAIN',
    })

    expect(state.automaticProgressionEligible).toBe(false)
    expect(state.safetyNotice).toMatch(/停止训练/)
  })
})
