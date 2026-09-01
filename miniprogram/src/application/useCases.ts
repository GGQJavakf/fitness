import { ApplicationError } from './errors'
import type { AiPlanGenerator } from './cloudbaseAi'
import type {
  ActivePlanData,
  CandidateCommitRequest,
  PlanCandidateGenerationData,
  PlanValidationDraft,
  PlanExerciseOption,
  PlanExerciseReplacementOption,
  PlanDayOption,
  PlanPresetSummary,
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
  listPlanPresets(): Promise<readonly PlanPresetSummary[]>
  selectPlanPreset(presetCode: string): Promise<CandidateViewModel>
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

interface PendingCandidateCommit {
  editorSessionId: number
  candidateId: string
  request: CandidateCommitRequest
  idempotencyKey: string
  warningConfirmationToken?: string
}

interface CandidateCommitInFlight {
  editorSessionId: number
  candidateId: string
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
  let activePlanLoaded = false
  let pendingCandidateCommit: PendingCandidateCommit | null = null
  let candidateCommitInFlight: CandidateCommitInFlight | null = null

  function assertUserStateGeneration(expectedGeneration: number): void {
    if (userStateGeneration !== expectedGeneration) {
      throw new ApplicationError(
        'AUTHENTICATION_REQUIRED',
        '账号状态已更新，本次旧请求结果已失效',
      )
    }
  }

  async function awaitCurrentUserState<T>(
    expectedGeneration: number,
    operation: () => Promise<T>,
  ): Promise<T> {
    assertUserStateGeneration(expectedGeneration)
    try {
      const result = await operation()
      assertUserStateGeneration(expectedGeneration)
      return result
    } catch (error) {
      assertUserStateGeneration(expectedGeneration)
      throw error
    }
  }

  function requireEditor(): PlanEditorState {
    if (!editor) {
      throw new ApplicationError('RESOURCE_NOT_FOUND', '请先选择或加载一个计划')
    }
    return editor
  }

  function validationDraft(state: PlanEditorState): PlanValidationDraft {
    return {
      templateCode: state.workingCopy.templateCode,
      trainingSplit: state.workingCopy.trainingSplit,
      name: state.workingCopy.name,
      presetCode: state.workingCopy.presetCode,
      presetVersion: state.workingCopy.presetVersion,
      executionRules: state.workingCopy.executionRules
        ? [...state.workingCopy.executionRules]
        : undefined,
      progressionRules: state.workingCopy.progressionRules
        ? [...state.workingCopy.progressionRules]
        : undefined,
      movementImpactConstraint: state.workingCopy.movementImpactConstraint,
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
    const activationGeneration = userStateGeneration
    const candidateId = candidateData?.candidate?.candidateId
    if (!candidateId) {
      throw new ApplicationError('RESOURCE_NOT_FOUND', '候选计划不存在，请重新生成')
    }
    if (candidateCommitInFlight || pendingCandidateCommit) {
      throw new ApplicationError(
        'VALIDATION_FAILED',
        '候选编辑正在原子保存或等待确认，请勿同时直接激活',
      )
    }
    if (activePlan && activatedCandidateId === candidateId) return activePlan
    const pendingActivation = candidateActivations.get(candidateId)
    if (pendingActivation) {
      const activatedPlan = await awaitCurrentUserState(
        activationGeneration,
        () => pendingActivation,
      )
      assertCandidateStillCurrent(candidateId)
      return activatedPlan
    }

    const promise = planPort.createInitialPlan(candidateId)
    candidateActivations.set(candidateId, promise)
    try {
      const activatedPlan = await awaitCurrentUserState(activationGeneration, () => promise)
      assertCandidateStillCurrent(candidateId)
      activePlan = activatedPlan
      activePlanLoaded = true
      activatedCandidateId = candidateId
      pendingCandidateCommit = null
      editor = editorWithActivePlan(activatedPlan)
      editorSessionId += 1
      editorCandidateId = null
      return activatedPlan
    } finally {
      if (candidateActivations.get(candidateId) === promise) {
        candidateActivations.delete(candidateId)
      }
    }
  }

  function assertCandidateStillCurrent(expectedCandidateId: string): void {
    if (candidateData?.candidate?.candidateId !== expectedCandidateId) {
      throw new ApplicationError(
        'VALIDATION_FAILED',
        '推荐方案已更新，本次旧计划未激活',
      )
    }
  }

  return {
    clearUserState() {
      userStateGeneration += 1
      candidateData = null
      onboardingDraft = null
      activePlan = null
      activePlanLoaded = false
      pendingCandidateCommit = null
      candidateCommitInFlight = null
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
        const generated = await awaitCurrentUserState(
          completionGeneration,
          () => saveProfileAndGenerateCandidate(
            onboardingPort,
            submittedDraft,
            aiPlanGenerator,
            { assertCurrent: () => assertUserStateGeneration(completionGeneration) },
          ),
        )
        candidateData = generated
        pendingCandidateCommit = null
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

    listPlanPresets() {
      const generation = userStateGeneration
      return awaitCurrentUserState(generation, () => onboardingPort.listPlanPresets())
    },

    async selectPlanPreset(presetCode) {
      const generation = userStateGeneration
      const profileVersion = await awaitCurrentUserState(
        generation,
        () => onboardingPort.getProfileVersion(),
      )
      if (profileVersion === null) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '请先完成训练档案，再选择系统预设')
      }
      const generated = await awaitCurrentUserState(
        generation,
        () => onboardingPort.generateCandidate({
          profileVersion,
          presetCode,
          lockedFields: {},
          fallbackAllowed: false,
        }),
      )
      candidateData = generated
      activatedCandidateId = null
      editor = null
      pendingCandidateCommit = null
      editorSessionId += 1
      editorCandidateId = null
      return buildCandidateViewModel(generated)
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
      pendingCandidateCommit = null
      editorSessionId += 1
      editorCandidateId = candidate.candidateId
      return editor
    },

    async loadActivePlan() {
      const loadGeneration = userStateGeneration
      const loaded = await awaitCurrentUserState(loadGeneration, () => planPort.getActivePlan())
      activePlan = loaded
      activePlanLoaded = true
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
      pendingCandidateCommit = null
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
      const generation = userStateGeneration
      const state = requireEditor()
      const ruleReference = activePlan?.activeVersion.ruleReference
        ?? candidateData?.candidate?.ruleReference
      if (!ruleReference) throw new ApplicationError('RESOURCE_NOT_FOUND', '计划规则版本不存在')
      const result = await awaitCurrentUserState(
        generation,
        () => planPort.validatePlan(
          validationDraft(state),
          ruleReference,
        ),
      )
      editor = applyValidation(state, result)
      return editor
    },

    async saveEditor() {
      const generation = userStateGeneration
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
          await awaitCurrentUserState(
            generation,
            () => planPort.validatePlan(validationDraft(state), ruleReference),
          ),
        )
        assertEditorSession(savingEditorSessionId, savingCandidateId)
        editor = state
        buildSaveCommand(state)
        if (!state.planId) {
          if (!savingCandidateId) {
            throw new ApplicationError('RESOURCE_NOT_FOUND', '候选计划不存在，请重新生成')
          }
          if (candidateActivations.has(savingCandidateId) || candidateCommitInFlight) {
            throw new ApplicationError(
              'VALIDATION_FAILED',
              '候选计划正在激活或保存，请等待当前操作完成',
            )
          }
          const commitInFlight: CandidateCommitInFlight = {
            editorSessionId: savingEditorSessionId,
            candidateId: savingCandidateId,
          }
          candidateCommitInFlight = commitInFlight
          try {
            if (!activePlanLoaded) {
              const loaded = await awaitCurrentUserState(
                generation,
                () => planPort.getActivePlan(),
              )
              assertEditorSession(savingEditorSessionId, savingCandidateId)
              activePlan = loaded
              activePlanLoaded = true
            }

            const saveCommand = buildSaveCommand(state)
            const semanticRequest = snapshotCandidateCommitRequest({
              candidateId: savingCandidateId,
              expectedActiveVersionNumber: activePlan?.activeVersion.versionNumber ?? 0,
              plan: saveCommand.plan,
              locks: saveCommand.locks,
            })
            const semanticKey = candidateCommitIdempotencyKey(semanticRequest)
            const pendingForSession = pendingCandidateCommit
              && pendingCandidateCommit.editorSessionId === savingEditorSessionId
              && pendingCandidateCommit.candidateId === savingCandidateId
              && pendingCandidateCommit.request.expectedActiveVersionNumber
                === semanticRequest.expectedActiveVersionNumber
              ? pendingCandidateCommit
              : null
            const warningConfirmationToken = saveCommand.warningConfirmationToken
            const confirmsPendingWarning = Boolean(
              warningConfirmationToken
                && pendingForSession?.warningConfirmationToken === warningConfirmationToken,
            )
            const attempt = confirmsPendingWarning
                || pendingForSession?.idempotencyKey === semanticKey
              ? pendingForSession as PendingCandidateCommit
              : {
                  editorSessionId: savingEditorSessionId,
                  candidateId: savingCandidateId,
                  request: semanticRequest,
                  idempotencyKey: semanticKey,
                }
            pendingCandidateCommit = attempt
            const request = snapshotCandidateCommitRequest({
              ...attempt.request,
              ...(warningConfirmationToken ? { warningConfirmationToken } : {}),
            })
            const result = await awaitCurrentUserState(
              generation,
              () => planPort.commitCandidate(
                request,
                attempt.idempotencyKey,
              ),
            )
            assertEditorSession(savingEditorSessionId, savingCandidateId)
            if (result.status === 'WARNING_CONFIRMATION_REQUIRED'
                && result.warningConfirmationToken
                && pendingCandidateCommit === attempt) {
              pendingCandidateCommit = {
                ...attempt,
                warningConfirmationToken: result.warningConfirmationToken,
              }
            } else if (pendingCandidateCommit === attempt) {
              pendingCandidateCommit = null
            }
            const resultState = result.version
              ? { ...state, planId: result.version.planId }
              : state
            editor = applyVersionResult(resultState, result)
            if (result.version) {
              activePlan = {
                planId: result.version.planId,
                activeVersion: result.version,
              }
              activePlanLoaded = true
              activatedCandidateId = savingCandidateId
            }
            return editor
          } finally {
            if (candidateCommitInFlight === commitInFlight) {
              candidateCommitInFlight = null
            }
          }
        }
        const result = await awaitCurrentUserState(
          generation,
          () => planPort.createPlanVersion(state.planId, buildSaveCommand(state)),
        )
        assertEditorSession(savingEditorSessionId, savingCandidateId)
        editor = applyVersionResult(state, result)
        if (result.version) {
          activePlan = { planId: state.planId, activeVersion: result.version }
        }
        return editor
      } catch (error) {
        assertUserStateGeneration(generation)
        assertEditorSession(savingEditorSessionId, savingCandidateId)
        if (error instanceof ApplicationError && error.code === 'VERSION_CONFLICT') {
          if (pendingCandidateCommit?.editorSessionId === savingEditorSessionId
              && pendingCandidateCommit.candidateId === savingCandidateId) {
            pendingCandidateCommit = null
          }
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
      const generation = userStateGeneration
      const state = requireEditor()
      if (!state.planId) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '请先保存候选计划，再请求重新优化预览')
      }
      const command = buildSaveCommand({
        ...state,
        warningConfirmationToken: undefined,
        warningConfirmed: false,
      })
      const result = await awaitCurrentUserState(
        generation,
        () => planPort.previewRebalance(state.planId, command),
      )
      editor = applyRebalancePreview(state, result)
      return editor
    },

    async listPlanExerciseOptions(dayCode) {
      const generation = userStateGeneration
      const state = requireEditor()
      if (!state.planId || !planPort.listExerciseOptions) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '请先保存计划，再调整动作结构')
      }
      return awaitCurrentUserState(
        generation,
        () => planPort.listExerciseOptions!(state.planId, dayCode),
      )
    },

    async listPlanExerciseReplacements(dayCode, sourceExerciseCode) {
      const generation = userStateGeneration
      const state = requireEditor()
      if (!state.planId || !planPort.listPlanExerciseReplacements) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '请先保存计划，再替换动作')
      }
      return awaitCurrentUserState(
        generation,
        () => planPort.listPlanExerciseReplacements!(state.planId, dayCode, sourceExerciseCode),
      )
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
      const generation = userStateGeneration
      const state = requireEditor()
      if (!state.planId || !planPort.listDayOptions) {
        throw new ApplicationError('RESOURCE_NOT_FOUND', '请先保存计划，再调整训练日结构')
      }
      const currentCodes = new Set(state.workingCopy.days.map((day) => day.code))
      return (await awaitCurrentUserState(
        generation,
        () => planPort.listDayOptions!(state.planId),
      ))
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

