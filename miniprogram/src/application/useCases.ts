import { ApplicationError } from './errors'
import type { AiPlanGenerator } from './cloudbaseAi'
import type {
  ActivePlanData,
  PlanCandidateGenerationData,
  PlanValidationDraft,
  PlanExerciseOption,
  PlanExerciseReplacementOption,
  PlanDayOption,
} from './models'
import {
  addPlanDay as addDay,
  addPlanExercise as addExercise,
  applyRebalancePreview,
  applyPreSaveValidation,
  applyValidation,
  applyVersionResult,
  buildSaveCommand,
  changeNumericField,
  confirmWarnings,
  createPlanEditorState,
  markVersionConflict,
  movePlanExercise as moveExercise,
  movePlanDay as moveDay,
  removePlanExercise as removeExercise,
  removePlanDay as removeDay,
  replacePlanExercise as replaceExercise,
  setFieldLock,
  type EditableNumericField,
  type PlanEditorState,
} from './planEditor'
import type { PlanPersistencePort } from './ports'
import {
  buildCandidateViewModel,
  createOnboardingState,
  saveProfileAndGenerateCandidate,
  type CandidateViewModel,
  type OnboardingAdjustmentRoute,
  type OnboardingDraft,
  type OnboardingPersistencePort,
  type OnboardingState,
} from './onboarding'

export interface FitnessApplication {
  clearUserState(): void
  completeOnboarding(draft: OnboardingDraft): Promise<CandidateViewModel>
  resumeOnboarding(route?: OnboardingAdjustmentRoute): OnboardingState
  getCandidate(): CandidateViewModel | null
  activateCandidate(): Promise<ActivePlanData>
  openCandidateEditor(): PlanEditorState
  loadActivePlan(): Promise<ActivePlanData | null>
  getActivePlan(): ActivePlanData | null
  openPlanEditor(): PlanEditorState | null
  getPlanEditor(): PlanEditorState | null
  editPlanNumber(
    dayCode: string,
    exerciseCode: string,
    field: EditableNumericField,
    value: number,
  ): PlanEditorState
  setPlanFieldLock(fieldPath: string, status: 'USER_LOCKED' | 'UNLOCKED'): PlanEditorState
  validateEditor(): Promise<PlanEditorState>
  saveEditor(): Promise<PlanEditorState>
  confirmEditorWarnings(): PlanEditorState
  previewRebalance(): Promise<PlanEditorState>
  listPlanExerciseOptions(dayCode: string): Promise<readonly PlanExerciseOption[]>
  listPlanExerciseReplacements(
    dayCode: string,
    sourceExerciseCode: string,
  ): Promise<readonly PlanExerciseReplacementOption[]>
  addPlanExercise(dayCode: string, option: PlanExerciseOption): PlanEditorState
  removePlanExercise(dayCode: string, exerciseCode: string): PlanEditorState
  replacePlanExercise(dayCode: string, exerciseCode: string, option: PlanExerciseOption): PlanEditorState
  movePlanExercise(dayCode: string, exerciseCode: string, direction: -1 | 1): PlanEditorState
  listPlanDayOptions(): Promise<readonly PlanDayOption[]>
  addPlanDay(option: PlanDayOption): PlanEditorState
  removePlanDay(dayCode: string): PlanEditorState
  movePlanDay(dayCode: string, direction: -1 | 1): PlanEditorState
}

