import { adjustRestTimer, resumeRestTimer, skipRestTimer, startRestTimer } from '../../domain/workout/RestTimer'
import {
  acknowledgeOperation,
  abandonOperation,
  abandonBlockedOperations as abandonBlockedQueueOperations,
  abandonUnresolvedOperations,
  enqueueOperation,
  hasBlockingOperations,
  markOperationConflict,
  markOperationRejected,
  rememberConflictResolution as rememberQueueConflictResolution,
  rebuildRejectedOperations,
} from '../../domain/sync/OperationQueue'
import type { Clock } from '../ports/Clock'
import type {
  WorkoutConflictResolutionIntent,
  WorkoutConflictResolutionResult,
} from '../ports/WorkoutConflictResolutionPort'
import type { WorkoutDraft, WorkoutDraftStore } from '../ports/WorkoutDraftStore'
import { WorkoutDraftRecoveryRequiredError } from '../ports/WorkoutDraftStore'
import type { WorkoutOperationSyncPort } from '../ports/WorkoutOperationSyncPort'
import type { WorkoutCompletionPort, WorkoutCompletionResult, WorkoutCompletionType } from '../ports/WorkoutCompletionPort'
import type { ExerciseReplacementCandidate, WorkoutReplacementPort } from '../ports/WorkoutReplacementPort'
import type {
  RecoverableActiveWorkout,
  StartWorkoutSessionRequest,
  WorkoutSessionStartPort,
} from '../ports/WorkoutSessionStartPort'
import { ActiveWorkoutExistsError } from '../errors'
import {
  createWorkoutFlow,
  areRequiredWorkSetsComplete,
  beginWorkSets,
  chooseOptionalSet,
  completeGeneralWarmup,
  isWorkoutPrescriptionFinished,
  recordWorkoutSet,
  restSecondsAfterRecordedSet,
  replaceExerciseForSession,
  restoreWorkoutFlow,
  setWorkoutExerciseWeight,
  workoutSafetyNotice,
  type RecordWorkoutSetInput,
  type WorkoutExerciseSnapshot,
  type WorkoutFlowState,
  type WorkoutRir,
} from '../workoutFlow'
import { restoreFlowFromDraft, toWorkoutDraft } from '../workoutFlowDraftMapper'

export interface StartWorkoutFlowInput {
  clientSessionKey: string
  planVersionId: string
  exercises: readonly WorkoutExerciseSnapshot[]
  warmupDurationSeconds?: 180 | 300 | 480
  warmupPrescription?: import('../workoutFlow').WorkoutWarmupPrescriptionSnapshot
  serverSessionId?: string
  serverVersion?: number
  startedAtUtc?: string
}

export type StartOrResumeWorkoutInput = StartWorkoutSessionRequest & {
  warmupDurationSeconds?: 180 | 300 | 480
  activeDraftDecision?: 'RESUME'
}

export type StartOrResumeWorkoutResult =
  | { kind: 'STARTED'; state: WorkoutFlowState }
  | { kind: 'RESUMED'; state: WorkoutFlowState }
  | { kind: 'RESUME_REQUIRED'; state: WorkoutFlowState }

export class WorkoutFlowService {
  private readonly commandTails = new Map<string, Promise<void>>()

  constructor(
    private readonly drafts: WorkoutDraftStore,
    private readonly clock: Clock,
    private readonly remote?: WorkoutOperationSyncPort,
    private readonly completion?: WorkoutCompletionPort,
    private readonly replacements?: WorkoutReplacementPort,
    private readonly starter?: WorkoutSessionStartPort,
  ) {}

  async start(input: StartWorkoutFlowInput): Promise<WorkoutFlowState> {
    return this.serialize('__active-workout__', async () => {
      const existing = await this.drafts.loadActive()
      if (existing) {
        if (existing.clientSessionKey === input.clientSessionKey) return restoreFlowFromDraft(existing)
        throw new Error('an unfinished workout must be resumed or explicitly abandoned')
      }
      return this.startNew(input)
    })
  }

