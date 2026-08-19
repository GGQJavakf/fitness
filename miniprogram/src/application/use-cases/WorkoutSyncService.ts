import {
  acknowledgeOperation,
  enqueueOperation,
  type NewWorkoutOperation,
  type WorkoutOperation,
} from '../../domain/sync/OperationQueue'
import type { WorkoutDraft, WorkoutDraftStore } from '../ports/WorkoutDraftStore'

export class WorkoutSyncService {
  constructor(
    private readonly drafts: WorkoutDraftStore,
    private readonly nowUtc: () => string,
  ) {}

  async recordLocalOperation<TPayload>(
    draft: WorkoutDraft,
    input: Omit<NewWorkoutOperation<TPayload>, 'createdAtUtc'>,
  ): Promise<{ draft: WorkoutDraft; operation: WorkoutOperation<TPayload> }> {
    const appended = enqueueOperation(draft.queue, { ...input, createdAtUtc: this.nowUtc() })
    const updated: WorkoutDraft = {
      ...draft,
      revision: draft.revision + 1,
      queue: appended.queue,
      updatedAtUtc: this.nowUtc(),
    }
    await this.drafts.save(updated, draft.revision)
    return { draft: updated, operation: appended.operation }
  }

  async acknowledge(draft: WorkoutDraft, clientOperationSeq: number): Promise<WorkoutDraft> {
    const updated: WorkoutDraft = {
      ...draft,
      revision: draft.revision + 1,
      queue: acknowledgeOperation(draft.queue, clientOperationSeq),
      updatedAtUtc: this.nowUtc(),
    }
    await this.drafts.save(updated, draft.revision)
    return updated
  }
}
