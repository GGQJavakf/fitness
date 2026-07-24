import { describe, expect, it, vi } from 'vitest'

import type { PlanDraft } from '../src/application/models'
import {
  addPlanExercise,
  changeNumericField,
  createPlanEditorState,
  movePlanExercise,
  removePlanExercise,
  replacePlanExercise,
} from '../src/application/planEditor'
import { FitnessApiClient } from '../src/infrastructure/api/client'

const plan: PlanDraft = {
  templateCode: 'FULL_BODY_3D',
  name: '全身训练',
  locks: {
    '/days/D1/exercises/SQUAT/workSets': 'USER_LOCKED',
  },
  days: [{
    code: 'D1',
    name: '训练日 1',
    exercises: [
      { exerciseCode: 'SQUAT', workSets: 3, repMin: 8, repMax: 12, restSeconds: 90, weightStatus: 'NEEDS_CALIBRATION' },
      { exerciseCode: 'ROW', workSets: 3, repMin: 8, repMax: 12, restSeconds: 90, weightStatus: 'KNOWN', targetWeightKg: 20 },
    ],
  }],
}

function state(source: PlanDraft = plan) {
  return createPlanEditorState({
    planId: 'plan-1',
    baseVersion: 1,
    plan: source,
    validationResult: { valid: true, validationIssues: [] },
  })
}

describe('plan structure editing', () => {
  it('loads server-prescribed options through the authenticated versioned API', async () => {
    const request = vi.fn().mockResolvedValue({
      statusCode: 200,
      data: {
        data: { items: [{
          exerciseCode: 'PRESS', name: '哑铃卧推', workSets: 3,
          repMin: 8, repMax: 12, restSeconds: 90, weightStatus: 'NEEDS_CALIBRATION',
        }] },
        meta: { requestId: 'request-1', serverTime: '2026-07-25T00:00:00Z' },
      },
    })
    const client = new FitnessApiClient(
      'http://127.0.0.1:8080',
      { request },
      { load: vi.fn().mockResolvedValue({ accessToken: 'test-token' }), save: vi.fn(), clear: vi.fn() },
    )

    await expect(client.listExerciseOptions('plan / 1', 'DAY A')).resolves.toHaveLength(1)
    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://127.0.0.1:8080/api/v1/plans/plan%20%2F%201/exercise-options?dayCode=DAY%20A',
      method: 'GET',
      headers: expect.objectContaining({ Authorization: 'Bearer test-token' }),
    }))
  })

  it('adds only a complete server-prescribed option and rejects duplicates', () => {
    const option = {
      exerciseCode: 'PRESS',
      name: '哑铃卧推',
      workSets: 3,
      repMin: 8,
      repMax: 12,
      restSeconds: 90,
      weightStatus: 'NEEDS_CALIBRATION' as const,
    }

    const added = addPlanExercise(state(), 'D1', option)

    expect(added.workingCopy.days[0].exercises.at(-1)).toEqual({
      exerciseCode: 'PRESS', workSets: 3, repMin: 8, repMax: 12,
      restSeconds: 90, weightStatus: 'NEEDS_CALIBRATION',
    })
    expect(() => addPlanExercise(added, 'D1', option)).toThrow('动作已在当前训练日中')
  })

  it('removes an exercise and explicitly unlocks its user locks', () => {
    const removed = removePlanExercise(state(), 'D1', 'SQUAT')

    expect(removed.workingCopy.days[0].exercises.map((item) => item.exerciseCode)).toEqual(['ROW'])
    expect(removed.lockCommands).toEqual({
      '/days/D1/exercises/SQUAT/workSets': 'UNLOCKED',
    })
  })

  it('never removes or replaces the last exercise or a rule-locked exercise', () => {
    const single = {
      ...plan,
      days: [{ ...plan.days[0], exercises: [plan.days[0].exercises[0]] }],
      locks: {},
    }
    expect(() => removePlanExercise(state(single), 'D1', 'SQUAT')).toThrow('每个训练日至少保留一个动作')

    const locked = {
      ...plan,
      locks: { '/days/D1/exercises/SQUAT/restSeconds': 'RULE_LOCKED' as const },
    }
    expect(() => replacePlanExercise(state(locked), 'D1', 'SQUAT', {
      exerciseCode: 'PRESS', workSets: 3, repMin: 8, repMax: 12,
      restSeconds: 90, weightStatus: 'NEEDS_CALIBRATION',
    })).toThrow('规则锁定动作不能替换')
  })

  it('replaces without carrying a potentially unsafe calibrated weight', () => {
    const replaced = replacePlanExercise(state(), 'D1', 'ROW', {
      exerciseCode: 'CABLE_ROW', workSets: 3, repMin: 10, repMax: 12,
      restSeconds: 75, weightStatus: 'NEEDS_CALIBRATION',
    })

    expect(replaced.workingCopy.days[0].exercises[1]).toEqual({
      exerciseCode: 'CABLE_ROW', workSets: 3, repMin: 10, repMax: 12,
      restSeconds: 75, weightStatus: 'NEEDS_CALIBRATION',
    })
  })

  it('moves actions without changing stable lock paths', () => {
    const moved = movePlanExercise(state(), 'D1', 'ROW', -1)

    expect(moved.workingCopy.days[0].exercises.map((item) => item.exerciseCode)).toEqual(['ROW', 'SQUAT'])
    expect(moved.locks).toEqual(plan.locks)
  })

  it('supports explicit KG calibration while retaining integer-only prescription fields', () => {
    const calibrated = changeNumericField(state(), 'D1', 'SQUAT', 'targetWeightKg', 17.5)
    expect(calibrated.workingCopy.days[0].exercises[0]).toMatchObject({
      targetWeightKg: 17.5,
      weightStatus: 'KNOWN',
    })
    expect(calibrated.locks['/days/D1/exercises/SQUAT/targetWeightKg']).toBe('USER_LOCKED')

    const rejected = changeNumericField(state(), 'D1', 'SQUAT', 'workSets', 3.5)
    expect(rejected).toEqual(state())
  })
})
