import { describe, expect, it, vi } from 'vitest'

const wechatRuntime = vi.hoisted(() => ({ reportEvent: vi.fn() }))
vi.stubGlobal('wx', wechatRuntime)

import { createTelemetryEvent, createTelemetryReporter } from '../src/infrastructure/telemetry/events'
import { createWechatTelemetryReporter } from '../src/platform/weapp/WechatTelemetryReporter'

describe('telemetry event construction', () => {
  it('constructs only declared bounded metadata and freezes the event', () => {
    const event = createTelemetryEvent('api_result', {
      operation: 'workout_submit',
      result: 'failure',
      status: 409,
      durationMs: 17,
    })

    expect(event).toEqual({
      name: 'api_result',
      schemaVersion: 1,
      properties: {
        operation: 'workout_submit',
        result: 'failure',
        status: 409,
        durationMs: 17,
      },
    })
    expect(Object.isFrozen(event)).toBe(true)
    expect(Object.isFrozen(event.properties)).toBe(true)
  })

  it('rejects undeclared and sensitive payload keys without any network side effect', () => {
    expect(() => createTelemetryEvent('screen_viewed', {
      screen: 'home',
      wechatCode: 'temporary-code',
      authorization: 'Bearer secret',
      injuryNote: '膝盖疼',
    } as never)).toThrow('Invalid telemetry event properties')
  })

  it('rejects non-finite numbers and overlong strings', () => {
    expect(() => createTelemetryEvent('api_result', {
      operation: 'workout_submit',
      result: 'success',
      status: 200,
      durationMs: Number.POSITIVE_INFINITY,
    })).toThrow('Invalid telemetry event properties')
    expect(() => createTelemetryEvent('screen_viewed', {
      screen: 'x'.repeat(65),
    } as never)).toThrow('Invalid telemetry event properties')
  })

  it('never lets a telemetry outlet failure block the business flow', async () => {
    const reporter = createTelemetryReporter(() => Promise.reject(new Error('offline')))
    expect(() => reporter.track('sync_failed', { reason: 'network' })).not.toThrow()
    await Promise.resolve()
    expect(reporter.droppedEventCount()).toBe(1)
  })

  it('reports only the validated event envelope through the WeChat event API', () => {
    const reportEvent = vi.fn()
    const reporter = createWechatTelemetryReporter(reportEvent)

    reporter.track('api_result', {
      operation: 'workout_submit',
      result: 'failure',
      status: 409,
      durationMs: 17,
    })

    expect(reportEvent).toHaveBeenCalledWith('api_result', {
      schema_version: 1,
      operation: 'workout_submit',
      result: 'failure',
      status: 409,
      durationMs: 17,
    })
  })

  it('uses the native WeChat reportEvent API in the production adapter by default', () => {
    wechatRuntime.reportEvent.mockClear()
    const reporter = createWechatTelemetryReporter()

    reporter.track('sync_failed', { reason: 'network' })

    expect(wechatRuntime.reportEvent).toHaveBeenCalledWith('sync_failed', {
      schema_version: 1,
      reason: 'network',
    })
  })

  it('keeps WeChat reporting failures isolated from the business flow', () => {
    const reporter = createWechatTelemetryReporter(() => {
      throw new Error('reportEvent unavailable')
    })

    expect(() => reporter.track('workout_started', { exerciseCount: 5 })).not.toThrow()
    expect(reporter.droppedEventCount()).toBe(1)
  })

  it('keeps asynchronous WeChat reporting failures isolated from the business flow', async () => {
    const reporter = createWechatTelemetryReporter(
      vi.fn().mockRejectedValue(new Error('reportEvent unavailable')),
    )

    expect(() => reporter.track('workout_started', { exerciseCount: 5 })).not.toThrow()
    await Promise.resolve()
    expect(reporter.droppedEventCount()).toBe(1)
  })
})
