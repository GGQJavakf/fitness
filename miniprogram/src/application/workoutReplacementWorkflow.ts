export type WorkoutReplacementPhase = 'ENDING_ACTIVE' | 'OPENING_NEW'

/** Observer for the atomic replacement request and the subsequent page handoff. */
export interface WorkoutReplacementProgressObserver {
  onPhaseChanged(phase: WorkoutReplacementPhase): void
}

/** Preserves whether the atomic command or only the page handoff failed. */
export class WorkoutReplacementWorkflowError extends Error {
  constructor(
    readonly phase: WorkoutReplacementPhase,
    readonly failure: unknown,
  ) {
    super(phase === 'ENDING_ACTIVE'
      ? 'the active workout could not be atomically replaced'
      : 'the replacement workout started but its page could not be opened')
    this.name = 'WorkoutReplacementWorkflowError'
  }
}
