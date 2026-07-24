export const p0TelemetryEventNames = [
  'onboarding_started', 'onboarding_completed',
  'plan_generated', 'plan_generation_failed', 'plan_edited', 'plan_confirmed',
  'workout_started', 'workout_set_completed', 'workout_paused', 'workout_resumed',
  'workout_completed', 'workout_aborted', 'exercise_replaced', 'exercise_skipped',
  'progression_recommended', 'progression_applied', 'progression_dismissed',
  'ai_summary_requested', 'ai_summary_viewed', 'ai_summary_failed',
  'sync_failed', 'sync_conflict_resolved',
] as const

export const telemetryEventNames = [
  'app_started', 'screen_viewed', 'api_result', ...p0TelemetryEventNames,
] as const

export type TelemetryEventName = (typeof telemetryEventNames)[number]

type TelemetryEventProperties = {
  app_started: Readonly<{ result: 'ready' | 'degraded' }>
  screen_viewed: Readonly<{ screen: 'home' | 'plan' | 'workout' | 'profile' }>
  api_result: Readonly<{ operation: 'plan_generate' | 'workout_submit'; result: 'success' | 'failure'; status: number; durationMs: number }>
  onboarding_started: Readonly<{ source: 'new' | 'resume' }>
  onboarding_completed: Readonly<{ daysPerWeek: number; sessionMinutes: number }>
  plan_generated: Readonly<{ result: 'ready' | 'needs_adjustment'; issueCount: number }>
  plan_generation_failed: Readonly<{ reason: 'validation' | 'network' | 'server' }>
  plan_edited: Readonly<{ fieldKind: 'prescription' | 'lock' | 'structure' }>
  plan_confirmed: Readonly<{ versionNumber: number }>
  workout_started: Readonly<{ exerciseCount: number }>
  workout_set_completed: Readonly<{ status: 'completed' | 'failed' | 'skipped' }>
  workout_paused: Readonly<{ reason: 'background' | 'user' }>
  workout_resumed: Readonly<{ source: 'foreground' | 'relaunch' }>
  workout_completed: Readonly<{ completedSetCount: number }>
  workout_aborted: Readonly<{ completedSetCount: number }>
  exercise_replaced: Readonly<{ source: 'rules' | 'user' }>
  exercise_skipped: Readonly<{ reason: 'user' | 'unavailable' | 'pain' }>
  progression_recommended: Readonly<{ decision: 'increase' | 'keep' | 'reduce' | 'review' }>
  progression_applied: Readonly<{ decision: 'increase' | 'reduce'; modified: boolean }>
  progression_dismissed: Readonly<{ decision: 'increase' | 'keep' | 'reduce' | 'review' }>
  ai_summary_requested: Readonly<{ purpose: 'workout_summary' }>
  ai_summary_viewed: Readonly<{ source: 'provider' | 'template' }>
  ai_summary_failed: Readonly<{ reason: 'timeout' | 'rate_limited' | 'invalid_output' | 'unavailable' | 'budget' }>
  sync_failed: Readonly<{ reason: 'network' | 'rejected' | 'conflict' }>
  sync_conflict_resolved: Readonly<{ resolution: 'keep_local' | 'keep_server' | 'keep_both' }>
}

export type TelemetryEvent<Name extends TelemetryEventName> = Readonly<{
  name: Name
  schemaVersion: 1
  properties: TelemetryEventProperties[Name]
}>

type Validator = (value: unknown) => boolean
const oneOf = (values: readonly string[]): Validator => (value) => typeof value === 'string' && values.includes(value)
const integer = (minimum: number, maximum: number): Validator =>
  (value) => typeof value === 'number' && Number.isInteger(value) && value >= minimum && value <= maximum
const finite = (minimum: number, maximum: number): Validator =>
  (value) => typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum
const boolean: Validator = (value) => typeof value === 'boolean'

const schemas: Record<TelemetryEventName, Readonly<Record<string, Validator>>> = {
  app_started: { result: oneOf(['ready', 'degraded']) },
  screen_viewed: { screen: oneOf(['home', 'plan', 'workout', 'profile']) },
  api_result: {
    operation: oneOf(['plan_generate', 'workout_submit']), result: oneOf(['success', 'failure']),
    status: integer(100, 599), durationMs: finite(0, 600_000),
  },
  onboarding_started: { source: oneOf(['new', 'resume']) },
  onboarding_completed: { daysPerWeek: integer(2, 6), sessionMinutes: integer(30, 90) },
  plan_generated: { result: oneOf(['ready', 'needs_adjustment']), issueCount: integer(0, 100) },
  plan_generation_failed: { reason: oneOf(['validation', 'network', 'server']) },
  plan_edited: { fieldKind: oneOf(['prescription', 'lock', 'structure']) },
  plan_confirmed: { versionNumber: integer(1, 1_000_000) },
  workout_started: { exerciseCount: integer(1, 100) },
  workout_set_completed: { status: oneOf(['completed', 'failed', 'skipped']) },
  workout_paused: { reason: oneOf(['background', 'user']) },
  workout_resumed: { source: oneOf(['foreground', 'relaunch']) },
  workout_completed: { completedSetCount: integer(0, 1_000) },
  workout_aborted: { completedSetCount: integer(0, 1_000) },
  exercise_replaced: { source: oneOf(['rules', 'user']) },
  exercise_skipped: { reason: oneOf(['user', 'unavailable', 'pain']) },
  progression_recommended: { decision: oneOf(['increase', 'keep', 'reduce', 'review']) },
  progression_applied: { decision: oneOf(['increase', 'reduce']), modified: boolean },
  progression_dismissed: { decision: oneOf(['increase', 'keep', 'reduce', 'review']) },
  ai_summary_requested: { purpose: oneOf(['workout_summary']) },
  ai_summary_viewed: { source: oneOf(['provider', 'template']) },
  ai_summary_failed: { reason: oneOf(['timeout', 'rate_limited', 'invalid_output', 'unavailable', 'budget']) },
  sync_failed: { reason: oneOf(['network', 'rejected', 'conflict']) },
  sync_conflict_resolved: { resolution: oneOf(['keep_local', 'keep_server', 'keep_both']) },
}

export function createTelemetryEvent<Name extends TelemetryEventName>(
  name: Name,
  properties: TelemetryEventProperties[Name],
): TelemetryEvent<Name> {
  const schema = schemas[name]
  const entries = Object.entries(properties)
  if (entries.length !== Object.keys(schema).length
    || entries.some(([key, value]) => !schema[key]?.(value))) {
    throw new TypeError('Invalid telemetry event properties')
  }
  const immutableProperties = Object.freeze({ ...properties }) as TelemetryEventProperties[Name]
  return Object.freeze({ name, schemaVersion: 1, properties: immutableProperties })
}

export type TelemetrySink = (event: TelemetryEvent<TelemetryEventName>) => void | Promise<void>

export function createTelemetryReporter(sink: TelemetrySink = () => undefined) {
  let droppedEvents = 0
  return {
    track<Name extends TelemetryEventName>(name: Name, properties: TelemetryEventProperties[Name]): void {
      try {
        const pending = sink(createTelemetryEvent(name, properties))
        Promise.resolve(pending).catch(() => { droppedEvents += 1 })
      } catch {
        droppedEvents += 1
      }
    },
    droppedEventCount(): number {
      return droppedEvents
    },
  }
}
