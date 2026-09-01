import { describe, expect, it, vi } from 'vitest'

import { ApplicationError } from '../src/application/errors'
import type {
  ActivePlanData,
  CandidateCommitRequest,
  PlanDraft,
  PlanVersionResultData,
} from '../src/application/models'
import type { OnboardingPersistencePort } from '../src/application/onboarding'
import type { PlanPersistencePort } from '../src/application/ports'
import { createFitnessApplication } from '../src/application/useCases'

const candidateIds = {
  first: '11111111-1111-4111-8111-111111111111',
  second: '22222222-2222-4222-8222-222222222222',
}
const workSetsPath = '/days/DAY_A/exercises/GOBLET_SQUAT/workSets'

const candidatePlan: PlanDraft = {
  templateCode: 'FULL_BODY_V1',
  trainingSplit: 'FULL_BODY',
  name: '全身训练',
  days: [{
    code: 'DAY_A',
    name: '训练日 A',
    exercises: [{
      exerciseCode: 'GOBLET_SQUAT',
      workSets: 3,
      repMin: 8,
      repMax: 12,
      restSeconds: 90,
      weightStatus: 'KNOWN',
    }],
  }],
  locks: {},
}

const ruleReference = {
  ruleVersion: 'r1',
  templateVersion: 't1',
  contentVersion: 'c1',
}

function candidate(candidateId: string, plan: PlanDraft = candidatePlan) {
  return {
    candidateId,
    plan,
    validationIssues: [],
    ruleReference,
    lockedFieldOutcomes: {},
    explanationStatus: 'READY' as const,
    explanation: '规则候选',
    expiresAt: '2026-09-01T00:00:00Z',
  }
}

function onboardingPort(...candidateQueue: ReturnType<typeof candidate>[]): OnboardingPersistencePort {
  const generated = [...candidateQueue]
  return {
    getProfileVersion: vi.fn().mockResolvedValue(1),
    getEquipmentVersion: vi.fn().mockResolvedValue(1),
    getPreferencesVersion: vi.fn().mockResolvedValue(1),
    saveProfile: vi.fn().mockResolvedValue({ version: 1 }),
    saveEquipment: vi.fn().mockResolvedValue({ version: 1 }),
    savePreferences: vi.fn().mockResolvedValue({ version: 1 }),
    listPlanPresets: vi.fn().mockResolvedValue([]),
    generateCandidate: vi.fn().mockImplementation(async () => {
      const next = generated.shift()
      if (!next) throw new Error('candidate queue exhausted')
      return {
        status: 'CANDIDATE_READY',
        candidate: next,
        validationIssues: [],
        lockedFieldOutcomes: {},
      }
    }),
  }
}

function activePlan(
  planId: string,
  versionNumber: number,
  plan: PlanDraft = candidatePlan,
): ActivePlanData {
  return {
    planId,
    activeVersion: {
      id: `version-${versionNumber}`,
      planId,
      versionNumber,
      sourceType: versionNumber === 1 ? 'INITIAL' : 'USER_EDIT',
      plan,
      ruleReference,
      confirmedWarningCodes: [],
      createdAt: '2026-08-31T00:00:00Z',
    },
  }
}

function createdResult(
  request: CandidateCommitRequest,
  planId: string,
  versionNumber: number,
): PlanVersionResultData {
  const plan = { ...request.plan, locks: request.locks }
  return {
    status: 'CREATED',
    plan,
    validationIssues: [],
    version: {
      id: `version-${versionNumber}`,
      planId,
      versionNumber,
      sourceType: versionNumber === 1 ? 'INITIAL' : 'USER_EDIT',
      plan,
      ruleReference,
      confirmedWarningCodes: [],
      createdAt: '2026-08-31T00:01:00Z',
    },
  }
}

function planPort(overrides: Partial<PlanPersistencePort> = {}): PlanPersistencePort {
  return {
    validatePlan: vi.fn().mockResolvedValue({ valid: true, validationIssues: [] }),
    createInitialPlan: vi.fn(),
    getActivePlan: vi.fn().mockResolvedValue(null),
    commitCandidate: vi.fn(),
    createPlanVersion: vi.fn(),
    previewRebalance: vi.fn(),
    ...overrides,
  }
}

async function openEditedCandidate(
  application: ReturnType<typeof createFitnessApplication>,
  workSets = 4,
): Promise<void> {
  await application.selectPlanPreset('FULL_BODY_V1')
  application.openCandidateEditor()
  application.editPlanNumber('DAY_A', 'GOBLET_SQUAT', 'workSets', workSets)
}