  async startOrResume(input: StartOrResumeWorkoutInput): Promise<StartOrResumeWorkoutResult> {
    return this.serialize('__active-workout__', async () => {
      const existing = await this.drafts.loadActive()
      if (existing) {
        const state = restoreFlowFromDraft(existing)
        return input.activeDraftDecision === 'RESUME'
          ? { kind: 'RESUMED', state }
          : { kind: 'RESUME_REQUIRED', state }
      }
      if (!this.starter) throw new Error('workout session start is unavailable')
      let session
      try {
        session = await this.starter.startWorkoutSession({
          clientSessionKey: input.clientSessionKey,
          planId: input.planId,
          planVersionNo: input.planVersionNo,
          planDayId: input.planDayId,
          recoveryConfirmationToken: input.recoveryConfirmationToken,
        })
      } catch (error) {
        if (error instanceof ActiveWorkoutExistsError) {
          const active = ['CREATED', 'PAUSED'].includes(error.activeWorkout.session.status)
            ? await this.activateRecoveredWorkout(error.activeWorkout)
            : error.activeWorkout
          const state = await this.restoreActiveWorkout(active)
          return { kind: 'RESUME_REQUIRED', state }
        }
        throw error
      }
      const state = await this.startNew({
        clientSessionKey: input.clientSessionKey,
        planVersionId: session.planVersionId,
        serverSessionId: session.id,
        serverVersion: session.version,
        startedAtUtc: session.startedAt ?? this.clock.nowUtc(),
        warmupPrescription: session.warmupPrescription,
        exercises: session.exercises.map((exercise) => ({
          snapshotExerciseKey: exercise.id,
          exerciseCode: exercise.exerciseCode,
          name: exercise.exerciseName,
          targetWorkSets: exercise.prescription.workSets,
          targetRepMin: exercise.prescription.repMin,
          targetRepMax: exercise.prescription.repMax,
          restSeconds: exercise.prescription.restSeconds,
          weightStatus: exercise.prescription.weightStatus,
          targetWeightKg: exercise.prescription.targetWeightKg,
          targetRirMin: exercise.prescription.targetRirMin,
          targetRirMax: exercise.prescription.targetRirMax,
          eccentricSeconds: exercise.prescription.eccentricSeconds,
          perSide: exercise.prescription.perSide,
          executionGroup: exercise.prescription.executionGroup,
          executionOrder: exercise.prescription.executionOrder,
          optionalSetRule: exercise.prescription.optionalSetRule,
        })),
      })
      return { kind: 'STARTED', state }
    })
  }

  private async activateRecoveredWorkout(
    active: RecoverableActiveWorkout,
  ): Promise<RecoverableActiveWorkout> {
    if (!this.starter?.activateWorkoutSession) {
      throw new Error('active workout recovery cannot activate the server session')
    }
    const activated = await this.starter.activateWorkoutSession(
      active.session.id,
      active.session.version,
    )
    if (!('status' in activated) || activated.status !== 'IN_PROGRESS') {
      throw new Error('active workout recovery did not enter progress')
    }
    return {
      ...active,
      session: {
        ...active.session,
        ...activated,
        clientSessionKey: active.session.clientSessionKey,
        status: 'IN_PROGRESS',
      },
    }
  }

  private async restoreActiveWorkout(active: RecoverableActiveWorkout): Promise<WorkoutFlowState> {
    const session = active.session
    const base = createWorkoutFlow({
      clientSessionKey: session.clientSessionKey,
      planVersionId: session.planVersionId,
      startedAtUtc: session.startedAt ?? this.clock.nowUtc(),
      warmupPrescription: session.warmupPrescription,
      exercises: session.exercises.map((exercise) => ({
        snapshotExerciseKey: exercise.id,
        exerciseCode: exercise.exerciseCode,
        name: exercise.exerciseName,
        targetWorkSets: exercise.prescription.workSets,
        targetRepMin: exercise.prescription.repMin,
        targetRepMax: exercise.prescription.repMax,
        restSeconds: exercise.prescription.restSeconds,
        weightStatus: exercise.prescription.weightStatus,
        targetWeightKg: exercise.prescription.targetWeightKg,
        targetRirMin: exercise.prescription.targetRirMin,
        targetRirMax: exercise.prescription.targetRirMax,
        eccentricSeconds: exercise.prescription.eccentricSeconds,
        perSide: exercise.prescription.perSide,
        executionGroup: exercise.prescription.executionGroup,
        executionOrder: exercise.prescription.executionOrder,
        optionalSetRule: exercise.prescription.optionalSetRule,
      })),
    })
    const effectiveSets = active.sets
      .slice()
      .sort((left, right) => left.setOrder - right.setOrder
        || left.clientOperationSeq - right.clientOperationSeq)
    const exercises = base.exercises.map((exercise) => {
      const facts = effectiveSets.filter((set) => set.sessionExerciseId === exercise.snapshotExerciseKey)
      const latestWorkWeight = facts.filter((set) => set.setType !== 'WARMUP'
        && set.completionStatus !== 'SKIPPED' && set.actual.weight.value > 0).at(-1)?.actual.weight.value
      return {
        ...exercise,
        ...(exercise.weightStatus !== 'BODYWEIGHT' && latestWorkWeight
          ? { sessionWeightKg: latestWorkWeight }
          : {}),
        sets: facts.map((set) => ({
          clientSetKey: set.clientSetKey,
          setType: set.setType,
          status: set.completionStatus as 'COMPLETED' | 'FAILED' | 'SKIPPED',
          actualWeightKg: set.actual.weight.value,
          actualReps: set.actual.reps,
          rir: remainingRepsToRir(set.remainingReps),
          safetyFlag: set.safetyFlag ?? null,
          discomfort: set.safetyFlag === 'PAIN' ? 'PAIN' as const : 'NONE' as const,
        })),
      }
    })
    const rampCount = effectiveSets.filter((set) => set.setType === 'WARMUP').length
    const hasWorkFact = effectiveSets.some((set) => set.setType !== 'WARMUP')
    const phase = hasWorkFact
      ? 'WORK' as const
      : rampCount === 0
        ? 'GENERAL' as const
        : base.warmup.rampExerciseIndex !== null && rampCount < base.warmup.maximumRampSets
          ? 'RAMP' as const
          : 'WORK' as const
    const unsafe = effectiveSets.some((set) => set.safetyFlag != null
      || set.anomalyStatus === 'CONFIRMED_EXCLUDED')
    const generalTimer = phase === 'GENERAL'
      ? startRestTimer({
          sourceSetKey: `${session.clientSessionKey}-general-warmup`,
          configuredDurationSeconds: base.warmup.generalDurationSeconds,
          nowUtc: this.clock.nowUtc(),
        })
      : null
    const restored = restoreWorkoutFlow({
      ...base,
      exercises,
      restTimer: null,
      warmup: { ...base.warmup, phase, generalTimer },
      syncStatus: 'SYNCED',
      automaticProgressionEligible: !unsafe,
      safetyNotice: unsafe ? workoutSafetyNotice(
        effectiveSets.find((set) => set.safetyFlag)?.safetyFlag ?? null,
      ) : null,
    })
    const draft = toWorkoutDraft(restored, null, this.clock.nowUtc())
    await this.drafts.save({
      ...draft,
      sessionId: session.id,
      lastServerVersion: session.version,
    }, null)
    return restored
  }

