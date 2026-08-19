import { describe, expect, it, vi } from 'vitest'

import {
  AiDiagnosticError,
  type AiPlanGenerator,
} from '../src/application/cloudbaseAi'
import {
  createOnboardingState,
  saveProfileAndGenerateCandidate,
  type OnboardingDraft,
  type OnboardingPersistencePort,
} from '../src/application/onboarding'
import type {
  AiPlanProposal,
  PlanCandidateGenerationData,
  PlanGenerationContextData,
} from '../src/application/models'
import { createFitnessApplication } from '../src/application/useCases'

const draft: OnboardingDraft = {
  adultConfirmed: true,
  safetyAccepted: true,
  goal: 'GENERAL_FITNESS',
  experience: 'BEGINNER',
  trainingSplit: 'UPPER_LOWER',
  weeklyFrequency: 2,
  sessionMinutes: 45,
  location: 'HOME',
  equipment: [],
  preferences: [],
  additionalRequirements: '核心训练优先，不安排跳跃动作',
  aiConsentGranted: false,
}

const context = {
  profile: {
    experience: 'BEGINNER', trainingSplit: 'UPPER_LOWER', goal: 'GENERAL_FITNESS', weeklyFrequency: 2,
    sessionMinutes: 45, location: 'HOME', profileVersion: 1,
  },
  exercises: [],
  constraints: {
    minimumSessionsPerWeek: 2, maximumSessionsPerWeek: 6,
    maximumExercisesPerSession: 5, minimumWorkSets: 2, maximumWorkSets: 4,
    minimumReps: 5, maximumReps: 15, minimumRestSeconds: 45,
    maximumRestSeconds: 240, secondsPerWorkSet: 45,
    secondsPerExerciseTransition: 75, maximumMovementPatternOccurrencesPerSession: 2,
    maximumWorkSetsPerPrimaryMusclePerSession: 12,
    minimumRecoveryHoursBetweenPrimaryMuscleSessions: 48,
  },
  ruleReference: {
    ruleVersion: '1.3.0', templateVersion: '1.3.0', contentVersion: '1.6.0',
  },
} satisfies PlanGenerationContextData

const proposal: AiPlanProposal = {
  name: 'AI 候选',
  days: [],
}

const fallbackCandidate: PlanCandidateGenerationData = {
  status: 'CANDIDATE_READY',
  candidate: {
    candidateId: 'fallback-candidate',
    generationSource: 'FALLBACK_RULE_PLAN',
    plan: { templateCode: 'BODYWEIGHT_2_DAY_V1', name: '规则计划', days: [], locks: {} },
    validationIssues: [],
    ruleReference: context.ruleReference,
    lockedFieldOutcomes: {},
    explanationStatus: 'DEGRADED',
    explanation: '规则说明',
    expiresAt: '2026-08-11T12:00:00Z',
  },
  validationIssues: [],
  lockedFieldOutcomes: {},
}

const aiCandidate: PlanCandidateGenerationData = {
  ...fallbackCandidate,
  candidate: {
    ...fallbackCandidate.candidate!,
    candidateId: 'ai-candidate',
    generationSource: 'AI_PERSONALIZED',
    plan: { ...fallbackCandidate.candidate!.plan, templateCode: 'AI_PERSONALIZED' },
  },
}

function port(): OnboardingPersistencePort {
  return {
    getProfileVersion: vi.fn().mockResolvedValue(0),
    getEquipmentVersion: vi.fn().mockResolvedValue(0),
    getPreferencesVersion: vi.fn().mockResolvedValue(0),
    saveProfile: vi.fn().mockResolvedValue({ version: 1 }),
    saveEquipment: vi.fn().mockResolvedValue({ version: 1 }),
    savePreferences: vi.fn().mockResolvedValue({ version: 1 }),
    getPlanGenerationContext: vi.fn().mockResolvedValue(context),
    generateCandidate: vi.fn().mockResolvedValue(fallbackCandidate),
  }
}

