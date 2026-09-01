import { describe, expect, it, vi } from 'vitest'

import type { OnboardingPersistencePort } from '../src/application/onboarding'
import {
  applyRebalancePreview,
  applyValidation,
  buildSaveCommand,
  changeNumericField,
  createPlanEditorState,
  numericFieldPath,
  setFieldLock,
} from '../src/application/planEditor'
import type { PlanPersistencePort } from '../src/application/ports'
import { createFitnessApplication } from '../src/application/useCases'

const originalPlan = {
  templateCode: 'full-body',
  name: '全身训练',
  days: [{
    code: 'day-a',
    name: '训练日 A',
    exercises: [{
      exerciseCode: 'goblet-squat',
      workSets: 3,
      repMin: 8,
      repMax: 12,
      restSeconds: 90,
      weightStatus: 'KNOWN' as const,
    }],
  }],
  locks: {
    '/days/day-a/exercises/goblet-squat/restSeconds': 'RULE_LOCKED' as const,
  },
}

const workSetsPath = '/days/day-a/exercises/goblet-squat/workSets'
const restSecondsPath = '/days/day-a/exercises/goblet-squat/restSeconds'

describe('plan editor locks', () => {
  it('builds backend-compatible paths from stable codes and rejects slash-containing segments', () => {
    expect(numericFieldPath('day-a', 'goblet-squat', 'workSets')).toBe(workSetsPath)
    expect(() => numericFieldPath('day/a', 'goblet-squat', 'workSets')).toThrow('dayCode')
    expect(() => numericFieldPath('day-a', 'goblet/squat', 'workSets')).toThrow('exerciseCode')
  })

  it('maintains baseVersion and a separate working copy with stable user-locked paths', () => {
    const initial = createPlanEditorState({
      planId: 'plan-1',
      baseVersion: 4,
      plan: originalPlan,
      validationResult: { valid: true, validationIssues: [] },
    })
    const edited = changeNumericField(initial, 'day-a', 'goblet-squat', 'workSets', 4)

    expect(edited.baseVersion).toBe(4)
    expect(edited.basePlan.days[0].exercises[0].workSets).toBe(3)
    expect(edited.workingCopy.days[0].exercises[0].workSets).toBe(4)
    expect(edited.locks[workSetsPath]).toBe('USER_LOCKED')
  })

  it('preserves fixed-preset identity and rules in validation and save requests', async () => {
    const presetPlan = {
      ...originalPlan,
      templateCode: 'PERSONAL_5_DAY_HYPERTROPHY_V1',
      trainingSplit: 'BODY_PART_FIVE_DAY' as const,
      presetCode: 'PERSONAL_5_DAY_HYPERTROPHY_V1',
      presetVersion: '1.0.0',
      executionRules: ['复合动作保留约 2 次余力。'],
      progressionRules: ['使用双进阶法。'],
      locks: {},
    }
    const state = createPlanEditorState({
      planId: 'plan-1',
      baseVersion: 1,
      plan: presetPlan,
      validationResult: { valid: true, validationIssues: [] },
    })
    expect(buildSaveCommand(state).plan).toMatchObject({
      trainingSplit: 'BODY_PART_FIVE_DAY',
      presetCode: 'PERSONAL_5_DAY_HYPERTROPHY_V1',
      presetVersion: '1.0.0',
      executionRules: presetPlan.executionRules,
      progressionRules: presetPlan.progressionRules,
    })

    const validatePlan = vi.fn().mockResolvedValue({ valid: true, validationIssues: [] })
    const activePlan = {
      ...createActivePlan(),
      activeVersion: {
        ...createActivePlan().activeVersion,
        plan: presetPlan,
      },
    }
    const application = createFitnessApplication(createUnusedOnboardingPort(), {
      validatePlan,
      createInitialPlan: vi.fn(),
      getActivePlan: vi.fn().mockResolvedValue(activePlan),
      commitCandidate: vi.fn(),
      createPlanVersion: vi.fn(),
      previewRebalance: vi.fn(),
    })
    await application.loadActivePlan()
    application.openPlanEditor()
    await application.validateEditor()

    expect(validatePlan).toHaveBeenCalledWith(
      expect.objectContaining({
        trainingSplit: 'BODY_PART_FIVE_DAY',
        presetCode: 'PERSONAL_5_DAY_HYPERTROPHY_V1',
        presetVersion: '1.0.0',
        executionRules: presetPlan.executionRules,
        progressionRules: presetPlan.progressionRules,
      }),
      activePlan.activeVersion.ruleReference,
    )
  })

  it.each(['workSets', 'repMin', 'repMax', 'restSeconds'] as const)(
    'uses the stable field path and auto-locks a user edit to %s',
    (field) => {
      const plan = {
        ...originalPlan,
        locks: {},
      }
      const state = createPlanEditorState({
        planId: 'plan-1',
        baseVersion: 1,
        plan,
        validationResult: { valid: true, validationIssues: [] },
      })
      const edited = changeNumericField(state, 'day-a', 'goblet-squat', field, 6)
      expect(edited.locks[`/days/day-a/exercises/goblet-squat/${field}`]).toBe('USER_LOCKED')
    },
  )

  it('sends UNLOCKED only after explicit unlock and never changes RULE_LOCKED fields', () => {
    let state = createPlanEditorState({
      planId: 'plan-1',
      baseVersion: 1,
      plan: originalPlan,
      validationResult: { valid: true, validationIssues: [] },
    })
    state = changeNumericField(state, 'day-a', 'goblet-squat', 'workSets', 4)
    state = setFieldLock(state, workSetsPath, 'UNLOCKED')

    expect(state.locks[workSetsPath]).toBe('UNLOCKED')
    expect(buildSaveCommand(state)).toMatchObject({
      baseVersionNumber: 1,
      locks: { [workSetsPath]: 'UNLOCKED' },
    })

    const ruleLockedEdit = changeNumericField(state, 'day-a', 'goblet-squat', 'restSeconds', 120)
    const ruleLockedUnlock = setFieldLock(
      ruleLockedEdit,
      restSecondsPath,
      'UNLOCKED',
    )
    expect(ruleLockedUnlock.workingCopy.days[0].exercises[0].restSeconds).toBe(90)
    expect(ruleLockedUnlock.locks[restSecondsPath]).toBe('RULE_LOCKED')
    expect(ruleLockedUnlock.lockedFieldOutcomes[restSecondsPath]).toBe('RULE_LOCKED')
  })

  it('preserves an explicit UNLOCKED command when editing a base USER_LOCKED field', () => {
    let state = createPlanEditorState({
      planId: 'plan-1',
      baseVersion: 1,
      plan: {
        ...originalPlan,
        locks: {
          ...originalPlan.locks,
          [workSetsPath]: 'USER_LOCKED' as const,
        },
      },
      validationResult: { valid: true, validationIssues: [] },
    })

    expect(buildSaveCommand(state).locks).toEqual({})

    state = setFieldLock(state, workSetsPath, 'UNLOCKED')
    state = changeNumericField(state, 'day-a', 'goblet-squat', 'workSets', 4)

    expect(state).toMatchObject({
      baseLocks: { [workSetsPath]: 'USER_LOCKED' },
      lockCommands: { [workSetsPath]: 'UNLOCKED' },
      locks: { [workSetsPath]: 'UNLOCKED' },
    })
    expect(buildSaveCommand(state).locks).toEqual({ [workSetsPath]: 'UNLOCKED' })
  })

  it('blocks ERROR saves and requires a second command with the warning token', () => {
    const initial = createPlanEditorState({
      planId: 'plan-1',
      baseVersion: 2,
      plan: { ...originalPlan, locks: {} },
      validationResult: { valid: true, validationIssues: [] },
    })
    const invalid = applyValidation(initial, {
      valid: false,
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'INVALID_REP_RANGE',
        fieldPath: '/days/day-a/exercises/goblet-squat/repMin',
      }],
    })
    expect(() => buildSaveCommand(invalid)).toThrow('计划包含错误，不能保存')

    const warning = applyValidation(initial, {
      valid: true,
      validationIssues: [{
        severity: 'WARNING',
        reasonCode: 'HIGH_VOLUME',
        fieldPath: '/days/day-a',
      }],
    })
    expect(buildSaveCommand(warning)).not.toHaveProperty('warningConfirmationToken')

    const awaitingConfirmation = {
      ...warning,
      warningConfirmationToken: 'warning-token',
      warningConfirmed: false,
    }
    expect(() => buildSaveCommand(awaitingConfirmation)).toThrow('请先确认所有警告')
    expect(buildSaveCommand({ ...awaitingConfirmation, warningConfirmed: true })).toMatchObject({
      warningConfirmationToken: 'warning-token',
    })
  })

  it('preserves a confirmed warning token across an unchanged deterministic preflight', async () => {
    const warningIssue = {
      severity: 'WARNING' as const,
      reasonCode: 'HIGH_VOLUME',
      fieldPath: '/days/day-a',
    }
    const validatePlan = vi.fn().mockResolvedValue({
      valid: true,
      validationIssues: [warningIssue],
    })
    const createPlanVersion = vi.fn()
      .mockImplementationOnce(async (_planId, request) => ({
        status: 'WARNING_CONFIRMATION_REQUIRED',
        plan: { ...request.plan, locks: request.locks },
        validationIssues: [warningIssue],
        warningConfirmationToken: 'warning-token-1',
      }))
      .mockImplementationOnce(async (planId, request) => ({
        status: 'CREATED',
        plan: { ...request.plan, locks: request.locks },
        validationIssues: [warningIssue],
        version: {
          id: 'version-2',
          planId,
          versionNumber: 2,
          sourceType: 'USER_EDIT',
          plan: { ...request.plan, locks: request.locks },
          ruleReference: { ruleVersion: 'r1', templateVersion: 't1', contentVersion: 'c1' },
          confirmedWarningCodes: ['HIGH_VOLUME'],
          createdAt: '2026-07-24T00:01:00Z',
        },
      }))
    const application = createFitnessApplication(createUnusedOnboardingPort(), {
      validatePlan,
      createInitialPlan: vi.fn(),
      getActivePlan: vi.fn().mockResolvedValue(createActivePlan()),
      commitCandidate: vi.fn(),
      createPlanVersion,
      previewRebalance: vi.fn(),
    })
    await application.loadActivePlan()
    application.openPlanEditor()
    application.editPlanNumber('day-a', 'goblet-squat', 'workSets', 4)

    const warning = await application.saveEditor()
    expect(warning.warningConfirmationToken).toBe('warning-token-1')
    application.confirmEditorWarnings()
    await application.saveEditor()

    expect(validatePlan).toHaveBeenCalledTimes(2)
    expect(createPlanVersion).toHaveBeenCalledTimes(2)
    expect(createPlanVersion.mock.calls[1][1]).toMatchObject({
      warningConfirmationToken: 'warning-token-1',
    })
  })

  it('drops a stale confirmed token and requires a fresh confirmation when preflight warnings change', async () => {
    const warningA = {
      severity: 'WARNING' as const,
      reasonCode: 'HIGH_VOLUME',
      fieldPath: '/days/day-a',
    }
    const warningB = {
      severity: 'WARNING' as const,
      reasonCode: 'RECOVERY_TIGHT',
      fieldPath: '/days/day-a/exercises/goblet-squat/restSeconds',
    }
    const validatePlan = vi.fn()
      .mockResolvedValueOnce({ valid: true, validationIssues: [warningA] })
      .mockResolvedValueOnce({ valid: true, validationIssues: [warningB] })
    const createPlanVersion = vi.fn()
      .mockImplementationOnce(async (_planId, request) => ({
        status: 'WARNING_CONFIRMATION_REQUIRED',
        plan: { ...request.plan, locks: request.locks },
        validationIssues: [warningA],
        warningConfirmationToken: 'warning-token-old',
      }))
      .mockImplementationOnce(async (_planId, request) => ({
        status: 'WARNING_CONFIRMATION_REQUIRED',
        plan: { ...request.plan, locks: request.locks },
        validationIssues: [warningB],
        warningConfirmationToken: 'warning-token-new',
      }))
    const application = createFitnessApplication(createUnusedOnboardingPort(), {
      validatePlan,
      createInitialPlan: vi.fn(),
      getActivePlan: vi.fn().mockResolvedValue(createActivePlan()),
      commitCandidate: vi.fn(),
      createPlanVersion,
      previewRebalance: vi.fn(),
    })
    await application.loadActivePlan()
    application.openPlanEditor()
    application.editPlanNumber('day-a', 'goblet-squat', 'workSets', 4)
    await application.saveEditor()
    application.confirmEditorWarnings()

    const changed = await application.saveEditor()

    expect(createPlanVersion.mock.calls[1][1]).not.toHaveProperty('warningConfirmationToken')
    expect(changed).toMatchObject({
      warningConfirmationToken: 'warning-token-new',
      warningConfirmed: false,
      validationResult: { validationIssues: [warningB] },
    })
  })

  it('keeps user locks during rebalance preview and exposes diffs and lock outcomes', () => {
    let state = createPlanEditorState({
      planId: 'plan-1',
      baseVersion: 5,
      plan: { ...originalPlan, locks: {} },
      validationResult: { valid: true, validationIssues: [] },
    })
    state = changeNumericField(state, 'day-a', 'goblet-squat', 'workSets', 4)
    state = {
      ...state,
      lockedFieldOutcomes: { [workSetsPath]: 'USER_LOCKED' },
    }

    const preview = applyRebalancePreview(state, {
      status: 'PREVIEW',
      plan: {
        ...state.workingCopy,
        days: [{
          ...state.workingCopy.days[0],
          exercises: [{
            ...state.workingCopy.days[0].exercises[0],
            workSets: 2,
            repMax: 10,
          }],
        }],
        locks: {
          [workSetsPath]: 'USER_LOCKED',
        },
      },
      validationIssues: [],
    })

    expect(preview.workingCopy.days[0].exercises[0].workSets).toBe(4)
    expect(preview.workingCopy.days[0].exercises[0].repMax).toBe(10)
    expect(preview.rebalanceDiffs).toContainEqual({
      fieldPath: '/days/day-a/exercises/goblet-squat/repMax',
      before: 12,
      after: 10,
    })
    expect(preview.lockedFieldOutcomes).toEqual({
      [workSetsPath]: 'USER_LOCKED',
    })
    expect(preview.baseVersion).toBe(5)
  })

  it('surfaces version conflicts without replacing the working copy', () => {
    const initial = createPlanEditorState({
      planId: 'plan-1',
      baseVersion: 7,
      plan: { ...originalPlan, locks: {} },
      validationResult: { valid: true, validationIssues: [] },
    })
    const conflicted = {
      ...initial,
      conflict: {
        code: 'VERSION_CONFLICT' as const,
        message: '活动计划已在其他位置更新，请刷新后比较',
      },
    }
    expect(conflicted.conflict.code).toBe('VERSION_CONFLICT')
    expect(conflicted.workingCopy).toEqual(initial.workingCopy)
  })

  it('keeps candidate editing local until one atomic save confirms version 1', async () => {
    const candidate = {
      candidateId: 'candidate-1',
      plan: { ...originalPlan, locks: {} },
      validationIssues: [],
      ruleReference: {
        ruleVersion: 'r1',
        templateVersion: 't1',
        contentVersion: 'c1',
      },
      lockedFieldOutcomes: {},
      explanationStatus: 'READY' as const,
      explanation: '规则候选',
      expiresAt: '2026-07-25T00:00:00Z',
    }
    const onboardingPort: OnboardingPersistencePort = {
      getProfileVersion: vi.fn().mockResolvedValue(0),
      getEquipmentVersion: vi.fn().mockResolvedValue(0),
      getPreferencesVersion: vi.fn().mockResolvedValue(0),
      saveProfile: vi.fn().mockResolvedValue({ version: 1 }),
      saveEquipment: vi.fn().mockResolvedValue({ version: 1 }),
      savePreferences: vi.fn().mockResolvedValue({ version: 1 }),
      listPlanPresets: vi.fn().mockResolvedValue([]),
      generateCandidate: vi.fn().mockResolvedValue({
        status: 'CANDIDATE_READY',
        candidate,
        validationIssues: [],
        lockedFieldOutcomes: {},
      }),
    }
    const createInitialPlan = vi.fn()
    const createPlanVersion = vi.fn()
    const commitCandidate = vi.fn().mockImplementation(async (request) => ({
      status: 'CREATED',
      plan: { ...request.plan, locks: request.locks },
      validationIssues: [],
      version: {
        id: 'version-1',
        planId: 'plan-1',
        versionNumber: 1,
        sourceType: 'INITIAL',
        plan: { ...request.plan, locks: request.locks },
        ruleReference: candidate.ruleReference,
        confirmedWarningCodes: [],
        createdAt: '2026-07-24T00:01:00Z',
      },
    }))
    const planPort: PlanPersistencePort = {
      validatePlan: vi.fn().mockResolvedValue({ valid: true, validationIssues: [] }),
      createInitialPlan,
      getActivePlan: vi.fn().mockResolvedValue(null),
      commitCandidate,
      createPlanVersion,
      previewRebalance: vi.fn(),
    }
    const application = createFitnessApplication(onboardingPort, planPort)
    await application.completeOnboarding({
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS',
      experience: 'BEGINNER',
      weeklyFrequency: 3,
      sessionMinutes: 45,
      location: 'GYM',
      equipment: [],
      preferences: [],
    })

    application.openCandidateEditor()
    application.editPlanNumber('day-a', 'goblet-squat', 'workSets', 4)
    expect(createInitialPlan).not.toHaveBeenCalled()

    await application.saveEditor()

    expect(commitCandidate).toHaveBeenCalledWith(expect.objectContaining({
      candidateId: 'candidate-1',
      expectedActiveVersionNumber: 0,
      locks: { [workSetsPath]: 'USER_LOCKED' },
    }), expect.stringMatching(/^candidate-commit-/))
    expect(createInitialPlan).not.toHaveBeenCalled()
    expect(createPlanVersion).not.toHaveBeenCalled()
  })

  it('keeps code-targeted edits stable when days and exercises are reordered', () => {
    const reorderedPlan = {
      ...originalPlan,
      days: [
        {
          code: 'day-b',
          name: '训练日 B',
          exercises: [{
            ...originalPlan.days[0].exercises[0],
            exerciseCode: 'row',
            workSets: 5,
          }],
        },
        originalPlan.days[0],
      ],
      locks: {},
    }
    const state = createPlanEditorState({
      planId: 'plan-1',
      baseVersion: 1,
      plan: reorderedPlan,
      validationResult: { valid: true, validationIssues: [] },
    })

    const edited = changeNumericField(state, 'day-a', 'goblet-squat', 'workSets', 4)

    expect(edited.workingCopy.days[0].exercises[0].workSets).toBe(5)
    expect(edited.workingCopy.days[1].exercises[0].workSets).toBe(4)
    expect(edited.locks[workSetsPath]).toBe('USER_LOCKED')
  })

  it('preserves a code-targeted user lock across rebalance reordering', () => {
    const dayB = {
      code: 'day-b',
      name: '训练日 B',
      exercises: [{
        ...originalPlan.days[0].exercises[0],
        exerciseCode: 'row',
        workSets: 5,
      }],
    }
    let state = createPlanEditorState({
      planId: 'plan-1',
      baseVersion: 3,
      plan: { ...originalPlan, days: [originalPlan.days[0], dayB], locks: {} },
      validationResult: { valid: true, validationIssues: [] },
    })
    state = changeNumericField(state, 'day-a', 'goblet-squat', 'workSets', 4)
    state = {
      ...state,
      lockedFieldOutcomes: { [workSetsPath]: 'USER_LOCKED' },
    }

    const preview = applyRebalancePreview(state, {
      status: 'PREVIEW',
      plan: {
        ...state.workingCopy,
        days: [
          dayB,
          {
            ...state.workingCopy.days[0],
            exercises: [{ ...state.workingCopy.days[0].exercises[0], workSets: 2, repMax: 10 }],
          },
        ],
        locks: { [workSetsPath]: 'USER_LOCKED' },
      },
      validationIssues: [],
    })

    const dayA = preview.workingCopy.days.find((day) => day.code === 'day-a')!
    expect(dayA.exercises[0].workSets).toBe(4)
    expect(dayA.exercises[0].repMax).toBe(10)
    expect(preview.rebalanceDiffs).toContainEqual({
      fieldPath: '/days/day-a/exercises/goblet-squat/repMax',
      before: 12,
      after: 10,
    })
  })

  it('does not create any plan version when a provisional candidate contains ERROR', async () => {
    const candidate = {
      candidateId: 'candidate-error',
      plan: { ...originalPlan, locks: {} },
      validationIssues: [{
        severity: 'ERROR' as const,
        reasonCode: 'INVALID_REP_RANGE',
        fieldPath: '/days/day-a/exercises/goblet-squat/repMin',
      }],
      ruleReference: {
        ruleVersion: 'r1',
        templateVersion: 't1',
        contentVersion: 'c1',
      },
      lockedFieldOutcomes: {},
      explanationStatus: 'READY' as const,
      explanation: '规则候选',
      expiresAt: '2026-07-25T00:00:00Z',
    }
    const onboardingPort: OnboardingPersistencePort = {
      getProfileVersion: vi.fn().mockResolvedValue(0),
      getEquipmentVersion: vi.fn().mockResolvedValue(0),
      getPreferencesVersion: vi.fn().mockResolvedValue(0),
      saveProfile: vi.fn().mockResolvedValue({ version: 1 }),
      saveEquipment: vi.fn().mockResolvedValue({ version: 1 }),
      savePreferences: vi.fn().mockResolvedValue({ version: 1 }),
      listPlanPresets: vi.fn().mockResolvedValue([]),
      generateCandidate: vi.fn().mockResolvedValue({
        status: 'CANDIDATE_READY',
        candidate,
        validationIssues: candidate.validationIssues,
        lockedFieldOutcomes: {},
      }),
    }
    const createInitialPlan = vi.fn()
    const createPlanVersion = vi.fn()
    const application = createFitnessApplication(onboardingPort, {
      validatePlan: vi.fn(),
      createInitialPlan,
      getActivePlan: vi.fn(),
      commitCandidate: vi.fn(),
      createPlanVersion,
      previewRebalance: vi.fn(),
    })
    await application.completeOnboarding({
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS',
      experience: 'BEGINNER',
      weeklyFrequency: 3,
      sessionMinutes: 45,
      location: 'GYM',
      equipment: [],
      preferences: [],
    })
    application.openCandidateEditor()

    await expect(application.saveEditor()).rejects.toThrow('计划包含错误，不能保存')
    expect(createInitialPlan).not.toHaveBeenCalled()
    expect(createPlanVersion).not.toHaveBeenCalled()
  })

  it('validates the exact edited working copy before any provisional plan write', async () => {
    const candidate = {
      candidateId: 'candidate-server-invalid',
      plan: { ...originalPlan, locks: {} },
      validationIssues: [],
      ruleReference: {
        ruleVersion: 'r1',
        templateVersion: 't1',
        contentVersion: 'c1',
      },
      lockedFieldOutcomes: {},
      explanationStatus: 'READY' as const,
      explanation: '规则候选',
      expiresAt: '2026-07-25T00:00:00Z',
    }
    const onboardingPort: OnboardingPersistencePort = {
      getProfileVersion: vi.fn().mockResolvedValue(0),
      getEquipmentVersion: vi.fn().mockResolvedValue(0),
      getPreferencesVersion: vi.fn().mockResolvedValue(0),
      saveProfile: vi.fn().mockResolvedValue({ version: 1 }),
      saveEquipment: vi.fn().mockResolvedValue({ version: 1 }),
      savePreferences: vi.fn().mockResolvedValue({ version: 1 }),
      listPlanPresets: vi.fn().mockResolvedValue([]),
      generateCandidate: vi.fn().mockResolvedValue({
        status: 'CANDIDATE_READY',
        candidate,
        validationIssues: [],
        lockedFieldOutcomes: {},
      }),
    }
    const validatePlan = vi.fn().mockResolvedValue({
      valid: false,
      validationIssues: [{
        severity: 'ERROR',
        reasonCode: 'SERVER_REJECTED_EDIT',
        fieldPath: workSetsPath,
      }],
    })
    const createInitialPlan = vi.fn()
    const createPlanVersion = vi.fn()
    const application = createFitnessApplication(onboardingPort, {
      validatePlan,
      createInitialPlan,
      getActivePlan: vi.fn(),
      commitCandidate: vi.fn(),
      createPlanVersion,
      previewRebalance: vi.fn(),
    })
    await application.completeOnboarding({
      adultConfirmed: true,
      safetyAccepted: true,
      goal: 'GENERAL_FITNESS',
      experience: 'BEGINNER',
      weeklyFrequency: 3,
      sessionMinutes: 45,
      location: 'GYM',
      equipment: [],
      preferences: [],
    })
    application.openCandidateEditor()
    application.editPlanNumber('day-a', 'goblet-squat', 'workSets', 4)

    await expect(application.saveEditor()).rejects.toThrow('计划包含错误，不能保存')
    expect(validatePlan).toHaveBeenCalledWith(
      expect.objectContaining({
        days: [expect.objectContaining({
          exercises: [expect.objectContaining({ workSets: 4 })],
        })],
      }),
      candidate.ruleReference,
    )
    expect(createInitialPlan).not.toHaveBeenCalled()
    expect(createPlanVersion).not.toHaveBeenCalled()
  })
})

function createUnusedOnboardingPort(): OnboardingPersistencePort {
  return {
    getProfileVersion: vi.fn(),
    getEquipmentVersion: vi.fn(),
    getPreferencesVersion: vi.fn(),
    saveProfile: vi.fn(),
    saveEquipment: vi.fn(),
    savePreferences: vi.fn(),
    listPlanPresets: vi.fn().mockResolvedValue([]),
    generateCandidate: vi.fn(),
  }
}

function createActivePlan() {
  return {
    planId: 'plan-1',
    activeVersion: {
      id: 'version-1',
      planId: 'plan-1',
      versionNumber: 1,
      sourceType: 'INITIAL' as const,
      plan: { ...originalPlan, locks: {} },
      ruleReference: { ruleVersion: 'r1', templateVersion: 't1', contentVersion: 'c1' },
      confirmedWarningCodes: [],
      createdAt: '2026-07-24T00:00:00Z',
    },
  }
}
