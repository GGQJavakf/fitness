import {
  createTelemetryReporter,
  type TelemetryEvent,
  type TelemetryEventName,
} from '../../infrastructure/telemetry/events'

type WechatReportEvent = (
  eventId: string,
  data: Readonly<Record<string, string | number | boolean>>,
) => void | Promise<void>

interface WechatTelemetryRuntime {
  reportEvent?: WechatReportEvent
}

type WechatGlobal = typeof globalThis & { wx?: WechatTelemetryRuntime }

const reportThroughWechat: WechatReportEvent = (eventId, data) => {
  const runtime = (globalThis as WechatGlobal).wx
  const reportEvent = runtime?.reportEvent
  if (typeof reportEvent !== 'function') {
    throw new Error('WeChat reportEvent is unavailable')
  }
  return reportEvent.call(runtime, eventId, data)
}

export function createWechatTelemetryReporter(reportEvent: WechatReportEvent = reportThroughWechat) {
  return createTelemetryReporter((event: TelemetryEvent<TelemetryEventName>) => (
    reportEvent(event.name, {
      schema_version: event.schemaVersion,
      ...event.properties,
    })
  ))
}