export function createFitnessApplication(
  onboardingPort: OnboardingPersistencePort,
  planPort: PlanPersistencePort,
  aiPlanGenerator?: AiPlanGenerator,
): FitnessApplication {
  let candidateData: PlanCandidateGenerationData | null = null
  let onboardingDraft: OnboardingDraft | null = null
  let activePlan: ActivePlanData | null = null
  let editor: PlanEditorState | null = null
  let activatedCandidateId: string | null = null
  const candidateActivations = new Map<string, Promise<ActivePlanData>>()
  let editorSessionId = 0
  let editorCandidateId: string | null = null
  let onboardingCompletion: Promise<CandidateViewModel> | null = null
  let pendingOnboardingRoute: OnboardingAdjustmentRoute | null = null
  let userStateGeneration = 0

  function requireEditor(): PlanEditorState {
    if (!editor) {
      throw new ApplicationError('RESOURCE_NOT_FOUND', '请先选择或加载一个计划')
    }
    return editor
  }

  function validationDraft(state: PlanEditorState): PlanValidationDraft {
    return {
      templateCode: state.workingCopy.templateCode,
      name: state.workingCopy.name,
      days: state.workingCopy.days.map((day) => ({
        ...day,
        exercises: day.exercises.map((exercise) => ({ ...exercise })),
      })),
    }
  }

  function editorWithActivePlan(plan: ActivePlanData): PlanEditorState {
    return createPlanEditorState({
      planId: plan.planId,
      baseVersion: plan.activeVersion.versionNumber,
      plan: plan.activeVersion.plan,
      validationResult: { valid: true, validationIssues: [] },
    })
  }

  function assertEditorSession(
    expectedSessionId: number,
    expectedCandidateId: string | null,
  ): void {
    const candidateStillCurrent = expectedCandidateId === null
      || candidateData?.candidate?.candidateId === expectedCandidateId
    if (editorSessionId !== expectedSessionId || !candidateStillCurrent) {
      throw new ApplicationError(
        'VALIDATION_FAILED',
        '推荐方案已更新，本次旧编辑未保存',
      )
    }
  }

  async function activateCurrentCandidate(): Promise<ActivePlanData> {
    const candidateId = candidateData?.candidate?.candidateId
    if (!candidateId) {
      throw new ApplicationError('RESOURCE_NOT_FOUND', '候选计划不存在，请重新生成')
    }
    if (activePlan && activatedCandidateId === candidateId) return activePlan
    const pendingActivation = candidateActivations.get(candidateId)
    if (pendingActivation) return pendingActivation

    const promise = planPort.createInitialPlan(candidateId)
    candidateActivations.set(candidateId, promise)
    try {
      const activatedPlan = await promise
      if (candidateData?.candidate?.candidateId === candidateId) {
        activePlan = activatedPlan
        activatedCandidateId = candidateId
        editor = editorWithActivePlan(activatedPlan)
      }
      return activatedPlan
    } finally {
      if (candidateActivations.get(candidateId) === promise) {
        candidateActivations.delete(candidateId)
      }
    }
  }

  return {
    clearUserState() {
      userStateGeneration += 1
      candidateData = null
      onboardingDraft = null
      activePlan = null
      editor = null
      activatedCandidateId = null
      candidateActivations.clear()
      editorSessionId += 1
      editorCandidateId = null
      onboardingCompletion = null
      pendingOnboardingRoute = null
    },

    async completeOnboarding(draft) {
      if (onboardingCompletion) return onboardingCompletion
      const completionGeneration = userStateGeneration
      const submittedDraft = cloneOnboardingDraft(draft)
      const submission = (async () => {
        onboardingDraft = { ...submittedDraft, aiConsentGranted: false }
        const generated = await saveProfileAndGenerateCandidate(
          onboardingPort,
          submittedDraft,
          aiPlanGenerator,
        )
        if (completionGeneration !== userStateGeneration) {
          throw new ApplicationError('AUTHENTICATION_REQUIRED', '账号已退出，本次建档结果未保留')
        }
        candidateData = generated
        editorSessionId += 1
        editorCandidateId = null
        return buildCandidateViewModel(candidateData)
      })()
      onboardingCompletion = submission
      try {
        return await submission
      } finally {
        if (onboardingCompletion === submission) onboardingCompletion = null
      }
    },

    resumeOnboarding(route) {
      const initial = createOnboardingState()
      if (!onboardingDraft) return initial
      if (route) pendingOnboardingRoute = route
      const effectiveRoute = route ?? pendingOnboardingRoute
      if (!route) pendingOnboardingRoute = null
      const step = effectiveRoute === 'ONBOARDING_SCHEDULE' ? 'SCHEDULE' : 'LOCATION_AND_EQUIPMENT'
      const stepIndex = step === 'SCHEDULE' ? 2 : 3
      return {
        ...initial,
        stepIndex,
        step,
        draft: cloneOnboardingDraft(onboardingDraft),
      }
    },

    getCandidate() {
      return candidateData ? buildCandidateViewModel(candidateData) : null
    },

    activateCandidate: activateCurrentCandidate,

    openCandidateEditor() {
      const candidate = candidateData?.candidate
      if (!candidate) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '候选计划不存在，请重新生成')
      }
      editor = createPlanEditorState({
        planId: '',
        baseVersion: 0,
        plan: candidate.plan,
        validationResult: {
          valid: !candidate.validationIssues.some((issue) => issue.severity === 'ERROR'),
          validationIssues: candidate.validationIssues,
        },
      })
      editor = {
        ...editor,
        lockedFieldOutcomes: { ...candidate.lockedFieldOutcomes },
      }
      editorSessionId += 1
      editorCandidateId = candidate.candidateId
      return editor
    },

    async loadActivePlan() {
      const loadGeneration = userStateGeneration
      const loaded = await planPort.getActivePlan()
      if (loadGeneration !== userStateGeneration) {
        throw new ApplicationError('AUTHENTICATION_REQUIRED', '账号已退出，本次计划结果未保留')
      }
      activePlan = loaded
      return activePlan
    },

    getActivePlan() {
      return activePlan
    },

    openPlanEditor() {
      if (!activePlan) {
        return null
      }
      editor = editorWithActivePlan(activePlan)
      editorSessionId += 1
      editorCandidateId = null
      return editor
    },

    getPlanEditor() {
      return editor
    },

    editPlanNumber(dayCode, exerciseCode, field, value) {
      editor = changeNumericField(requireEditor(), dayCode, exerciseCode, field, value)
      return editor
    },

    setPlanFieldLock(fieldPath, status) {
      editor = setFieldLock(requireEditor(), fieldPath, status)
      return editor
    },

    async validateEditor() {
      const state = requireEditor()
      const ruleReference = activePlan?.activeVersion.ruleReference
        ?? candidateData?.candidate?.ruleReference
      if (!ruleReference) throw new ApplicationError('RESOURCE_NOT_FOUND', '计划规则版本不存在')
      const result = await planPort.validatePlan(
        validationDraft(state),
        ruleReference,
      )
      editor = applyValidation(state, result)
      return editor
    },

    async saveEditor() {
      let state = requireEditor()
      const savingEditorSessionId = editorSessionId
      const savingCandidateId = editorCandidateId
      try {
        buildSaveCommand(state)
        const ruleReference = activePlan?.activeVersion.ruleReference
          ?? candidateData?.candidate?.ruleReference
        if (!ruleReference) {
          throw new ApplicationError('RESOURCE_NOT_FOUND', '计划规则版本不存在')
        }
        state = applyPreSaveValidation(
          state,
          await planPort.validatePlan(validationDraft(state), ruleReference),
        )
        assertEditorSession(savingEditorSessionId, savingCandidateId)
        editor = state
        buildSaveCommand(state)
        if (!state.planId) {
          if (!savingCandidateId) {
            throw new ApplicationError('RESOURCE_NOT_FOUND', '候选计划不存在，请重新生成')
          }
          const pendingWorkingCopy = state.workingCopy
          const pendingLockCommands = state.lockCommands
          const pendingValidation = state.validationResult
          const activatedPlan = await activateCurrentCandidate()
          assertEditorSession(savingEditorSessionId, savingCandidateId)
          activePlan = activatedPlan
          activatedCandidateId = savingCandidateId
          const baseEditor = editorWithActivePlan(activatedPlan)
          const hasPlanChanges = JSON.stringify(pendingWorkingCopy) !== JSON.stringify(activatedPlan.activeVersion.plan)
          const hasLockChanges = Object.keys(pendingLockCommands).length > 0
          if (!hasPlanChanges && !hasLockChanges) {
            editor = baseEditor
            return editor
          }
          state = {
            ...baseEditor,
            workingCopy: pendingWorkingCopy,
            lockCommands: pendingLockCommands,
            locks: { ...baseEditor.baseLocks, ...pendingLockCommands },
            validationResult: pendingValidation,
          }
          editor = state
        }
        const result = await planPort.createPlanVersion(state.planId, buildSaveCommand(state))
        assertEditorSession(savingEditorSessionId, savingCandidateId)
        editor = applyVersionResult(state, result)
        if (result.version) {
          activePlan = { planId: state.planId, activeVersion: result.version }
        }
        return editor
      } catch (error) {
        if (error instanceof ApplicationError && error.code === 'VERSION_CONFLICT') {
          editor = markVersionConflict(state, error.message)
          return editor
        }
        throw error
      }
    },

    confirmEditorWarnings() {
      editor = confirmWarnings(requireEditor())
      return editor
    },

    async previewRebalance() {
      const state = requireEditor()
      if (!state.planId) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '请先保存候选计划，再请求重新优化预览')
      }
      const command = buildSaveCommand({
        ...state,
        warningConfirmationToken: undefined,
        warningConfirmed: false,
      })
      const result = await planPort.previewRebalance(state.planId, command)
      editor = applyRebalancePreview(state, result)
      return editor
    },

    async listPlanExerciseOptions(dayCode) {
      const state = requireEditor()
      if (!state.planId || !planPort.listExerciseOptions) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '请先保存计划，再调整动作结构')
      }
      return planPort.listExerciseOptions(state.planId, dayCode)
    },

    async listPlanExerciseReplacements(dayCode, sourceExerciseCode) {
      const state = requireEditor()
      if (!state.planId || !planPort.listPlanExerciseReplacements) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '请先保存计划，再替换动作')
      }
      return planPort.listPlanExerciseReplacements(state.planId, dayCode, sourceExerciseCode)
    },

    addPlanExercise(dayCode, option) {
      editor = addExercise(requireEditor(), dayCode, option)
      return editor
    },

    removePlanExercise(dayCode, exerciseCode) {
      editor = removeExercise(requireEditor(), dayCode, exerciseCode)
      return editor
    },

    replacePlanExercise(dayCode, exerciseCode, option) {
      editor = replaceExercise(requireEditor(), dayCode, exerciseCode, option)
      return editor
    },

    movePlanExercise(dayCode, exerciseCode, direction) {
      editor = moveExercise(requireEditor(), dayCode, exerciseCode, direction)
      return editor
    },

    async listPlanDayOptions() {
      const state = requireEditor()
      if (!state.planId || !planPort.listDayOptions) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '请先保存计划，再调整训练日结构')
      }
      const currentCodes = new Set(state.workingCopy.days.map((day) => day.code))
      return (await planPort.listDayOptions(state.planId))
        .filter((option) => !currentCodes.has(option.code))
    },

    addPlanDay(option) {
      editor = addDay(requireEditor(), option)
      return editor
    },

    removePlanDay(dayCode) {
      editor = removeDay(requireEditor(), dayCode)
      return editor
    },

    movePlanDay(dayCode, direction) {
      editor = moveDay(requireEditor(), dayCode, direction)
      return editor
    },
  }
}

function cloneOnboardingDraft(draft: OnboardingDraft): OnboardingDraft {
  return {
    ...draft,
    equipment: draft.equipment.map((item) => ({
      ...item,
      minIncrement: { ...item.minIncrement },
      availableLevels: item.availableLevels.map((level) => ({ ...level })),
    })),
    preferences: draft.preferences.map((preference) => ({ ...preference })),
  }
}
