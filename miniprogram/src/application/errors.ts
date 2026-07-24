export type ApplicationErrorCode =
  | 'AUTHENTICATION_REQUIRED'
  | 'REAUTHENTICATION_REQUIRED'
  | 'ACCESS_DENIED'
  | 'RESOURCE_NOT_FOUND'
  | 'VERSION_CONFLICT'
  | 'VALIDATION_FAILED'
  | 'PLAN_VALIDATION_FAILED'
  | 'RATE_LIMITED'
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

export function applicationErrorMessage(code: ApplicationErrorCode): string {
  switch (code) {
    case 'AUTHENTICATION_REQUIRED':
      return '登录状态已失效，请重新登录'
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
    case 'RATE_LIMITED':
      return '请求过于频繁，请稍后重试'
    case 'NETWORK_ERROR':
      return '网络连接失败，请检查本地或体验版网络配置后重试'
    case 'INVALID_RESPONSE':
    case 'INTERNAL_ERROR':
      return '服务暂时不可用，请稍后重试'
  }
}
