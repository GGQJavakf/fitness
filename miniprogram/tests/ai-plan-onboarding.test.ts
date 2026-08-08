import { describe, expect, it, vi } from 'vitest'

import {
  AiPlanUnavailableError,
  type AiPlanGenerator,
} from '../src/application/cloudbaseAi'
import {
  saveProfileAndGenerateCandidate,
  type OnboardingDraft,
  type OnboardingPersistencePort,
} from '../src/application/onboarding'
import type {
  AiPlanProposal,
  PlanCandidateGenerationData,
  PlanGenerationContextData,
} from '../src/application/models'

const draft: OnboardingDraft = {
  adultConfirmed: true,
  safetyAccepted: true,
  goal: 'GENERAL_FITNESS',
  experience: 'BEGINNER',
  weeklyFrequency: 2,
  sessionMinutes: 45,
  location: 'HOME',
  equipment: [],
  preferences: [],
  additionalRequirements: '核心训练优先，不安排跳跃动作',
}

const context: PlanGenerationContextData = {
  profile: {
    experience: 'BEGINNER',
    goal: 'GENERAL_FITNESS',
    weeklyFrequency: 2,
    sessionMinutes: 45,
    location: 'HOME',
    profileVersion: 1,
  },
  exercises: [],
  constraints: {
    minimumSessionsPerWeek: 2,
    maximumSessionsPerWeek: 6,
    maximumExercisesPerSession: 8,
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
    templateVersion: '1.2.0',
    contentVersion: '1.2.0',
  },
}

const firstProposal: AiPlanProposal = {
  name: '第一次方案',
  days: [{
    code: 'DAY_1',
    name: '第一天',
    exercises: [{
      exerciseCode: 'UNKNOWN',
      workSets: 3,
      repMin: 8,
      repMax: 12,
      restSeconds: 75,
    }],
  }, {
    code: 'DAY_2',
    name: '第二天',
    exercises: [{
      exerciseCode: 'UNKNOWN',
      workSets: 3,
      repMin: 8,
      repMax: 12,
      restSeconds: 75,
    }],
  }],
}

const repairedProposal: AiPlanProposal = {
  ...firstProposal,
  name: '修复后方案',
}

function candidate(source: 'AI_PERSONALIZED' | 'FALLBACK_RULE_PLAN'): PlanCandidateGenerationData {
  return {
    status: 'CANDIDATE_READY',
    candidate: {
      candidateId: `candidate-${source}`,
      generationSource: source,
      plan: {
        templateCode: source === 'AI_PERSONALIZED' ? 'AI_PERSONALIZED' : 'BODYWEIGHT_2_DAY_V1',
        name: '可用计划',
        days: [],
        locks: {},
      },
      validationIssues: [],
      ruleReference: context.ruleReference,
      lockedFieldOutcomes: {},
      explanationStatus: source === 'AI_PERSONALIZED' ? 'PENDING' : 'DEGRADED',
      explanation: '说明',
      expiresAt: '2026-08-04T12:00:00Z',
    },
    validationIssues: [],
    lockedFieldOutcomes: {},
  }
}

function port(results: PlanCandidateGenerationData[]): OnboardingPersistencePort {
  return {
    getProfileVersion: vi.fn().mockResolvedValue(0),
    getEquipmentVersion: vi.fn().mockResolvedValue(0),
    getPreferencesVersion: vi.fn().mockResolvedValue(0),
    saveProfile: vi.fn().mockResolvedValue({ version: 1 }),
    saveEquipment: vi.fn().mockResolvedValue({ version: 1 }),
    savePreferences: vi.fn().mockResolvedValue({ version: 1 }),
    getPlanGenerationContext: vi.fn().mockResolvedValue(context),
    generateCandidate: vi.fn()
      .mockImplementation(() => Promise.resolve(results.shift() ?? candidate('FALLBACK_RULE_PLAN'))),
  }
}

