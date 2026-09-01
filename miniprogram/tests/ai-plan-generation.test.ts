import { describe, expect, it, vi } from 'vitest'

import {
  createValidatedAiPlanGenerator,
  type AiTextGenerationPort,
} from '../src/application/cloudbaseAi'
import type { PlanGenerationContextData } from '../src/application/models'

const exerciseFacts = [
  ['BODYWEIGHT_SQUAT', 'SQUAT', ['QUADRICEPS', 'GLUTES']],
  ['GLUTE_BRIDGE', 'HINGE', ['GLUTES', 'HAMSTRINGS']],
  ['BENT_KNEE_PUSH_UP', 'HORIZONTAL_PUSH', ['CHEST', 'TRICEPS']],
  ['FLOOR_PRONE_COBRA', 'HORIZONTAL_PULL', ['BACK', 'SHOULDERS']],
  ['DEAD_BUG', 'CORE', ['CORE']],
] as const

const context = {
  profile: {
    experience: 'BEGINNER',
    trainingSplit: 'UPPER_LOWER',
    goal: 'GENERAL_FITNESS',
    weeklyFrequency: 2,
    sessionMinutes: 45,
    location: 'HOME',
    profileVersion: 7,
  },
  exercises: exerciseFacts.map(([code, movementPattern, primaryMuscles], index) => ({
    code,
    name: `不得发送的动作名称 ${index}`,
    movementPattern,
    difficulty: 'BEGINNER',
    equipment: ['BODYWEIGHT'],
    primaryMuscles: [...primaryMuscles],
    preferred: index === 0,
    bodyweight: true,
  })),
  constraints: {
    minimumSessionsPerWeek: 2,
    maximumSessionsPerWeek: 6,
    maximumExercisesPerSession: 5,
    minimumWorkSets: 2,
    maximumWorkSets: 4,
    minimumReps: 5,
    maximumReps: 15,
    minimumRestSeconds: 45,
    maximumRestSeconds: 240,
    secondsPerWorkSet: 45,
    secondsPerExerciseTransition: 75,
    maximumMovementPatternOccurrencesPerSession: 2,
    maximumWorkSetsPerPrimaryMusclePerSession: 12,
    minimumRecoveryHoursBetweenPrimaryMuscleSessions: 48,
  },
  ruleReference: {
    ruleVersion: '1.3.0',
    templateVersion: '1.3.0',
    contentVersion: '1.6.0',
  },
} satisfies PlanGenerationContextData

function selectionOnlyResponse(): string {
  return JSON.stringify({
    name: 'AI 动作编排候选',
    days: [
      {
        code: 'DAY_1',
        name: '训练日 1',
        exerciseCodes: exerciseFacts.slice(0, 4).map(([code]) => code),
      },
      {
        code: 'DAY_2',
        name: '训练日 2',
        exerciseCodes: exerciseFacts.slice(1, 5).map(([code]) => code),
      },
    ],
  })
}