function snapshotCandidateCommitRequest(
  request: CandidateCommitRequest,
): CandidateCommitRequest {
  return {
    candidateId: request.candidateId,
    expectedActiveVersionNumber: request.expectedActiveVersionNumber,
    plan: {
      ...request.plan,
      executionRules: request.plan.executionRules
        ? [...request.plan.executionRules]
        : undefined,
      progressionRules: request.plan.progressionRules
        ? [...request.plan.progressionRules]
        : undefined,
      days: request.plan.days.map((day) => ({
        ...day,
        exercises: day.exercises.map((exercise) => ({ ...exercise })),
      })),
    },
    locks: { ...request.locks },
    ...(request.warningConfirmationToken
      ? { warningConfirmationToken: request.warningConfirmationToken }
      : {}),
  }
}

function candidateCommitIdempotencyKey(request: CandidateCommitRequest): string {
  const semanticPayload = canonicalJson({
    candidateId: request.candidateId,
    expectedActiveVersionNumber: request.expectedActiveVersionNumber,
    plan: request.plan,
    locks: request.locks,
  })
  return `candidate-commit-${semanticHash(semanticPayload)}`
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') {
    return JSON.stringify(value) ?? 'null'
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalJson(item)).join(',')}]`
  }
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, item]) => item !== undefined)
    .sort(([left], [right]) => left === right ? 0 : left < right ? -1 : 1)
  return `{${entries
    .map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`)
    .join(',')}}`
}

function semanticHash(value: string): string {
  return [0x811c9dc5, 0x9e3779b9, 0x85ebca6b, 0xc2b2ae35]
    .map((seed) => {
      let hash = seed
      for (let index = 0; index < value.length; index += 1) {
        hash = Math.imul(hash ^ value.charCodeAt(index), 0x01000193)
      }
      hash ^= hash >>> 16
      return (hash >>> 0).toString(16).padStart(8, '0')
    })
    .join('')
}