describe('AI-primary onboarding orchestration', () => {
  it('repairs one backend-rejected AI proposal before accepting the candidate', async () => {
    const rejected: PlanCandidateGenerationData = {
      status: 'NO_CANDIDATE',
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'EXERCISE_NOT_ELIGIBLE',
        fieldPath: '/days/DAY_1/exercises/UNKNOWN',
      }],
      lockedFieldOutcomes: {},
    }
    const persistence = port([rejected, candidate('AI_PERSONALIZED')])
    const generator: AiPlanGenerator = {
      generate: vi.fn()
        .mockResolvedValueOnce(firstProposal)
        .mockResolvedValueOnce(repairedProposal),
    }

    await expect(saveProfileAndGenerateCandidate(persistence, draft, generator))
      .resolves.toMatchObject({
        candidate: { generationSource: 'AI_PERSONALIZED' },
      })

    expect(generator.generate).toHaveBeenCalledTimes(2)
    expect(generator.generate).toHaveBeenNthCalledWith(
      2,
      context,
      draft.additionalRequirements,
      rejected.validationIssues,
    )
    expect(persistence.generateCandidate).toHaveBeenNthCalledWith(1, {
      profileVersion: 1,
      additionalRequirements: draft.additionalRequirements,
      aiProposal: firstProposal,
      fallbackAllowed: false,
    })
    expect(persistence.generateCandidate).toHaveBeenNthCalledWith(2, {
      profileVersion: 1,
      additionalRequirements: draft.additionalRequirements,
      aiProposal: repairedProposal,
      fallbackAllowed: false,
    })
  })

  it('uses an explicitly requested fallback when CloudBase AI is unavailable', async () => {
    const persistence = port([candidate('FALLBACK_RULE_PLAN')])
    const generator: AiPlanGenerator = {
      generate: vi.fn().mockRejectedValue(new AiPlanUnavailableError('provider unavailable')),
    }

    await expect(saveProfileAndGenerateCandidate(persistence, draft, generator))
      .resolves.toMatchObject({
        candidate: { generationSource: 'FALLBACK_RULE_PLAN' },
      })

    expect(generator.generate).toHaveBeenCalledTimes(1)
    expect(persistence.generateCandidate).toHaveBeenCalledOnce()
    expect(persistence.generateCandidate).toHaveBeenCalledWith({
      profileVersion: 1,
      additionalRequirements: draft.additionalRequirements,
      fallbackAllowed: true,
    })
  })

  it('never performs more than one repair before falling back', async () => {
    const rejected: PlanCandidateGenerationData = {
      status: 'NO_CANDIDATE',
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'SESSION_DURATION_EXCEEDED',
        fieldPath: '/days/DAY_1',
      }],
      lockedFieldOutcomes: {},
    }
    const persistence = port([rejected, rejected, candidate('FALLBACK_RULE_PLAN')])
    const generator: AiPlanGenerator = {
      generate: vi.fn()
        .mockResolvedValueOnce(firstProposal)
        .mockResolvedValueOnce(repairedProposal),
    }

    await expect(saveProfileAndGenerateCandidate(persistence, draft, generator))
      .resolves.toMatchObject({
        candidate: { generationSource: 'FALLBACK_RULE_PLAN' },
      })

    expect(generator.generate).toHaveBeenCalledTimes(2)
    expect(persistence.generateCandidate).toHaveBeenCalledTimes(3)
    expect(persistence.generateCandidate).toHaveBeenLastCalledWith({
      profileVersion: 1,
      additionalRequirements: draft.additionalRequirements,
      fallbackAllowed: true,
    })
  })

  it('does not hide generation-context or candidate contract failures behind fallback', async () => {
    const contextFailure = port([])
    vi.mocked(contextFailure.getPlanGenerationContext!).mockRejectedValue(
      new Error('profile version conflict'),
    )
    const generator: AiPlanGenerator = {
      generate: vi.fn().mockResolvedValue(firstProposal),
    }

    await expect(saveProfileAndGenerateCandidate(contextFailure, draft, generator))
      .rejects.toThrow('profile version conflict')
    expect(contextFailure.generateCandidate).not.toHaveBeenCalled()

    const candidateFailure = port([])
    vi.mocked(candidateFailure.generateCandidate).mockRejectedValue(
      new Error('contract response invalid'),
    )
    await expect(saveProfileAndGenerateCandidate(candidateFailure, draft, generator))
      .rejects.toThrow('contract response invalid')
    expect(candidateFailure.generateCandidate).toHaveBeenCalledTimes(1)
  })

  it('preserves existing structured preferences until the user edits them', async () => {
    const persistence = port([candidate('AI_PERSONALIZED')])
    const generator: AiPlanGenerator = {
      generate: vi.fn().mockResolvedValue(firstProposal),
    }

    await saveProfileAndGenerateCandidate(persistence, draft, generator)

    expect(persistence.savePreferences).not.toHaveBeenCalled()
  })

  it('saves structured preferences when the user explicitly changes them', async () => {
    const persistence = port([candidate('AI_PERSONALIZED')])
    const generator: AiPlanGenerator = {
      generate: vi.fn().mockResolvedValue(firstProposal),
    }
    const editedDraft: OnboardingDraft = {
      ...draft,
      preferences: [{
        exerciseId: '11111111-1111-4111-8111-111111111111',
        preferenceType: 'PREFERRED',
      }],
      preferencesTouched: true,
    }

    await saveProfileAndGenerateCandidate(persistence, editedDraft, generator)

    expect(persistence.savePreferences).toHaveBeenCalledWith({
      items: editedDraft.preferences,
      expectedVersion: 0,
    })
  })
})
