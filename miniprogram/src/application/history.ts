export interface WorkoutHistoryItem {
  readonly sessionId: string
  readonly trainingDayCode: string
  readonly trainingDayName: string
  readonly status: 'COMPLETED' | 'ABORTED'
  readonly startedAt: string
  readonly completedAt: string
  readonly completedWorkSets: number
  readonly completedVolumeKg: number
  readonly completedReps: number
  readonly usesExternalLoad: boolean
}

export interface WorkoutHistoryPage {
  readonly items: readonly WorkoutHistoryItem[]
  readonly nextCursor?: string
  readonly hasMore: boolean
}

export interface WorkoutSessionSummary {
  readonly sessionId: string
  readonly status: 'COMPLETED' | 'ABORTED'
  readonly completedWorkSets: number
  readonly completedVolumeKg: number
  readonly completedReps: number
  readonly usesExternalLoad: boolean
}

export interface WorkoutHistoryPort {
  listHistory(cursor?: string, limit?: number): Promise<WorkoutHistoryPage>
  getWorkoutSessionSummary(sessionId: string): Promise<WorkoutSessionSummary>
}

export interface WorkoutHistoryCard {
  readonly id: string
  readonly trainingDayCode: string
  readonly title: string
  readonly statusLabel: string
  readonly timeLabel: string
  readonly factsLabel: string
  readonly incomplete: boolean
}

const CHINA_STANDARD_TIMEZONE_OFFSET_MINUTES = -8 * 60

export function toWorkoutHistoryCard(item: WorkoutHistoryItem): WorkoutHistoryCard {
  return {
    id: item.sessionId,
    trainingDayCode: item.trainingDayCode,
    title: item.trainingDayName,
    statusLabel: item.status === 'COMPLETED' ? '完整完成' : '提前结束',
    timeLabel: formatLocalDateTime(item.completedAt),
    factsLabel: item.usesExternalLoad
      ? `${item.completedWorkSets} 组 · ${formatVolume(item.completedVolumeKg)} KG·次`
      : `${item.completedWorkSets} 组 · 共 ${item.completedReps} 次`,
    incomplete: item.status !== 'COMPLETED',
  }
}

export function formatLocalDateTime(value: string, timezoneOffsetMinutes?: number): string {
  const instant = new Date(value)
  if (Number.isNaN(instant.getTime())) return value

  // The product currently serves users in China. The WeChat simulator may report
  // UTC even when it is emulating a Chinese device, so use the product timezone
  // unless a caller explicitly supplies another offset.
  const offset = timezoneOffsetMinutes ?? CHINA_STANDARD_TIMEZONE_OFFSET_MINUTES
  if (!Number.isFinite(offset)) return value
  const local = new Date(instant.getTime() - offset * 60_000)
  const pad = (part: number): string => String(part).padStart(2, '0')
  return `${local.getUTCFullYear()}-${pad(local.getUTCMonth() + 1)}-${pad(local.getUTCDate())}`
    + ` ${pad(local.getUTCHours())}:${pad(local.getUTCMinutes())}`
}

function formatVolume(value: number): string {
  if (!Number.isFinite(value) || value < 0) return '0'
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '')
}