describe('atomic candidate editor commit', () => {
  it('creates only version 1 when no active plan exists', async () => {
    const commitCandidate = vi.fn().mockImplementation(
      async (request: CandidateCommitRequest) => createdResult(request, 'plan-first', 1),
    )
    const plans = planPort({ commitCandidate })
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first)),
      plans,
    )
    await openEditedCandidate(application)

    const saved = await application.saveEditor()

    expect(plans.getActivePlan).toHaveBeenCalledOnce()
    expect(commitCandidate).toHaveBeenCalledWith(
      expect.objectContaining({
        candidateId: candidateIds.first,
        expectedActiveVersionNumber: 0,
        plan: expect.objectContaining({
          days: [expect.objectContaining({
            exercises: [expect.objectContaining({ workSets: 4 })],
          })],
        }),
        locks: { [workSetsPath]: 'USER_LOCKED' },
      }),
      expect.stringMatching(/^candidate-commit-[a-f0-9]{32}$/),
    )
    expect(plans.createInitialPlan).not.toHaveBeenCalled()
    expect(plans.createPlanVersion).not.toHaveBeenCalled()
    expect(saved).toMatchObject({ planId: 'plan-first', baseVersion: 1 })
    expect(application.getActivePlan()).toMatchObject({
      planId: 'plan-first',
      activeVersion: { versionNumber: 1 },
    })
  })

  it('appends exactly N plus 1 when an active plan is discovered before commit', async () => {
    const existing = activePlan('plan-existing', 4)
    const commitCandidate = vi.fn().mockImplementation(
      async (request: CandidateCommitRequest) => createdResult(request, existing.planId, 5),
    )
    const plans = planPort({
      getActivePlan: vi.fn().mockResolvedValue(existing),
      commitCandidate,
    })
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first)),
      plans,
    )
    await openEditedCandidate(application)

    await application.saveEditor()

    expect(commitCandidate.mock.calls[0][0]).toMatchObject({
      candidateId: candidateIds.first,
      expectedActiveVersionNumber: 4,
    })
    expect(application.getActivePlan()).toMatchObject({
      planId: 'plan-existing',
      activeVersion: { versionNumber: 5 },
    })
    expect(plans.createInitialPlan).not.toHaveBeenCalled()
    expect(plans.createPlanVersion).not.toHaveBeenCalled()
  })

  it('reuses the same semantic key for warning confirmation and excludes the token from identity', async () => {
    const warningIssue = {
      severity: 'WARNING' as const,
      reasonCode: 'HIGH_VOLUME',
      fieldPath: '/days/DAY_A',
    }
    const validatePlan = vi.fn().mockResolvedValue({
      valid: true,
      validationIssues: [warningIssue],
    })
    const commitCandidate = vi.fn()
      .mockImplementationOnce(async (request: CandidateCommitRequest) => ({
        status: 'WARNING_CONFIRMATION_REQUIRED',
        plan: {
          ...request.plan,
          days: request.plan.days.map((day) => ({
            ...day,
            exercises: day.exercises.map((exercise) => ({
              ...exercise,
              workSets: exercise.workSets + 1,
            })),
          })),
          locks: request.locks,
        },
        validationIssues: [warningIssue],
        warningConfirmationToken: 'warning-token-1',
      }))
      .mockImplementationOnce(async (request: CandidateCommitRequest) => (
        createdResult(request, 'plan-warning', 1)
      ))
    const plans = planPort({ validatePlan, commitCandidate })
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first)),
      plans,
    )
    await openEditedCandidate(application)

    const warning = await application.saveEditor()
    expect(warning.warningConfirmationToken).toBe('warning-token-1')
    application.confirmEditorWarnings()
    await application.saveEditor()

    expect(commitCandidate).toHaveBeenCalledTimes(2)
    expect(commitCandidate.mock.calls[0][0]).not.toHaveProperty('warningConfirmationToken')
    expect(commitCandidate.mock.calls[1][0]).toMatchObject({
      warningConfirmationToken: 'warning-token-1',
    })
    expect(commitCandidate.mock.calls[1][0].plan).toEqual(commitCandidate.mock.calls[0][0].plan)
    expect(commitCandidate.mock.calls[1][0].plan.days[0]?.exercises[0]?.workSets).toBe(4)
    expect(commitCandidate.mock.calls[1][1]).toBe(commitCandidate.mock.calls[0][1])
    expect(plans.getActivePlan).toHaveBeenCalledOnce()
  })

  it('reuses the same key after an uncertain network outcome without falling back to two writes', async () => {
    const commitCandidate = vi.fn()
      .mockRejectedValueOnce(new ApplicationError('NETWORK_ERROR', 'response lost', {
        retryable: true,
      }))
      .mockImplementationOnce(async (request: CandidateCommitRequest) => (
        createdResult(request, 'plan-retried', 1)
      ))
    const plans = planPort({ commitCandidate })
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first)),
      plans,
    )
    await openEditedCandidate(application)

    await expect(application.saveEditor()).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    await expect(application.saveEditor()).resolves.toMatchObject({
      planId: 'plan-retried',
      baseVersion: 1,
    })

    expect(commitCandidate.mock.calls[1][1]).toBe(commitCandidate.mock.calls[0][1])
    expect(plans.createInitialPlan).not.toHaveBeenCalled()
    expect(plans.createPlanVersion).not.toHaveBeenCalled()
  })

  it('does not start direct activation while an atomic candidate save is in flight', async () => {
    let finishCommit: ((value: PlanVersionResultData) => void) | undefined
    let capturedRequest: CandidateCommitRequest | undefined
    const commitCandidate = vi.fn((request: CandidateCommitRequest) => {
      capturedRequest = request
      return new Promise<PlanVersionResultData>((resolve) => { finishCommit = resolve })
    })
    const plans = planPort({
      commitCandidate,
      createInitialPlan: vi.fn().mockResolvedValue(activePlan('plan-direct', 1)),
    })
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first)),
      plans,
    )
    await openEditedCandidate(application)
    const save = application.saveEditor()
    await vi.waitFor(() => expect(commitCandidate).toHaveBeenCalledOnce())

    await expect(application.activateCandidate()).rejects.toMatchObject({
      code: 'VALIDATION_FAILED',
    })
    expect(plans.createInitialPlan).not.toHaveBeenCalled()

    if (!capturedRequest) throw new Error('candidate commit request missing')
    finishCommit?.(createdResult(capturedRequest, 'plan-atomic', 1))
    await expect(save).resolves.toMatchObject({ planId: 'plan-atomic', baseVersion: 1 })
    expect(application.getActivePlan()).toMatchObject({ planId: 'plan-atomic' })
  })

  it('does not start an atomic candidate save while direct activation is in flight', async () => {
    let finishActivation: ((value: ActivePlanData) => void) | undefined
    const createInitialPlan = vi.fn(() => new Promise<ActivePlanData>((resolve) => {
      finishActivation = resolve
    }))
    const plans = planPort({ createInitialPlan })
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first)),
      plans,
    )
    await application.selectPlanPreset('FULL_BODY_V1')
    const activation = application.activateCandidate()
    await vi.waitFor(() => expect(createInitialPlan).toHaveBeenCalledOnce())

    application.openCandidateEditor()
    application.editPlanNumber('DAY_A', 'GOBLET_SQUAT', 'workSets', 4)
    await expect(application.saveEditor()).rejects.toMatchObject({
      code: 'VALIDATION_FAILED',
    })
    expect(plans.commitCandidate).not.toHaveBeenCalled()

    const activated = activePlan('plan-direct', 1)
    finishActivation?.(activated)
    await expect(activation).resolves.toEqual(activated)
    expect(application.getActivePlan()).toEqual(activated)
    expect(application.getPlanEditor()).toMatchObject({ planId: 'plan-direct', baseVersion: 1 })
  })

  it('changes the semantic key when payload, candidate, or expected version changes', async () => {
    async function attemptKey(
      candidateId: string,
      workSets: number,
      discoveredActivePlan: ActivePlanData | null,
    ): Promise<string> {
      const commitCandidate = vi.fn().mockRejectedValue(
        new ApplicationError('NETWORK_ERROR', 'response lost', { retryable: true }),
      )
      const application = createFitnessApplication(
        onboardingPort(candidate(candidateId)),
        planPort({
          getActivePlan: vi.fn().mockResolvedValue(discoveredActivePlan),
          commitCandidate,
        }),
      )
      await openEditedCandidate(application, workSets)
      await expect(application.saveEditor()).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
      return commitCandidate.mock.calls[0][1]
    }

    const baseline = await attemptKey(candidateIds.first, 4, null)
    const changedPayload = await attemptKey(candidateIds.first, 5, null)
    const changedCandidate = await attemptKey(candidateIds.second, 4, null)
    const changedExpectedVersion = await attemptKey(
      candidateIds.first,
      4,
      activePlan('plan-existing', 3),
    )

    expect(new Set([
      baseline,
      changedPayload,
      changedCandidate,
      changedExpectedVersion,
    ])).toHaveLength(4)
  })

  it('does not install a stale candidate commit response', async () => {
    let finishCommit: ((value: PlanVersionResultData) => void) | undefined
    let capturedRequest: CandidateCommitRequest | undefined
    const commitCandidate = vi.fn((request: CandidateCommitRequest) => {
      capturedRequest = request
      return new Promise<PlanVersionResultData>((resolve) => { finishCommit = resolve })
    })
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first), candidate(candidateIds.second)),
      planPort({ commitCandidate }),
    )
    await openEditedCandidate(application)
    const staleSave = application.saveEditor()
    await vi.waitFor(() => expect(commitCandidate).toHaveBeenCalledOnce())

    await application.selectPlanPreset('FULL_BODY_V1')
    if (!capturedRequest) throw new Error('candidate commit request missing')
    finishCommit?.(createdResult(capturedRequest, 'plan-stale', 1))

    await expect(staleSave).rejects.toThrow('推荐方案已更新，本次旧编辑未保存')
    expect(application.getCandidate()?.candidateId).toBe(candidateIds.second)
    expect(application.getActivePlan()).toBeNull()
  })

  it('does not install a stale candidate version conflict', async () => {
    let rejectCommit: ((reason: unknown) => void) | undefined
    const commitCandidate = vi.fn(() => new Promise<PlanVersionResultData>((_resolve, reject) => {
      rejectCommit = reject
    }))
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first), candidate(candidateIds.second)),
      planPort({ commitCandidate }),
    )
    await openEditedCandidate(application)
    const staleSave = application.saveEditor()
    await vi.waitFor(() => expect(commitCandidate).toHaveBeenCalledOnce())

    await application.selectPlanPreset('FULL_BODY_V1')
    rejectCommit?.(new ApplicationError('VERSION_CONFLICT', '活动计划版本已变化'))

    await expect(staleSave).rejects.toThrow('推荐方案已更新，本次旧编辑未保存')
    expect(application.getCandidate()?.candidateId).toBe(candidateIds.second)
    expect(application.getPlanEditor()).toBeNull()
    expect(application.getActivePlan()).toBeNull()
  })

  it('does not install an old-account candidate commit response', async () => {
    let finishCommit: ((value: PlanVersionResultData) => void) | undefined
    let capturedRequest: CandidateCommitRequest | undefined
    const commitCandidate = vi.fn((request: CandidateCommitRequest) => {
      capturedRequest = request
      return new Promise<PlanVersionResultData>((resolve) => { finishCommit = resolve })
    })
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first)),
      planPort({ commitCandidate }),
    )
    await openEditedCandidate(application)
    const staleSave = application.saveEditor()
    await vi.waitFor(() => expect(commitCandidate).toHaveBeenCalledOnce())

    application.clearUserState()
    if (!capturedRequest) throw new Error('candidate commit request missing')
    finishCommit?.(createdResult(capturedRequest, 'plan-old-account', 1))

    await expect(staleSave).rejects.toMatchObject({ code: 'AUTHENTICATION_REQUIRED' })
    expect(application.getCandidate()).toBeNull()
    expect(application.getActivePlan()).toBeNull()
    expect(application.getPlanEditor()).toBeNull()
  })

  it('keeps direct unedited activation on createInitialPlan', async () => {
    const created = activePlan('plan-direct', 1)
    const plans = planPort({
      createInitialPlan: vi.fn().mockResolvedValue(created),
    })
    const application = createFitnessApplication(
      onboardingPort(candidate(candidateIds.first)),
      plans,
    )
    await application.selectPlanPreset('FULL_BODY_V1')

    await expect(application.activateCandidate()).resolves.toEqual(created)

    expect(plans.createInitialPlan).toHaveBeenCalledWith(candidateIds.first)
    expect(plans.commitCandidate).not.toHaveBeenCalled()
    expect(plans.createPlanVersion).not.toHaveBeenCalled()
  })

  it('keeps existing active-plan editing on createPlanVersion', async () => {
    const existing = activePlan('plan-existing', 4)
    const createPlanVersion = vi.fn().mockImplementation(async (_planId, request) => {
      const commitRequest: CandidateCommitRequest = {
        candidateId: candidateIds.first,
        expectedActiveVersionNumber: 4,
        plan: request.plan,
        locks: request.locks,
      }
      return createdResult(commitRequest, existing.planId, 5)
    })
    const plans = planPort({
      getActivePlan: vi.fn().mockResolvedValue(existing),
      createPlanVersion,
    })
    const application = createFitnessApplication(onboardingPort(), plans)
    await application.loadActivePlan()
    application.openPlanEditor()
    application.editPlanNumber('DAY_A', 'GOBLET_SQUAT', 'workSets', 4)

    await application.saveEditor()

    expect(createPlanVersion).toHaveBeenCalledWith(
      existing.planId,
      expect.objectContaining({ baseVersionNumber: 4 }),
    )
    expect(plans.commitCandidate).not.toHaveBeenCalled()
    expect(plans.createInitialPlan).not.toHaveBeenCalled()
  })
})
