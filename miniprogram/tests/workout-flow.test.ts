import { describe, expect, it } from 'vitest'

import {
  createWorkoutFlow,
  beginWorkSets,
  completeGeneralWarmup,
  completedRampSets,
  isWorkoutPrescriptionFinished,
  recordWorkoutSet,
  markWorkoutSyncPending,
  remainingRampWarmupSets,
  replaceExerciseForSession,
  setWorkoutExerciseWeight,
  summarizeWorkout,
} from '../src/application/workoutFlow'

const authoritativeWarmup = {
  schemaVersion: 'workout-warmup-prescription-v1',
  ruleVersion: '1.3.0',
  generalWarmup: { occurrences: 1, durationSeconds: 180 },
  rampWarmup: {
    exerciseId: 'exercise-1',
    exerciseOrder: 1,
    status: 'READY',
    sets: [{ weightKg: 10, reps: 10 }, { weightKg: 12.5, reps: 6 }],
  },
  countsTowardTrainingVolume: false,
  countsTowardProgression: false,
} as const

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
    const general = createWorkoutFlow({ ...baseInput, warmupPrescription: authoritativeWarmup })
    expect(general.warmup).toMatchObject({
      phase: 'GENERAL', generalDurationSeconds: 180, maximumRampSets: 2,
      prescriptionVersion: 'workout-warmup-prescription-v1', ruleVersion: '1.3.0',
    })

    let ramp = completeGeneralWarmup(general)
    ramp = recordWorkoutSet(ramp, {
      clientSetKey: 'ramp-1', exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED',
      actualWeightKg: 10, actualReps: 8,
    })

    expect(completedRampSets(ramp)).toBe(1)
    expect(ramp.currentSetIndex).toBe(0)
    expect(beginWorkSets(ramp).warmup.phase).toBe('WORK')
  })

  it('moves bodyweight exercises directly from general warmup to work sets', () => {
    const general = createWorkoutFlow({
      ...baseInput,
      exercises: baseInput.exercises.map((exercise, index) => index === 0
        ? { ...exercise, weightStatus: 'BODYWEIGHT' as const }
        : exercise),
    })

    expect(completeGeneralWarmup(general).warmup.phase).toBe('WORK')
  })

  it('does not infer a ramp exercise when the server prescription is absent', () => {
    const general = createWorkoutFlow(baseInput)

    expect(general.warmup).toMatchObject({
      prescriptionVersion: 'legacy-client-v1',
      rampExerciseIndex: null,
      rampStatus: 'NOT_REQUIRED',
      rampSets: [],
    })
    expect(completeGeneralWarmup(general).warmup.phase).toBe('WORK')
  })

  it('rejects set records that bypass the warmup state machine', () => {
    const general = createWorkoutFlow(baseInput)
    expect(() => beginWorkSets(general)).toThrow(/general warmup/i)
    expect(() => recordWorkoutSet(general, {
      clientSetKey: 'work-too-early', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 10,
    })).toThrow(/after warmup/i)
  })

  it('renders immutable server ramp sets and never recalculates weights in the client', () => {
    let state = completeGeneralWarmup(createWorkoutFlow({
      ...baseInput,
      exercises: [{ ...baseInput.exercises[0], weightStatus: 'KNOWN', targetWeightKg: 20 }],
      warmupPrescription: authoritativeWarmup,
    }))
    expect(remainingRampWarmupSets(state)).toEqual([
      { weightKg: 10, reps: 10 },
      { weightKg: 12.5, reps: 6 },
    ])
    state = recordWorkoutSet(state, {
      clientSetKey: 'server-ramp-1', exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED',
      actualWeightKg: 10, actualReps: 10,
    })
    expect(remainingRampWarmupSets(state)).toEqual([{ weightKg: 12.5, reps: 6 }])
    const lowered = setWorkoutExerciseWeight(state, 0, 11)
    expect(remainingRampWarmupSets(lowered)).toEqual([])
  })

  it('enforces the deterministic two-set ramp warmup ceiling', () => {
    let state = completeGeneralWarmup(createWorkoutFlow({ ...baseInput, warmupPrescription: authoritativeWarmup }))
    for (let order = 1; order <= 2; order += 1) {
      state = recordWorkoutSet(state, {
        clientSetKey: `ramp-${order}`, exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED',
        actualWeightKg: order * 5, actualReps: 8,
      })
    }
    expect(() => recordWorkoutSet(state, {
      clientSetKey: 'ramp-3', exerciseIndex: 0, setType: 'WARMUP', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 6,
    })).toThrow(/maximum ramp warmup sets/i)
  })

  it('keeps one confirmed formal weight on the exercise and reuses it for work sets', () => {
    const planned = createWorkoutFlow({
      ...baseInput,
      exercises: [{ ...baseInput.exercises[0], weightStatus: 'KNOWN' as const, targetWeightKg: 20 }],
    })
    expect(planned.exercises[0].sessionWeightKg).toBe(20)

    const calibrated = setWorkoutExerciseWeight(createWorkoutFlow(baseInput), 0, 17.5)
    const work = beginWorkSets(completeGeneralWarmup(calibrated))
    const recorded = recordWorkoutSet(work, {
      clientSetKey: 'work-uses-session-weight',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualReps: 10,
    })

    expect(recorded.exercises[0].sessionWeightKg).toBe(17.5)
    expect(recorded.exercises[0].sets[0].actualWeightKg).toBe(17.5)
  })

  it('preserves pending and conflict evidence when the session weight is adjusted', () => {
    const pending = markWorkoutSyncPending(createWorkoutFlow(baseInput))
    const conflict = { ...createWorkoutFlow(baseInput), syncStatus: 'CONFLICT' as const }

    expect(setWorkoutExerciseWeight(pending, 0, 15).syncStatus).toBe('OFFLINE_PENDING')
    expect(setWorkoutExerciseWeight(conflict, 0, 15).syncStatus).toBe('CONFLICT')
  })

  it('rejects external-load work attempts without a known or explicit weight', () => {
    const work = createWorkFlow()

    expect(() => recordWorkoutSet(work, {
      clientSetKey: 'failed-without-weight',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'FAILED',
      actualReps: 4,
    })).toThrow(/formal weight/i)
  })

  it('keeps missing RIR unknown and excludes warm-up, failed, and skipped sets from volume', () => {
    let state = completeGeneralWarmup(createWorkoutFlow({
      ...baseInput,
      exercises: [{ ...baseInput.exercises[0], targetWorkSets: 3 }],
      warmupPrescription: authoritativeWarmup,
    }))
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
      completedReps: 10,
      usesExternalLoad: true,
      failedSets: 1,
      skippedSets: 1,
      complete: false,
    })
  })

  it('summarizes bodyweight work with repetitions instead of a zero-weight volume', () => {
    let state = completeGeneralWarmup(createWorkoutFlow({
      ...baseInput,
      exercises: [{
        ...baseInput.exercises[0],
        weightStatus: 'BODYWEIGHT',
        targetWorkSets: 1,
      }],
    }))
    state = beginWorkSets(state)
    state = recordWorkoutSet(state, {
      clientSetKey: 'bodyweight-work-1',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 0,
      actualReps: 12,
    })

    expect(summarizeWorkout(state)).toMatchObject({
      completedWorkSets: 1,
      completedVolumeKg: 0,
      completedReps: 12,
      usesExternalLoad: false,
    })
  })

  it('uses repetitions while an external-load exercise is still awaiting weight calibration', () => {
    let state = beginWorkSets(completeGeneralWarmup(createWorkoutFlow({
      ...baseInput,
      exercises: [{
        ...baseInput.exercises[0],
        weightStatus: 'NEEDS_CALIBRATION',
        targetWorkSets: 1,
      }],
    })))
    state = recordWorkoutSet(state, {
      clientSetKey: 'calibration-work-1',
      exerciseIndex: 0,
      setType: 'WORK',
      status: 'COMPLETED',
      actualWeightKg: 0,
      actualReps: 10,
    })

    expect(summarizeWorkout(state)).toMatchObject({
      completedVolumeKg: 0,
      completedReps: 10,
      usesExternalLoad: false,
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

  it('detects when every prescribed work set has been recorded', () => {
    const oneSet = recordWorkoutSet(createWorkFlow(), {
      clientSetKey: 'work-1', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 10,
    })
    const finished = recordWorkoutSet(oneSet, {
      clientSetKey: 'work-2', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 10,
    })

    expect(isWorkoutPrescriptionFinished(oneSet)).toBe(false)
    expect(isWorkoutPrescriptionFinished(finished)).toBe(true)
  })

  it('rejects additional work facts after the prescription is complete', () => {
    const first = recordWorkoutSet(createWorkFlow(), {
      clientSetKey: 'work-1', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 10,
    })
    const finished = recordWorkoutSet(first, {
      clientSetKey: 'work-2', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 10,
    })

    expect(() => recordWorkoutSet(finished, {
      clientSetKey: 'work-3', exerciseIndex: 0, setType: 'WORK', status: 'COMPLETED',
      actualWeightKg: 20, actualReps: 10,
    })).toThrow(/already complete/i)
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

  it('clears a known external weight when replacement load mode is bodyweight', () => {
    const state = createWorkoutFlow({
      ...baseInput,
      exercises: [{ ...baseInput.exercises[0], weightStatus: 'KNOWN', targetWeightKg: 20 }],
    })

    const replaced = replaceExerciseForSession(state, 0, {
      ...baseInput.exercises[0],
      exerciseCode: 'bodyweight-squat',
      name: '徒手深蹲',
      weightStatus: 'BODYWEIGHT',
    })

    expect(replaced.exercises[0]).toMatchObject({ weightStatus: 'BODYWEIGHT' })
    expect(replaced.exercises[0].targetWeightKg).toBeUndefined()
    expect(replaced.exercises[0].sessionWeightKg).toBeUndefined()
  })

  it('requires calibration when a bodyweight exercise is replaced by an external load', () => {
    const state = createWorkoutFlow({
      ...baseInput,
      exercises: [{ ...baseInput.exercises[0], weightStatus: 'BODYWEIGHT' }],
    })

    const replaced = replaceExerciseForSession(state, 0, {
      ...baseInput.exercises[0],
      exerciseCode: 'goblet-squat',
      name: '高脚杯深蹲',
      weightStatus: 'NEEDS_CALIBRATION',
    })

    expect(replaced.exercises[0]).toMatchObject({ weightStatus: 'NEEDS_CALIBRATION' })
    expect(replaced.exercises[0].targetWeightKg).toBeUndefined()
    expect(replaced.exercises[0].sessionWeightKg).toBeUndefined()
  })

  it('turns off automatic progression and exposes a safety message when pain is reported', () => {
    const state = recordWorkoutSet(createWorkFlow(), {
      clientSetKey: 'pain-set', exerciseIndex: 0, setType: 'WORK', status: 'FAILED',
      actualWeightKg: 20, actualReps: 2, discomfort: 'PAIN',
    })

    expect(state.automaticProgressionEligible).toBe(false)
    expect(state.safetyNotice).toMatch(/停止训练/)
  })

  it('keeps legacy generic discomfort non-medical instead of guessing pain', () => {
    const state = recordWorkoutSet(createWorkFlow(), {
      clientSetKey: 'generic-discomfort-set', exerciseIndex: 0, setType: 'WORK', status: 'FAILED',
      actualWeightKg: 20, actualReps: 2, discomfort: 'DISCOMFORT',
    })

    expect(state.exercises[0].sets[0]).toMatchObject({
      discomfort: 'DISCOMFORT',
      safetyFlag: null,
    })
    expect(state.automaticProgressionEligible).toBe(false)
    expect(state.safetyNotice).toMatch(/明显不适/)
  })
})
