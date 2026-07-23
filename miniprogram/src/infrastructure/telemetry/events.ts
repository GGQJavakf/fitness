export const telemetryEventNames = ['app_started', 'screen_viewed', 'api_result'] as const

export type TelemetryEventName = (typeof telemetryEventNames)[number]

type AppStartedProperties = Readonly<{
  result: 'ready' | 'degraded'
}>

type ScreenViewedProperties = Readonly<{
  screen: 'home' | 'plan' | 'workout' | 'profile'
}>

type ApiResultProperties = Readonly<{
  operation: 'plan_generate' | 'workout_submit'
  result: 'success' | 'failure'
  status: number
  durationMs: number
}>

export type TelemetryEventProperties = {
  app_started: AppStartedProperties
  screen_viewed: ScreenViewedProperties
  api_result: ApiResultProperties
}

export type TelemetryEvent<Name extends TelemetryEventName> = Readonly<{
  name: Name
  properties: TelemetryEventProperties[Name]
}>

const allowedProperties: Record<TelemetryEventName, readonly string[]> = {
  app_started: ['result'],
  screen_viewed: ['screen'],
  api_result: ['operation', 'result', 'status', 'durationMs'],
}

const allowedValues = {
  app_started: { result: ['ready', 'degraded'] },
  screen_viewed: { screen: ['home', 'plan', 'workout', 'profile'] },
  api_result: {
    operation: ['plan_generate', 'workout_submit'],
    result: ['success', 'failure'],
  },
} as const

export function createTelemetryEvent<Name extends TelemetryEventName>(
  name: Name,
  properties: TelemetryEventProperties[Name],
): TelemetryEvent<Name> {
  if (!hasOnlyAllowedProperties(name, properties) || !hasValidValues(name, properties)) {
    throw new TypeError('Invalid telemetry event properties')
  }

  const immutableProperties = Object.freeze({ ...properties }) as TelemetryEventProperties[Name]
  return Object.freeze({
    name,
    properties: immutableProperties,
  })
}

function hasOnlyAllowedProperties(name: TelemetryEventName, properties: object): boolean {
  const propertyNames = Object.keys(properties)
  const expected = allowedProperties[name]
  return propertyNames.length === expected.length && propertyNames.every((property) => expected.includes(property))
}

function hasValidValues(name: TelemetryEventName, properties: object): boolean {
  const value = properties as Record<string, unknown>
  switch (name) {
    case 'app_started':
      return isAllowedString(value.result, allowedValues.app_started.result)
    case 'screen_viewed':
      return isAllowedString(value.screen, allowedValues.screen_viewed.screen)
    case 'api_result':
      return isAllowedString(value.operation, allowedValues.api_result.operation)
        && isAllowedString(value.result, allowedValues.api_result.result)
        && isHttpStatus(value.status)
        && isBoundedDuration(value.durationMs)
  }
}

function isAllowedString(value: unknown, allowed: readonly string[]): boolean {
  return typeof value === 'string' && value.length <= 64 && allowed.includes(value)
}

function isHttpStatus(value: unknown): boolean {
  return typeof value === 'number' && Number.isInteger(value) && value >= 100 && value <= 599
}

function isBoundedDuration(value: unknown): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 600_000
}
