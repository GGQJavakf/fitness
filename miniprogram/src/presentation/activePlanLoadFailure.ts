import { ApplicationError } from '../application/errors'

export type ActivePlanLoadFailure =
  | { kind: 'AUTHENTICATION_REQUIRED' }
  | { kind: 'DISPLAY_ERROR'; message: string }

export function resolveActivePlanLoadFailure(error: unknown): ActivePlanLoadFailure {
  if (!(error instanceof ApplicationError)) {
    return {
      kind: 'DISPLAY_ERROR',
      message: '服务暂时不可用，请稍后重试',
    }
  }
  if (error.code === 'AUTHENTICATION_REQUIRED') {
    return { kind: 'AUTHENTICATION_REQUIRED' }
  }
  return {
    kind: 'DISPLAY_ERROR',
    message: error.message,
  }
}