  private async startNew(input: StartWorkoutFlowInput): Promise<WorkoutFlowState> {
    const created = createWorkoutFlow({
      ...input,
      startedAtUtc: input.startedAtUtc ?? this.clock.nowUtc(),
    })
    const state = {
      ...created,
      warmup: {
        ...created.warmup,
        generalTimer: startRestTimer({
          sourceSetKey: `${input.clientSessionKey}-general-warmup`,
          configuredDurationSeconds: created.warmup.generalDurationSeconds,
          nowUtc: this.clock.nowUtc(),
        }),
      },
    }
    const draft = toWorkoutDraft(state, null, this.clock.nowUtc())
    await this.drafts.save({
      ...draft,
      sessionId: input.serverSessionId ?? null,
      lastServerVersion: input.serverVersion ?? 0,
    }, null)
    return state
  }

  async load(): Promise<WorkoutFlowState | null> {
    const draft = await this.drafts.loadActive()
    return draft ? restoreFlowFromDraft(draft) : null
  }

  async loadStatus(): Promise<
    | { kind: 'NONE' }
    | { kind: 'ACTIVE'; state: WorkoutFlowState }
    | { kind: 'RECOVERY_REQUIRED' }
  > {
    try {
      const state = await this.load()
      return state ? { kind: 'ACTIVE', state } : { kind: 'NONE' }
    } catch (error) {
      if (error instanceof WorkoutDraftRecoveryRequiredError) return { kind: 'RECOVERY_REQUIRED' }
      throw error
    }
  }

  async recordSet(state: WorkoutFlowState, input: RecordWorkoutSetInput): Promise<WorkoutFlowState> {
    return this.serialize(state.clientSessionKey, async () => {
      const { draft: previous, state: current } = await this.requireLatest(state.clientSessionKey)
      let updated = recordWorkoutSet(current, input)
      if (updated === current) return current
      const prescriptionFinished = isWorkoutPrescriptionFinished(updated)
      const requiredWorkFinished = areRequiredWorkSetsComplete(updated)
      if (prescriptionFinished || requiredWorkFinished) {
        updated = { ...updated, restTimer: null }
      } else if (input.setType === 'WORK' && input.status === 'COMPLETED') {
        const exercise = updated.exercises[input.exerciseIndex]
        const restSeconds = restSecondsAfterRecordedSet(updated, input.exerciseIndex)
        updated = restSeconds === null
          ? { ...updated, restTimer: null }
          : {
              ...updated,
              restTimer: startRestTimer({
                sourceSetKey: input.clientSetKey,
                configuredDurationSeconds: restSeconds ?? exercise.restSeconds,
                nowUtc: this.clock.nowUtc(),
              }),
            }
      }
      if (!previous.sessionId) {
        await this.persist(updated, previous)
        return updated
      }
      updated = {
        ...updated,
        syncStatus: current.syncStatus === 'CONFLICT' || current.syncStatus === 'SYNC_REJECTED'
          ? current.syncStatus
          : 'OFFLINE_PENDING',
      }
      const mapped = toWorkoutDraft(updated, previous, this.clock.nowUtc())
      const record = updated.exercises[input.exerciseIndex].sets
        .find((set) => set.clientSetKey === input.clientSetKey)!
      const pendingBefore = previous.queue.operations.filter((operation) => operation.status === 'PENDING').length
      const queued = enqueueOperation(mapped.queue, {
        idempotencyKey: input.clientSetKey,
        type: 'UPSERT_SET',
        createdAtUtc: this.clock.nowUtc(),
        payload: {
          sessionId: previous.sessionId,
          sessionExerciseId: updated.exercises[input.exerciseIndex].snapshotExerciseKey,
          setType: record.setType,
          setOrder: input.setType === 'WARMUP'
            ? updated.exercises[input.exerciseIndex].sets.filter((set) => set.setType === 'WARMUP').length
            : current.currentSetIndex + 1,
          target: { weight: { value: record.actualWeightKg ?? 0, unit: 'KG' }, reps: input.setType === 'WARMUP' ? (record.actualReps ?? 0) : updated.exercises[input.exerciseIndex].targetRepMax },
          actual: { weight: { value: record.actualWeightKg ?? 0, unit: 'KG' }, reps: record.actualReps ?? 0 },
          remainingReps: toRemainingReps(record.rir),
          completionStatus: record.status,
          completedAt: record.status === 'COMPLETED' ? this.clock.nowUtc() : undefined,
          safetyFlag: record.safetyFlag ?? undefined,
          expectedSessionVersion: previous.lastServerVersion + pendingBefore,
          confirmAnomaly: false,
        },
      })
      await this.drafts.save({ ...mapped, queue: queued.queue }, previous.revision)
      return updated
    })
  }

