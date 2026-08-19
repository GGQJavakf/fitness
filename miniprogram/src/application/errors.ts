import type { WorkoutRecoveryAssessment } from './ports/WorkoutRecoveryPort'
import type { RecoverableActiveWorkout } from './ports/WorkoutSessionStartPort'

export type ApplicationErrorCode =
  | 'AUTHENTICATION_REQUIRED'
  | 'ACCESS_REVOKED'
  | 'REAUTHENTICATION_REQUIRED'
  | 'ACCESS_DENIED'
  | 'RESOURCE_NOT_FOUND'
  | 'VERSION_CONFLICT'
  | 'VALIDATION_FAILED'
  | 'PLAN_VALIDATION_FAILED'
  | 'INSUFFICIENT_REPLACEMENTS'
  | 'RATE_LIMITED'
  | 'RECOVERY_CONFIRMATION_REQUIRED'
  | 'ACTIVE_WORKOUT_EXISTS'
  | 'WORKOUT_START_ALREADY_TERMINAL'
  | 'WORKOUT_NOT_TERMINAL'
  | 'NETWORK_ERROR'
  | 'INVALID_RESPONSE'
  | 'INTERNAL_ERROR'

export class ApplicationError extends Error {
  readonly code: ApplicationErrorCode
  readonly retryable: boolean
  readonly fieldPaths: readonly string[]

  constructor(
    code: ApplicationErrorCode,
    message: string,
    options: { retryable?: boolean; fieldPaths?: readonly string[] } = {},
  ) {
    super(message)
    this.name = 'ApplicationError'
    this.code = code
    this.retryable = options.retryable ?? false
    this.fieldPaths = options.fieldPaths ?? []
  }
}

/** A server-issued, short-lived confirmation challenge bound to one start request. */
export class WorkoutRecoveryConfirmationRequiredError extends ApplicationError {
  constructor(
    readonly assessment: WorkoutRecoveryAssessment,
    readonly confirmationToken: string,
    readonly confirmationExpiresAt: string,
  ) {
    super('RECOVERY_CONFIRMATION_REQUIRED', '需要明确确认恢复窗口提醒')
    this.name = 'WorkoutRecoveryConfirmationRequiredError'
  }
}

/** A competing start found an owned, server-authoritative workout that must be resumed first. */
export class ActiveWorkoutExistsError extends ApplicationError {
  constructor(readonly activeWorkout: RecoverableActiveWorkout) {
    super('ACTIVE_WORKOUT_EXISTS', '存在尚未结束的训练，请先继续或结束该训练')
    this.name = 'ActiveWorkoutExistsError'
  }
}

/** The durable start key belongs to a workout that another device has already ended. */
export class WorkoutStartTerminalReplayError extends ApplicationError {
  constructor(readonly terminalSession: {
    id: string
    clientSessionKey: string
    status: 'COMPLETED' | 'ABORTED'
    version: number
  }) {
    super('WORKOUT_START_ALREADY_TERMINAL', '上次训练已经结束，请重新开始')
    this.name = 'WorkoutStartTerminalReplayError'
  }
}

export function applicationErrorMessage(code: ApplicationErrorCode): string {
  switch (code) {
    case 'AUTHENTICATION_REQUIRED':
      return '登录状态已失效，请重新登录'
    case 'ACCESS_REVOKED':
      return '账号访问已终止，本机用户数据需要清理'
    case 'REAUTHENTICATION_REQUIRED':
      return '请重新验证身份后继续'
    case 'ACCESS_DENIED':
      return '当前账号无权执行此操作'
    case 'RESOURCE_NOT_FOUND':
      return '请求的资料尚未创建'
    case 'VERSION_CONFLICT':
      return '内容已在其他位置更新，请刷新后比较'
    case 'VALIDATION_FAILED':
    case 'PLAN_VALIDATION_FAILED':
      return '提交内容未通过校验，请检查标记字段'
    case 'INSUFFICIENT_REPLACEMENTS':
      return '当前器械和动作偏好下没有兼容的替代动作'
    case 'RATE_LIMITED':
      return '请求过于频繁，请稍后重试'
    case 'RECOVERY_CONFIRMATION_REQUIRED':
      return '需要明确确认恢复窗口提醒'
    case 'ACTIVE_WORKOUT_EXISTS':
      return '存在尚未结束的训练，请先继续或结束该训练'
    case 'WORKOUT_START_ALREADY_TERMINAL':
      return '上次训练已经结束，请重新开始'
    case 'WORKOUT_NOT_TERMINAL':
      return '训练尚未结束，暂时无法查看训练总结'
    case 'NETWORK_ERROR':
      return '网络连接失败，请检查本地或体验版网络配置后重试'
    case 'INVALID_RESPONSE':
    case 'INTERNAL_ERROR':
      return '服务暂时不可用，请稍后重试'
  }
}
