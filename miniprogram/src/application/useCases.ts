import { ApplicationError } from './errors'
import type {
  ActivePlanData,
  PlanCandidateGenerationData,
  PlanValidationDraft,
} from './models'
import {
  applyRebalancePreview,
  applyValidation,
  applyVersionResult,
  buildSaveCommand,
  changeNumericField,
  confirmWarnings,
  createPlanEditorState,
  markVersionConflict,
  setFieldLock,
  type EditableNumericField,
  type PlanEditorState,
} from './planEditor'
import type { PlanPersistencePort } from './ports'
import {
  buildCandidateViewModel,
  saveProfileAndGenerateCandidate,
  type CandidateViewModel,
  type OnboardingDraft,
  type OnboardingPersistencePort,
} from './onboarding'

export interface FitnessApplication {
  completeOnboarding(draft: OnboardingDraft): Promise<CandidateViewModel>
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
}

export function createFitnessApplication(
  onboardingPort: OnboardingPersistencePort,
  planPort: PlanPersistencePort,
): FitnessApplication {
  let candidateData: PlanCandidateGenerationData | null = null
  let activePlan: ActivePlanData | null = null
  let editor: PlanEditorState | null = null

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

  return {
    async completeOnboarding(draft) {
      candidateData = await saveProfileAndGenerateCandidate(onboardingPort, draft)
      return buildCandidateViewModel(candidateData)
    },

    getCandidate() {
      return candidateData ? buildCandidateViewModel(candidateData) : null
    },

    async activateCandidate() {
      const candidateId = candidateData?.candidate?.candidateId
      if (!candidateId) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '候选计划不存在，请重新生成')
      }
      activePlan = await planPort.createInitialPlan(candidateId)
      editor = editorWithActivePlan(activePlan)
      return activePlan
    },

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
      return editor
    },

    async loadActivePlan() {
      activePlan = await planPort.getActivePlan()
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
      try {
        buildSaveCommand(state)
        if (!state.planId) {
          const candidateId = candidateData?.candidate?.candidateId
          if (!candidateId) {
            throw new ApplicationError('RESOURCE_NOT_FOUND', '候选计划不存在，请重新生成')
          }
          const pendingWorkingCopy = state.workingCopy
          const pendingLocks = state.locks
          const pendingValidation = state.validationResult
          activePlan = await planPort.createInitialPlan(candidateId)
          const baseEditor = editorWithActivePlan(activePlan)
          const hasPlanChanges = JSON.stringify(pendingWorkingCopy) !== JSON.stringify(activePlan.activeVersion.plan)
          const hasLockChanges = Object.entries(pendingLocks).some(
            ([path, status]) => activePlan?.activeVersion.plan.locks[path] !== status,
          )
          if (!hasPlanChanges && !hasLockChanges) {
            editor = baseEditor
            return editor
          }
          state = {
            ...baseEditor,
            workingCopy: pendingWorkingCopy,
            locks: pendingLocks,
            validationResult: pendingValidation,
          }
          editor = state
        }
        const result = await planPort.createPlanVersion(state.planId, buildSaveCommand(state))
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
  }
}