  async resume(state: WorkoutFlowState): Promise<{
    state: WorkoutFlowState
    remainingSeconds: number
    warmupRemainingSeconds: number
    clockRollbackDetected: boolean
    syncFailed: boolean
  }> {
    return this.serialize(state.clientSessionKey, async () => {
      const { draft, state: current } = await this.requireLatest(state.clientSessionKey)
      const nowUtc = this.clock.nowUtc()
      const rest = current.restTimer ? resumeRestTimer(current.restTimer, nowUtc) : null
      const general = current.warmup.generalTimer ? resumeRestTimer(current.warmup.generalTimer, nowUtc) : null
      const updated = {
        ...current,
        restTimer: rest?.timer ?? current.restTimer,
        warmup: { ...current.warmup, generalTimer: general?.timer ?? current.warmup.generalTimer },
      }
      await this.persist(updated, draft)
      let recovered = updated
      let syncFailed = false
      try {
        recovered = await this.flushUnlocked(updated)
      } catch {
        syncFailed = true
      }
      return {
        state: recovered,
        remainingSeconds: rest?.remainingSeconds ?? 0,
        warmupRemainingSeconds: general?.remainingSeconds ?? 0,
        clockRollbackDetected: Boolean(rest?.clockRollbackDetected || general?.clockRollbackDetected),
        syncFailed,
      }
    })
  }

  async completeGeneralWarmup(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    return this.mutate(state, completeGeneralWarmup)
  }

  async beginWorkSets(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    return this.mutate(state, beginWorkSets)
  }

  async setExerciseWeight(
    state: WorkoutFlowState,
    exerciseIndex: number,
    weightKg: number,
  ): Promise<WorkoutFlowState> {
    return this.mutate(state, (current) => setWorkoutExerciseWeight(current, exerciseIndex, weightKg))
  }

  async chooseOptionalSet(
    state: WorkoutFlowState,
    choiceGroup: string,
    exerciseIndex: number | null,
  ): Promise<WorkoutFlowState> {
    return this.mutate(state, (current) => chooseOptionalSet(
      current,
      choiceGroup,
      exerciseIndex,
      this.clock.nowUtc(),
    ))
  }

  async adjustRest(state: WorkoutFlowState, seconds: 15 | -15): Promise<WorkoutFlowState> {
    return this.mutate(state, (current) => current.restTimer
      ? { ...current, restTimer: adjustRestTimer(current.restTimer, seconds, this.clock.nowUtc()) }
      : current)
  }

  async skipRest(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    return this.mutate(state, (current) => current.restTimer
      ? { ...current, restTimer: skipRestTimer(current.restTimer, this.clock.nowUtc()) }
      : current)
  }

  async finishRest(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    return this.mutate(state, (current) => {
      if (!current.restTimer) return current
      const resumed = resumeRestTimer(current.restTimer, this.clock.nowUtc())
      return resumed.timer.timerStatus === 'RUNNING' ? current : { ...current, restTimer: resumed.timer }
    })
  }

  async flush(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    return this.serialize(state.clientSessionKey, () => this.flushUnlocked(state))
  }