describe('onboarding AI boundary', () => {
  it('defaults AI consent to false', () => {
    expect(createOnboardingState().draft.aiConsentGranted).toBe(false)
  })

  it('uses deterministic fallback and makes zero AI/context calls without consent', async () => {
    const persistence = port()
    const generator: AiPlanGenerator = { generate: vi.fn() }

    await expect(saveProfileAndGenerateCandidate(persistence, draft, generator))
      .resolves.toMatchObject({ candidate: { generationSource: 'FALLBACK_RULE_PLAN' } })

    expect(generator.generate).not.toHaveBeenCalled()
    expect(persistence.getPlanGenerationContext).not.toHaveBeenCalled()
    expect(persistence.generateCandidate).toHaveBeenCalledWith({
      profileVersion: 1,
      trainingSplit: 'UPPER_LOWER',
      additionalRequirements: draft.additionalRequirements,
      fallbackAllowed: true,
    })
  })

  it('uses AI selection only after consent and asks the backend for authoritative validation', async () => {
    const persistence = port()
    vi.mocked(persistence.generateCandidate).mockResolvedValue(aiCandidate)
    const generator: AiPlanGenerator = { generate: vi.fn().mockResolvedValue(proposal) }

    await expect(saveProfileAndGenerateCandidate(
      persistence,
      { ...draft, aiConsentGranted: true },
      generator,
    )).resolves.toMatchObject({ candidate: { generationSource: 'AI_PERSONALIZED' } })

    expect(persistence.getPlanGenerationContext).toHaveBeenCalledWith(1)
    expect(generator.generate).toHaveBeenCalledWith(context, {
      consentGranted: true,
      repairIssues: undefined,
    })
    expect(persistence.generateCandidate).toHaveBeenCalledWith({
      profileVersion: 1,
      trainingSplit: 'UPPER_LOWER',
      additionalRequirements: draft.additionalRequirements,
      aiProposal: proposal,
      fallbackAllowed: false,
    })
  })

  it('uses one structured repair attempt before accepting an AI candidate', async () => {
    const rejected: PlanCandidateGenerationData = {
      status: 'NO_CANDIDATE',
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'SESSION_TARGET_UNDERFILLED',
        fieldPath: '/days/DAY_1/exercises',
      }],
      lockedFieldOutcomes: {},
    }
    const persistence = port()
    vi.mocked(persistence.generateCandidate)
      .mockResolvedValueOnce(rejected)
      .mockResolvedValueOnce(aiCandidate)
    const generator: AiPlanGenerator = { generate: vi.fn().mockResolvedValue(proposal) }

    await expect(saveProfileAndGenerateCandidate(
      persistence,
      { ...draft, aiConsentGranted: true },
      generator,
    )).resolves.toMatchObject({ candidate: { generationSource: 'AI_PERSONALIZED' } })

    expect(generator.generate).toHaveBeenCalledTimes(2)
    expect(generator.generate).toHaveBeenNthCalledWith(2, context, {
      consentGranted: true,
      repairIssues: rejected.validationIssues,
    })
    expect(persistence.generateCandidate).toHaveBeenCalledTimes(2)
  })

  it('stops after one repair and surfaces the final rule rejection without fallback', async () => {
    const rejected: PlanCandidateGenerationData = {
      status: 'NO_CANDIDATE',
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'RECOVERY_WINDOW_TOO_SHORT',
        fieldPath: '/days/DAY_2',
      }],
      lockedFieldOutcomes: {},
    }
    const persistence = port()
    vi.mocked(persistence.generateCandidate)
      .mockResolvedValueOnce(rejected)
      .mockResolvedValueOnce(rejected)
    const generator: AiPlanGenerator = { generate: vi.fn().mockResolvedValue(proposal) }

    await expect(saveProfileAndGenerateCandidate(
      persistence,
      { ...draft, aiConsentGranted: true },
      generator,
    )).resolves.toEqual(rejected)

    expect(generator.generate).toHaveBeenCalledTimes(2)
    expect(persistence.generateCandidate).toHaveBeenCalledTimes(2)
    expect(persistence.generateCandidate).not.toHaveBeenCalledWith(
      expect.objectContaining({ fallbackAllowed: true }),
    )
  })

  it('requires a fresh consent choice when onboarding is opened again', async () => {
    const persistence = port()
    vi.mocked(persistence.generateCandidate).mockResolvedValue(aiCandidate)
    const generator: AiPlanGenerator = { generate: vi.fn().mockResolvedValue(proposal) }
    const application = createFitnessApplication(persistence, {
      validatePlan: vi.fn(),
      createInitialPlan: vi.fn(),
      getActivePlan: vi.fn(),
      createPlanVersion: vi.fn(),
      previewRebalance: vi.fn(),
    }, generator)

    await application.completeOnboarding({ ...draft, aiConsentGranted: true })

    expect(application.resumeOnboarding().draft.aiConsentGranted).toBe(false)
  })

  it('falls back only for ordinary provider failures', async () => {
    const persistence = port()
    const generator: AiPlanGenerator = {
      generate: vi.fn().mockRejectedValue(new AiDiagnosticError(
        'TRANSIENT', 'AI_PROVIDER_TRANSIENT', 'temporary',
      )),
    }

    await expect(saveProfileAndGenerateCandidate(
      persistence,
      { ...draft, aiConsentGranted: true },
      generator,
    )).resolves.toMatchObject({ candidate: { generationSource: 'FALLBACK_RULE_PLAN' } })
    expect(persistence.generateCandidate).toHaveBeenCalledTimes(1)
    expect(persistence.generateCandidate).toHaveBeenCalledWith(expect.objectContaining({
      fallbackAllowed: true,
    }))
  })

  it('surfaces contract and unsafe-output failures without hiding them behind fallback', async () => {
    const persistence = port()
    const generator: AiPlanGenerator = {
      generate: vi.fn().mockRejectedValue(new AiDiagnosticError(
        'CONTRACT', 'AI_CONTRACT_INVALID', 'invalid response',
      )),
    }

    await expect(saveProfileAndGenerateCandidate(
      persistence,
      { ...draft, aiConsentGranted: true },
      generator,
    )).rejects.toMatchObject({ category: 'CONTRACT' })
    expect(persistence.generateCandidate).not.toHaveBeenCalled()
  })

  it('preserves structured preferences only when explicitly edited', async () => {
    const untouched = port()
    await saveProfileAndGenerateCandidate(untouched, draft)
    expect(untouched.savePreferences).not.toHaveBeenCalled()

    const edited = port()
    const editedDraft: OnboardingDraft = {
      ...draft,
      preferences: [{
        exerciseId: '11111111-1111-4111-8111-111111111111',
        preferenceType: 'PREFERRED',
      }],
      preferencesTouched: true,
    }
    await saveProfileAndGenerateCandidate(edited, editedDraft)
    expect(edited.savePreferences).toHaveBeenCalledWith({
      items: editedDraft.preferences,
      expectedVersion: 0,
    })
  })
})
