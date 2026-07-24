import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import {
  createTelemetryEvent,
  p0TelemetryEventNames,
} from '../src/infrastructure/telemetry/events'

const expectedP0Events = [
  'onboarding_started', 'onboarding_completed',
  'plan_generated', 'plan_generation_failed', 'plan_edited', 'plan_confirmed',
  'workout_started', 'workout_set_completed', 'workout_paused', 'workout_resumed',
  'workout_completed', 'workout_aborted', 'exercise_replaced', 'exercise_skipped',
  'progression_recommended', 'progression_applied', 'progression_dismissed',
  'ai_summary_requested', 'ai_summary_viewed', 'ai_summary_failed',
  'sync_failed', 'sync_conflict_resolved',
] as const

describe('P0 telemetry event contract', () => {
  it('covers every PRD event with a stable versioned allowlist', () => {
    expect(p0TelemetryEventNames).toEqual(expectedP0Events)
    expect(new Set(p0TelemetryEventNames).size).toBe(expectedP0Events.length)
  })

  it('accepts only bounded enum and numeric facts', () => {
    expect(createTelemetryEvent('workout_set_completed', {
      status: 'completed',
    })).toEqual({ name: 'workout_set_completed', schemaVersion: 1, properties: { status: 'completed' } })
    expect(createTelemetryEvent('onboarding_completed', {
      daysPerWeek: 4,
      sessionMinutes: 60,
    }).schemaVersion).toBe(1)
  })

  it('rejects free text, identifiers, and out-of-range metrics', () => {
    expect(() => createTelemetryEvent('workout_set_completed', {
      status: 'completed',
      injuryNote: '膝盖疼',
      userId: 'user-1',
    } as never)).toThrow('Invalid telemetry event properties')
    expect(() => createTelemetryEvent('onboarding_completed', {
      daysPerWeek: 99,
      sessionMinutes: 60,
    })).toThrow('Invalid telemetry event properties')
  })

  it('emits every P0 event from an explicit product boundary', () => {
    const presentation = [
      'onboarding', 'plan-editor', 'workout-prepare', 'workout-session',
      'workout-summary', 'history', 'sync-conflicts',
    ].map((page) => readFileSync(resolve(import.meta.dirname, `../src/presentation/pages/${page}/index.tsx`), 'utf8')).join('\n')
    for (const eventName of expectedP0Events) expect(presentation).toContain(`'${eventName}'`)
  })
})