  private async flushUnlocked(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    if (!this.remote) return state
    const draft = await this.drafts.loadActive()
    if (!draft || draft.clientSessionKey !== state.clientSessionKey) return state
    const pending = draft.queue.operations.filter((operation) => operation.status === 'PENDING' && operation.type === 'UPSERT_SET')
    if (pending.length === 0) return restoreFlowFromDraft(draft)
    const results = await this.remote.syncWorkoutOperations(pending.map((operation) => ({
      clientOperationSeq: operation.clientOperationSeq,
      operationType: 'UPSERT_SET' as const,
      clientKey: operation.idempotencyKey,
      payload: operation.payload as Readonly<Record<string, unknown>>,
    })))
    const latest = await this.drafts.loadActive()
    if (!latest || latest.clientSessionKey !== state.clientSessionKey) return state
    const persisted = restoreFlowFromDraft(latest)
    let queue = latest.queue
    let serverVersion = latest.lastServerVersion
    let syncStatus: WorkoutFlowState['syncStatus'] = 'SYNCED'
    for (const result of results) {
      const operation = pending.find((item) => item.clientOperationSeq === result.clientOperationSeq)
      if (!operation) continue
      if (result.status === 'CONFLICT') {
        if (!result.conflictId) throw new Error('sync conflict response is missing conflict identity')
        queue = markOperationConflict(
          queue,
          result.clientOperationSeq,
          result.conflictId,
          result.reasonCode,
        )
        syncStatus = 'CONFLICT'
      }
      if (result.status === 'REJECTED') {
        queue = markOperationRejected(queue, result.clientOperationSeq, result.reasonCode)
        syncStatus = 'SYNC_REJECTED'
      }
      if (result.status === 'APPLIED' || result.status === 'DUPLICATE') {
        queue = acknowledgeOperation(queue, result.clientOperationSeq)
        const expected = Number((operation.payload as { expectedSessionVersion?: unknown }).expectedSessionVersion)
        if (Number.isSafeInteger(expected)) serverVersion = Math.max(serverVersion, expected + 1)
      }
    }
    if (queue.operations.some((operation) => operation.status === 'CONFLICT')) {
      syncStatus = 'CONFLICT'
    } else if (queue.operations.some((operation) => operation.status === 'REJECTED')) {
      syncStatus = 'SYNC_REJECTED'
    } else if (queue.operations.some((operation) => operation.status === 'PENDING') && syncStatus === 'SYNCED') {
      syncStatus = 'OFFLINE_PENDING'
    }
    const updated = { ...persisted, syncStatus }
    const mapped = toWorkoutDraft(updated, latest, this.clock.nowUtc())
    await this.drafts.save({ ...mapped, queue, lastServerVersion: serverVersion }, latest.revision)
    return updated
  }

  async abandonBlockedOperations(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    return this.serialize(state.clientSessionKey, async () => {
      const { draft, state: current } = await this.requireLatest(state.clientSessionKey)
      const queue = abandonBlockedQueueOperations(draft.queue)
      const updated: WorkoutFlowState = {
        ...current,
        syncStatus: queue.operations.some((operation) => operation.status === 'PENDING')
          ? 'OFFLINE_PENDING'
          : 'SYNCED',
      }
      const mapped = toWorkoutDraft(updated, draft, this.clock.nowUtc())
      await this.drafts.save({ ...mapped, queue }, draft.revision)
      return updated
    })
  }

  async retryRejectedOperations(state: WorkoutFlowState): Promise<WorkoutFlowState> {
    return this.serialize(state.clientSessionKey, async () => {
      const { draft, state: current } = await this.requireLatest(state.clientSessionKey)
      const queue = rebuildRejectedOperations(draft.queue, this.clock.nowUtc())
      const updated: WorkoutFlowState = { ...current, syncStatus: 'OFFLINE_PENDING' }
      const mapped = toWorkoutDraft(updated, draft, this.clock.nowUtc())
      await this.drafts.save({ ...mapped, queue }, draft.revision)
      return updated
    })
  }

  async convergeConflict(result: WorkoutConflictResolutionResult): Promise<WorkoutFlowState | null> {
    const active = await this.drafts.loadActive()
    if (!active) return null
    return this.serialize(active.clientSessionKey, async () => {
      const { draft, state } = await this.requireLatest(active.clientSessionKey)
      const operation = draft.queue.operations.find((item) => (
        item.status === 'CONFLICT'
        && item.conflictId === result.conflictId
        && item.clientOperationSeq === result.clientOperationSeq
        && item.idempotencyKey === result.clientKey
      ))
      if (!operation) return state
      if (!Number.isSafeInteger(result.authoritativeSessionVersion)
        || result.authoritativeSessionVersion < draft.lastServerVersion) {
        throw new Error('conflict resolution session version is invalid')
      }
      if (result.outcome === 'REBUILT') {
        throw new Error('rebuilt conflict operations are not supported by this client version')
      }
      const expectedOutcome = result.resolution === 'KEEP_LOCAL' ? 'ACKNOWLEDGED' : 'ABANDONED'
      if (result.outcome !== expectedOutcome) {
        throw new Error('conflict resolution outcome does not match the selected decision')
      }
      const intent = operation.conflictResolutionIntent
      if (intent && intent.resolution !== result.resolution) {
        throw new Error('conflict resolution does not match the durable local decision')
      }

      const queue = result.outcome === 'ACKNOWLEDGED'
        ? acknowledgeOperation(draft.queue, operation.clientOperationSeq)
        : abandonOperation(draft.queue, operation.clientOperationSeq)
      if (!draft.sessionId) throw new Error('conflict resolution has no active server session')
      const authoritative = applyConflictAuthority(state, result, draft.sessionId)
      const updated: WorkoutFlowState = {
        ...authoritative,
        syncStatus: queue.operations.some((item) => item.status === 'CONFLICT')
          ? 'CONFLICT'
          : queue.operations.some((item) => item.status === 'REJECTED')
            ? 'SYNC_REJECTED'
            : queue.operations.some((item) => item.status === 'PENDING')
              ? 'OFFLINE_PENDING'
              : 'SYNCED',
      }
      const mapped = toWorkoutDraft(updated, draft, this.clock.nowUtc())
      await this.drafts.save({
        ...mapped,
        queue,
        lastServerVersion: result.authoritativeSessionVersion,
      }, draft.revision)
      return updated
    })
  }