describe('AI plan selection boundary', () => {
  it('sends only approved structured facts and accepts selection-only output after explicit consent', async () => {
    const provider: AiTextGenerationPort = {
      generate: vi.fn().mockResolvedValue(selectionOnlyResponse()),
    }

    const proposal = await createValidatedAiPlanGenerator(provider).generate(context, {
      consentGranted: true,
      repairIssues: [{
        severity: 'ERROR',
        reasonCode: 'SESSION_TARGET_UNDERFILLED',
        fieldPath: '/days/DAY_1/exercises',
        parameters: { rawUserText: '不得发送' },
      }],
    })

    const request = vi.mocked(provider.generate).mock.calls[0][0]
    const facts = JSON.parse(request.factsJson) as Record<string, unknown>
    expect(request).toMatchObject({
      purpose: 'PLAN_GENERATION',
      explicitUserConsent: true,
    })
    expect(facts).toEqual(expect.objectContaining({
      profile: {
        experience: 'BEGINNER',
        trainingSplit: 'UPPER_LOWER',
        goal: 'GENERAL_FITNESS',
        weeklyFrequency: 2,
        sessionMinutes: 45,
        location: 'HOME',
      },
      targetExercisesPerSession: { minimum: 4, maximum: 5 },
      repairIssues: [{
        reasonCode: 'SESSION_TARGET_UNDERFILLED',
        fieldPath: '/days/DAY_1/exercises',
      }],
    }))
    expect(request.factsJson).not.toContain('profileVersion')
    expect(request.factsJson).not.toContain('不得发送的动作名称')
    expect(request.factsJson).not.toContain('rawUserText')
    expect(request.factsJson).not.toContain('additionalRequirements')
    expect(request.factsJson).not.toContain('workSets')
    expect(proposal.days[0].exercises[0]).toEqual({
      exerciseCode: 'BODYWEIGHT_SQUAT',
      workSets: 2,
      repMin: 5,
      repMax: 5,
      restSeconds: 45,
    })
  })

  it('forwards a bounded underfilled selection so the backend can request a targeted repair', async () => {
    const provider: AiTextGenerationPort = {
      generate: vi.fn().mockResolvedValue(JSON.stringify({
        name: '待规则复核候选',
        days: [
          {
            code: 'DAY_1',
            name: '训练日 1',
            exerciseCodes: exerciseFacts.slice(0, 3).map(([code]) => code),
          },
          {
            code: 'DAY_2',
            name: '训练日 2',
            exerciseCodes: exerciseFacts.slice(1, 4).map(([code]) => code),
          },
        ],
      })),
    }

    const proposal = await createValidatedAiPlanGenerator(provider).generate(context, {
      consentGranted: true,
    })

    expect(proposal.days).toHaveLength(2)
    expect(proposal.days.every((day) => day.exercises.length === 3)).toBe(true)
  })

  it('projects the professional five-day split with direct triceps and biceps requirements', async () => {
    const fiveDayContext = {
      ...context,
      profile: {
        ...context.profile,
        weeklyFrequency: 5,
        trainingSplit: 'BODY_PART_FIVE_DAY',
      },
      exercises: [
        ...context.exercises,
        {
          code: 'DUMBBELL_BICEPS_CURL', name: '不得发送的二头动作名称',
          movementPattern: 'ELBOW_FLEXION', difficulty: 'BEGINNER',
          equipment: ['DUMBBELL'], primaryMuscles: ['BICEPS'], preferred: false, bodyweight: false,
        },
        {
          code: 'CABLE_TRICEPS_PUSHDOWN', name: '不得发送的三头动作名称',
          movementPattern: 'ELBOW_EXTENSION', difficulty: 'BEGINNER',
          equipment: ['CABLE'], primaryMuscles: ['TRICEPS'], preferred: false, bodyweight: false,
        },
      ],
    } satisfies PlanGenerationContextData
    const provider: AiTextGenerationPort = {
      generate: vi.fn().mockResolvedValue(JSON.stringify({
        name: '五日专业编排',
        days: Array.from({ length: 5 }, (_, index) => ({
          code: `DAY_${index + 1}`,
          name: `训练日 ${index + 1}`,
          exerciseCodes: exerciseFacts.slice(0, 4).map(([code]) => code),
        })),
      })),
    }

    await createValidatedAiPlanGenerator(provider).generate(fiveDayContext, {
      consentGranted: true,
    })

    const request = vi.mocked(provider.generate).mock.calls[0][0]
    const facts = JSON.parse(request.factsJson) as {
      professionalSessionStructure: Array<Record<string, unknown>>
      professionalWeeklyStructure: Record<string, unknown>
    }
    expect(facts.professionalWeeklyStructure).toEqual({
      requiredMovementPatterns: ['ELBOW_FLEXION', 'ELBOW_EXTENSION'],
    })
    expect(facts.professionalSessionStructure).toEqual([
      expect.objectContaining({
        code: 'DAY_1',
        focus: 'CHEST',
        requiredMovementPatterns: ['HORIZONTAL_PUSH'],
      }),
      expect.objectContaining({
        code: 'DAY_2',
        focus: 'BACK',
        requiredMovementPatterns: ['HORIZONTAL_PULL', 'VERTICAL_PULL'],
      }),
      expect.objectContaining({ code: 'DAY_3', focus: 'LOWER' }),
      expect.objectContaining({ code: 'DAY_4', focus: 'ARMS' }),
      expect.objectContaining({ code: 'DAY_5', focus: 'SHOULDERS' }),
    ])
    expect(request.systemPrompt).toContain('ELBOW_EXTENSION（直接三头）')
    expect(request.systemPrompt).toContain('ELBOW_FLEXION（直接二头）')
    expect(request.systemPrompt).toContain('用直接二头或直接三头替换可选动作')
    expect(request.systemPrompt).toContain('不能在原列表后追加')
    expect(request.systemPrompt).toContain('同一训练日通常不得重复 movementPattern')
    expect(request.systemPrompt).toContain('肩部日不得堆叠两个肩上推举')
  })

  it('projects a balanced three-day full-body week instead of filling every day with arm work', async () => {
    const gymPatterns = [
      ['DUMBBELL_GOBLET_SQUAT', 'SQUAT'],
      ['DUMBBELL_ROMANIAN_DEADLIFT', 'HINGE'],
      ['DUMBBELL_BENCH_PRESS', 'HORIZONTAL_PUSH'],
      ['DUMBBELL_OVERHEAD_PRESS', 'VERTICAL_PUSH'],
      ['ONE_ARM_DUMBBELL_ROW', 'HORIZONTAL_PULL'],
      ['LAT_PULLDOWN', 'VERTICAL_PULL'],
      ['DUMBBELL_BICEPS_CURL', 'ELBOW_FLEXION'],
      ['CABLE_TRICEPS_PUSHDOWN', 'ELBOW_EXTENSION'],
      ['DEAD_BUG', 'CORE'],
    ] as const
    const gymContext = {
      ...context,
      profile: {
        ...context.profile,
        weeklyFrequency: 3,
        trainingSplit: 'FULL_BODY',
        location: 'GYM',
      },
      exercises: gymPatterns.map(([code, movementPattern]) => ({
        code,
        name: '不得发送的动作名称',
        movementPattern,
        difficulty: 'BEGINNER',
        equipment: movementPattern === 'CORE' ? ['BODYWEIGHT'] : ['DUMBBELL'],
        primaryMuscles: [movementPattern],
        preferred: false,
        bodyweight: movementPattern === 'CORE',
      })),
    } satisfies PlanGenerationContextData
    const provider: AiTextGenerationPort = {
      generate: vi.fn().mockResolvedValue(JSON.stringify({
        name: '三日全身计划',
        days: Array.from({ length: 3 }, (_, index) => ({
          code: `DAY_${index + 1}`,
          name: `训练日 ${index + 1}`,
          exerciseCodes: gymPatterns.slice(0, 5).map(([code]) => code),
        })),
      })),
    }

    await createValidatedAiPlanGenerator(provider).generate(gymContext, { consentGranted: true })

    const request = vi.mocked(provider.generate).mock.calls[0][0]
    const facts = JSON.parse(request.factsJson) as {
      profile: { trainingSplit: string }
      professionalSessionStructure: Array<{ focus: string }>
      professionalWeeklyStructure: {
        requiredMovementPatterns: string[]
        movementPatternSessionTargets: Array<{
          movementPattern: string
          minimumSessions: number
          maximumSessions: number
        }>
      }
    }
    expect(facts.profile.trainingSplit).toBe('FULL_BODY')
    expect(facts.professionalSessionStructure).toHaveLength(3)
    expect(facts.professionalSessionStructure.every((session) => session.focus === 'FULL_BODY')).toBe(true)
    expect(facts.professionalWeeklyStructure.requiredMovementPatterns)
      .toEqual(['ELBOW_FLEXION', 'ELBOW_EXTENSION'])
    expect(facts.professionalWeeklyStructure.movementPatternSessionTargets).toEqual(
      expect.arrayContaining([
        { movementPattern: 'HORIZONTAL_PUSH', minimumSessions: 2, maximumSessions: 2 },
        { movementPattern: 'VERTICAL_PUSH', minimumSessions: 1, maximumSessions: 1 },
        { movementPattern: 'ELBOW_FLEXION', minimumSessions: 1, maximumSessions: 2 },
        { movementPattern: 'ELBOW_EXTENSION', minimumSessions: 1, maximumSessions: 2 },
      ]),
    )
    expect(request.systemPrompt).toContain('不要机械地每天都安排')
    expect(request.systemPrompt).toContain('WEEKLY_MOVEMENT_PATTERN_OVERFILLED')
  })

  it('makes zero provider calls without request-scoped explicit consent', async () => {
    const provider: AiTextGenerationPort = {
      generate: vi.fn().mockResolvedValue(selectionOnlyResponse()),
    }

    await expect(createValidatedAiPlanGenerator(provider).generate(context, {
      consentGranted: false,
    })).rejects.toMatchObject({
      category: 'ELIGIBILITY',
      code: 'AI_CONSENT_REQUIRED',
    })
    expect(provider.generate).not.toHaveBeenCalled()
  })

  it.each([
    ['numeric prescription fields', JSON.stringify({
      name: '越权候选',
      days: [{
        code: 'DAY_1',
        name: '训练日 1',
        exerciseCodes: exerciseFacts.slice(0, 4).map(([code]) => code),
        workSets: 9,
      }],
    }), 'CONTRACT'],
    ['unknown exercise code', JSON.stringify({
      name: '未知动作候选',
      days: [
        { code: 'DAY_1', name: '训练日 1', exerciseCodes: ['UNKNOWN', 'GLUTE_BRIDGE', 'BENT_KNEE_PUSH_UP', 'DEAD_BUG'] },
        { code: 'DAY_2', name: '训练日 2', exerciseCodes: exerciseFacts.slice(1, 5).map(([code]) => code) },
      ],
    }), 'CONTRACT'],
    ['unsafe free text', JSON.stringify({
      name: '疼痛康复处方',
      days: [
        { code: 'DAY_1', name: '训练日 1', exerciseCodes: exerciseFacts.slice(0, 4).map(([code]) => code) },
        { code: 'DAY_2', name: '训练日 2', exerciseCodes: exerciseFacts.slice(1, 5).map(([code]) => code) },
      ],
    }), 'UNSAFE_OUTPUT'],
  ])('rejects %s instead of forwarding it to the backend', async (_label, raw, category) => {
    const provider: AiTextGenerationPort = { generate: vi.fn().mockResolvedValue(raw) }

    await expect(createValidatedAiPlanGenerator(provider).generate(context, {
      consentGranted: true,
    })).rejects.toMatchObject({ category })
  })
})
