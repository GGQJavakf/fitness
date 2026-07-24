import { ApplicationError } from './errors'

export type DeletionStatus =
  | 'REQUESTED'
  | 'ACCESS_REVOKED'
  | 'BUSINESS_DATA_ANONYMIZED'
  | 'RETENTION_SEPARATED'
  | 'COMPLETED'
  | 'REJECTED'

export type PrivacyDataCategory =
  | 'PROFILE'
  | 'EQUIPMENT'
  | 'PREFERENCES'
  | 'PLANS'
  | 'WORKOUTS'

export type RetentionCategory = 'SECURITY_AUDIT' | 'LEGAL_HOLD'

export interface PrivacyExportResource {
  category: PrivacyDataCategory
  recordCount: number
  records: PrivacyExportRecord[]
}

export interface PrivacyExportRecord { id: string; summary: string }

export interface PrivacyExportData {
  id: string
  status: 'READY'
  generatedAt: string
  expiresAt: string
  resources: PrivacyExportResource[]
  scope: PrivacyDataCategory[]
  excludedRetentionCategories: RetentionCategory[]
}

export interface DeletionRequestData {
  id: string
  status: DeletionStatus
  requestedAt: string
  updatedAt: string
  deletionScope: PrivacyDataCategory[]
  retainedCategories: RetentionCategory[]
}

export interface PrivacyPort {
  exportData(reauthenticationProof: string): Promise<PrivacyExportData>
  requestDeletion(request: {
    reauthenticationProof: string
    confirmationText: 'DELETE'
  }): Promise<DeletionRequestData>
  getDeletionRequest(requestId: string): Promise<DeletionRequestData>
}

export interface ReauthenticationProofPort {
  getProof(): Promise<string>
}

export interface PrivacyExportViewModel extends PrivacyExportData {
  scopeLabel: string
  resourceSummary: string
  retentionNotice: string
}

export interface DeletionStatusViewModel extends DeletionRequestData {
  statusLabel: string
  scopeLabel: string
  retentionNotice: string
}

const categoryLabels: Record<PrivacyDataCategory | RetentionCategory, string> = {
  PROFILE: '档案',
  EQUIPMENT: '器械',
  PREFERENCES: '偏好',
  PLANS: '训练计划',
  WORKOUTS: '训练记录',
  SECURITY_AUDIT: '安全审计',
  LEGAL_HOLD: '法定保留',
}

const statusLabels: Record<DeletionStatus, string> = {
  REQUESTED: '已受理',
  ACCESS_REVOKED: '访问已撤销',
  BUSINESS_DATA_ANONYMIZED: '业务数据已匿名化',
  RETENTION_SEPARATED: '保留数据已分离',
  COMPLETED: '已完成',
  REJECTED: '未执行',
}

export function createPrivacyUseCases(port: PrivacyPort) {
  return {
    async exportData(reauthenticationProof: string): Promise<PrivacyExportViewModel> {
      requireProof(reauthenticationProof)
      const data = await port.exportData(reauthenticationProof)
      return {
        ...data,
        scopeLabel: labels(data.scope),
        resourceSummary: data.resources
          .map(({ category, recordCount }) => `${categoryLabels[category]} ${recordCount} 项`)
          .join('、'),
        retentionNotice: `以下数据不包含在普通导出/删除中：${labels(data.excludedRetentionCategories)}`,
      }
    },

    async requestDeletion(
      reauthenticationProof: string,
      confirmationText: string,
    ): Promise<DeletionStatusViewModel> {
      requireProof(reauthenticationProof)
      if (confirmationText !== 'DELETE') {
        throw new ApplicationError('VALIDATION_FAILED', '请准确输入 DELETE 完成二次确认')
      }
      return deletionView(await port.requestDeletion({
        reauthenticationProof,
        confirmationText,
      }))
    },

    async getDeletionStatus(requestId: string): Promise<DeletionStatusViewModel> {
      if (!requestId.trim()) {
        throw new ApplicationError('VALIDATION_FAILED', '删除申请编号不能为空')
      }
      return deletionView(await port.getDeletionRequest(requestId))
    },
  }
}

export function createVerifiedPrivacyUseCases(
  port: PrivacyPort,
  proofPort: ReauthenticationProofPort,
) {
  const privacy = createPrivacyUseCases(port)
  return {
    async exportData(): Promise<PrivacyExportViewModel> {
      return privacy.exportData(await proofPort.getProof())
    },
    async requestDeletion(confirmationText: string): Promise<DeletionStatusViewModel> {
      return privacy.requestDeletion(await proofPort.getProof(), confirmationText)
    },
    getDeletionStatus: privacy.getDeletionStatus,
  }
}

function requireProof(proof: string): void {
  if (!proof.trim()) {
    throw new ApplicationError('AUTHENTICATION_REQUIRED', '请重新验证身份后继续')
  }
}

function deletionView(data: DeletionRequestData): DeletionStatusViewModel {
  return {
    ...data,
    statusLabel: statusLabels[data.status],
    scopeLabel: labels(data.deletionScope),
    retentionNotice: `以下数据依法分离保留：${labels(data.retainedCategories)}`,
  }
}

function labels(categories: readonly (PrivacyDataCategory | RetentionCategory)[]): string {
  return categories.map((category) => categoryLabels[category]).join('、')
}

export function privacyActionErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof ApplicationError)) return fallback
  switch (error.code) {
    case 'REAUTHENTICATION_REQUIRED':
    case 'AUTHENTICATION_REQUIRED':
      return '请重新验证微信身份后继续'
    case 'RATE_LIMITED':
      return '操作过于频繁，请稍后重试'
    case 'NETWORK_ERROR':
      return '网络连接失败，请检查网络后重试'
    case 'VALIDATION_FAILED':
      return error.message
    default:
      return fallback
  }
}