  async rememberConflictResolution(intent: WorkoutConflictResolutionIntent): Promise<boolean> {
    const active = await this.drafts.loadActive()
    if (!active) return false
    return this.serialize(active.clientSessionKey, async () => {
      const draft = await this.drafts.loadActive()
      if (!draft || draft.clientSessionKey !== active.clientSessionKey) return false
      const queue = rememberQueueConflictResolution(draft.queue, intent)
      if (JSON.stringify(queue) === JSON.stringify(draft.queue)) return false
      await this.drafts.save({ ...draft, queue, revision: draft.revision + 1 }, draft.revision)
      return true
    })
  }

  async pendingConflictResolutions(): Promise<readonly WorkoutConflictResolutionIntent[]> {
    const active = await this.drafts.loadActive()
    if (!active) return []
    return active.queue.operations.flatMap((operation) => {
      const intent = operation.conflictResolutionIntent
      if (operation.status !== 'CONFLICT' || !operation.conflictId || !intent) return []
      return [{
        conflictId: operation.conflictId,
        clientKey: operation.idempotencyKey,
        resolution: intent.resolution,
        expectedConflictVersion: intent.expectedConflictVersion,
      }]
    })
  }

  async abandonActive(state: WorkoutFlowState): Promise<void> {
    await this.serialize(state.clientSessionKey, async () => {
      const { draft, state: current } = await this.requireLatest(state.clientSessionKey)
      const queue = abandonUnresolvedOperations(draft.queue)
      const abandoned = { ...current, syncStatus: 'SYNCED' as const }
      const mapped = toWorkoutDraft(abandoned, draft, this.clock.nowUtc())
      const persisted = { ...mapped, queue }
      await this.drafts.save(persisted, draft.revision)
      if (draft.sessionId) {
        if (!this.completion) throw new Error('workout completion is unavailable')
        await this.completion.completeWorkout(
          draft.sessionId,
          { expectedVersion: draft.lastServerVersion, completionType: 'EARLY_END' },
          `${state.clientSessionKey}-complete-EARLY_END`,
        )
      }
      await this.drafts.clearActive(draft.draftId)
    })
  }

  async discardOrphanedLocalWorkout(state: WorkoutFlowState): Promise<void> {
    await this.serialize(state.clientSessionKey, async () => {
      const draft = await this.drafts.loadActive()
      if (!draft || draft.clientSessionKey !== state.clientSessionKey) {
        throw new Error('the active workout changed before local discard')
      }
      await this.drafts.clearActive(draft.draftId)
    })
  }

  async complete(state: WorkoutFlowState, completionType: WorkoutCompletionType): Promise<WorkoutCompletionResult> {
    return this.serialize(state.clientSessionKey, async () => {
      if (!this.completion) throw new Error('workout completion is unavailable')
      const synchronized = await this.flushUnlocked(state)
      const draft = await this.drafts.loadActive()
      if (!draft || !draft.sessionId || draft.clientSessionKey !== state.clientSessionKey) {
        throw new Error('server workout session is unavailable')
      }
      if (hasBlockingOperations(draft.queue)
        || synchronized.syncStatus === 'CONFLICT'
        || synchronized.syncStatus === 'SYNC_REJECTED') {
        throw new Error('workout facts must be synchronized or explicitly abandoned before completion')
      }
      const result = await this.completion.completeWorkout(
        draft.sessionId,
        { expectedVersion: draft.lastServerVersion, completionType },
        `${state.clientSessionKey}-complete-${completionType}`,
      )
      await this.drafts.clearActive(draft.draftId)
      return result
    })
  }

  async replacementCandidates(state: WorkoutFlowState): Promise<readonly ExerciseReplacementCandidate[]> {
    if (!this.replacements) throw new Error('exercise replacement is unavailable')
    const draft = await this.drafts.loadActive()
    if (!draft?.sessionId || draft.clientSessionKey !== state.clientSessionKey) {
      throw new Error('workout session is unavailable for exercise replacement')
    }
    const current = state.exercises[state.currentExerciseIndex]
    return this.replacements.listExerciseReplacements(
      draft.sessionId, current.snapshotExerciseKey, current.exerciseCode,
    )
  }

  async replaceCurrentExercise(
    state: WorkoutFlowState, candidate: ExerciseReplacementCandidate,
  ): Promise<WorkoutFlowState> {
    return this.serialize(state.clientSessionKey, async () => {
      if (!this.replacements) throw new Error('exercise replacement is unavailable')
      const synchronized = await this.flushUnlocked(state)
      const draft = await this.drafts.loadActive()
      if (!draft || !draft.sessionId || draft.clientSessionKey !== state.clientSessionKey
        || hasBlockingOperations(draft.queue)
        || synchronized.syncStatus === 'CONFLICT' || synchronized.syncStatus === 'SYNC_REJECTED') {
        throw new Error('workout facts must be synchronized before replacement')
      }
      const latest = restoreFlowFromDraft(draft)
      const index = latest.currentExerciseIndex
      const current = latest.exercises[index]
      const session = await this.replacements.replaceWorkoutExercise(
        draft.sessionId, current.snapshotExerciseKey, candidate.code, draft.lastServerVersion,
      )
      const effective = session.exercises.find((exercise) => exercise.id === current.snapshotExerciseKey)
      if (!effective) throw new Error('replacement response is missing the current exercise')
      const updated = replaceExerciseForSession(latest, index, {
        snapshotExerciseKey: effective.id,
        exerciseCode: effective.exerciseCode,
        name: effective.exerciseName,
        targetWorkSets: effective.prescription.workSets,
        targetRepMin: effective.prescription.repMin,
        targetRepMax: effective.prescription.repMax,
        restSeconds: effective.prescription.restSeconds,
        weightStatus: effective.prescription.weightStatus,
        targetWeightKg: effective.prescription.targetWeightKg,
        targetRirMin: effective.prescription.targetRirMin,
        targetRirMax: effective.prescription.targetRirMax,
        eccentricSeconds: effective.prescription.eccentricSeconds,
        perSide: effective.prescription.perSide,
        executionGroup: effective.prescription.executionGroup,
        executionOrder: effective.prescription.executionOrder,
        optionalSetRule: effective.prescription.optionalSetRule,
      })
      const mapped = toWorkoutDraft(updated, draft, this.clock.nowUtc())
      await this.drafts.save({ ...mapped, lastServerVersion: session.version }, draft.revision)
      return updated
    })
  }

  async discardCorruptedDraft(): Promise<void> {
    if (!this.drafts.discardCorrupted) throw new Error('workout draft recovery is unavailable')
    await this.drafts.discardCorrupted()
  }

  private async mutate(
    state: WorkoutFlowState,
    update: (current: WorkoutFlowState) => WorkoutFlowState,
  ): Promise<WorkoutFlowState> {
    return this.serialize(state.clientSessionKey, async () => {
      const { draft, state: current } = await this.requireLatest(state.clientSessionKey)
      const updated = update(current)
      if (updated === current) return current
      await this.persist(updated, draft)
      return updated
    })
  }

  private async persist(state: WorkoutFlowState, previous: WorkoutDraft): Promise<void> {
    await this.drafts.save(toWorkoutDraft(state, previous, this.clock.nowUtc()), previous.revision)
  }

  private async requireLatest(clientSessionKey: string): Promise<{ draft: WorkoutDraft; state: WorkoutFlowState }> {
    const draft = await this.drafts.loadActive()
    if (!draft || draft.clientSessionKey !== clientSessionKey) {
      throw new Error('active workout draft does not match the workout session')
    }
    return { draft, state: restoreFlowFromDraft(draft) }
  }

  private async serialize<T>(clientSessionKey: string, command: () => Promise<T>): Promise<T> {
    const previous = this.commandTails.get(clientSessionKey) ?? Promise.resolve()
    const result = previous.catch(() => undefined).then(command)
    const tail = result.then(() => undefined, () => undefined)
    this.commandTails.set(clientSessionKey, tail)
    try {
      return await result
    } finally {
      if (this.commandTails.get(clientSessionKey) === tail) this.commandTails.delete(clientSessionKey)
    }
  }
}

function toRemainingReps(rir: WorkoutRir): number | undefined {
  if (rir === 'UNKNOWN') return undefined
  if (rir === '3_PLUS') return 3
  return Number(rir)
}

function remainingRepsToRir(remainingReps: number | undefined): WorkoutRir {
  if (remainingReps === undefined) return 'UNKNOWN'
  if (!Number.isSafeInteger(remainingReps) || remainingReps < 0) {
    throw new Error('active workout remaining reps are invalid')
  }
  if (remainingReps >= 3) return '3_PLUS'
  return String(remainingReps) as WorkoutRir
}

function applyConflictAuthority(
  state: WorkoutFlowState,
  result: WorkoutConflictResolutionResult,
  expectedSessionId: string,
): WorkoutFlowState {
  const payload = result.authoritativePayload
  if (payload.authoritativeSessionVersion !== result.authoritativeSessionVersion) {
    throw new Error('conflict authority version does not match the resolution')
  }
  if (payload.sessionId !== expectedSessionId) {
    throw new Error('conflict authority session does not match the active workout')
  }
  const kind = payload.kind
  let exercises: WorkoutFlowState['exercises'] = state.exercises.map(
    (exercise) => ({ ...exercise, sets: [...exercise.sets] }),
  )
  if (kind === 'WORKOUT_SESSION') {
    exercises = exercises.map((exercise) => ({
      ...exercise,
      sets: exercise.sets.filter((set) => set.clientSetKey !== result.clientKey),
    }))
  } else if (kind === 'WORKOUT_SET') {
    exercises = applyAuthoritativeSet(exercises, result)
  } else {
    throw new Error('conflict authority kind is unsupported')
  }
  const unsafeSet = exercises.flatMap((exercise) => exercise.sets)
    .find((set) => set.safetyFlag !== null || set.discomfort !== 'NONE')
  return restoreWorkoutFlow({
    ...state,
    exercises,
    automaticProgressionEligible: unsafeSet === undefined,
    safetyNotice: unsafeSet ? workoutSafetyNotice(unsafeSet.safetyFlag) : null,
  })
}

function applyAuthoritativeSet(
  exercises: readonly WorkoutFlowState['exercises'][number][],
  result: WorkoutConflictResolutionResult,
): WorkoutFlowState['exercises'] {
  const payload = result.authoritativePayload
  if (payload.clientSetKey !== result.clientKey || typeof payload.sessionExerciseId !== 'string') {
    throw new Error('conflict authority does not identify the local set')
  }
  const exerciseIndex = exercises.findIndex(
    (exercise) => exercise.snapshotExerciseKey === payload.sessionExerciseId,
  )
  if (exerciseIndex < 0) throw new Error('conflict authority exercise is not in the workout snapshot')
  const completionStatus = payload.completionStatus
  const actual = payload.actual
  const actualRecord = isRecord(actual) ? actual : null
  const safetyFlag = parseSafetyFlag(payload.safetyFlag)
  const existing = exercises.flatMap((exercise) => exercise.sets)
    .find((set) => set.clientSetKey === result.clientKey)
  if (!existing) throw new Error('conflict authority set is missing from the local draft')
  const existingExerciseIndex = exercises.findIndex((exercise) => (
    exercise.sets.some((set) => set.clientSetKey === result.clientKey)
  ))
  if (existingExerciseIndex !== exerciseIndex) {
    throw new Error('conflict authority set belongs to a different workout exercise')
  }

  if (completionStatus === 'PLANNED') {
    return exercises.map((exercise) => ({
      ...exercise,
      sets: exercise.sets.filter((set) => set.clientSetKey !== result.clientKey),
    }))
  }
  if (!['COMPLETED', 'FAILED', 'SKIPPED'].includes(String(completionStatus)) || !actualRecord) {
    throw new Error('conflict authority set facts are invalid')
  }
  const actualWeightKg = Number(actualRecord.actualWeightKg)
  const actualReps = Number(actualRecord.actualReps)
  if (!Number.isFinite(actualWeightKg) || actualWeightKg < 0
    || !Number.isSafeInteger(actualReps) || actualReps < 0) {
    throw new Error('conflict authority set measurements are invalid')
  }
  const remainingReps = payload.remainingReps
  const remainingRepsNumber = remainingReps === null || remainingReps === undefined
    ? null
    : Number(remainingReps)
  if (remainingRepsNumber !== null
    && (!Number.isSafeInteger(remainingRepsNumber) || remainingRepsNumber < 0)) {
    throw new Error('conflict authority remaining reps are invalid')
  }
  const rir: WorkoutRir = remainingRepsNumber === null
    ? 'UNKNOWN'
    : remainingRepsNumber >= 3
      ? '3_PLUS'
      : String(remainingRepsNumber) as WorkoutRir
  if (!['0', '1', '2', '3_PLUS', 'UNKNOWN'].includes(rir)) {
    throw new Error('conflict authority remaining reps are invalid')
  }
  const authoritative = {
    ...existing,
    setType: parseSetType(payload.setType),
    status: completionStatus as 'COMPLETED' | 'FAILED' | 'SKIPPED',
    actualWeightKg,
    actualReps,
    rir,
    safetyFlag,
    discomfort: safetyFlag === 'PAIN' ? 'PAIN' as const : 'NONE' as const,
  }
  return exercises.map((exercise, index) => index === exerciseIndex
    ? {
        ...exercise,
        sets: exercise.sets.map((set) => (
          set.clientSetKey === result.clientKey ? authoritative : set
        )),
      }
    : exercise)
}

function parseSetType(value: unknown): 'WARMUP' | 'WORK' | 'EXTRA' {
  if (value === 'WARMUP' || value === 'WORK' || value === 'EXTRA') return value
  throw new Error('conflict authority set type is invalid')
}

function parseSafetyFlag(value: unknown): import('../workoutFlow').WorkoutSafetyFlag | null {
  if (value === null || value === undefined) return null
  if (value === 'PAIN' || value === 'INJURY' || value === 'CHEST_DISCOMFORT'
    || value === 'DIZZINESS' || value === 'SEVERE_UNWELL') return value
  throw new Error('conflict authority safety flag is invalid')
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
