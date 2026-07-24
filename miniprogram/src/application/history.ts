export interface WorkoutHistoryItem {
  readonly sessionId: string
  readonly trainingDayCode: string
  readonly status: 'COMPLETED' | 'ABORTED'
  readonly startedAt: string
  readonly completedAt: string
  readonly completedWorkSets: number
  readonly completedVolumeKg: number
}

export interface WorkoutHistoryPage {
  readonly items: readonly WorkoutHistoryItem[]
  readonly nextCursor?: string
  readonly hasMore: boolean
}

export interface WorkoutHistoryPort {
  listHistory(cursor?: string, limit?: number): Promise<WorkoutHistoryPage>
}

export interface WorkoutHistoryCard {
  readonly id: string
  readonly title: string
  readonly statusLabel: string
  readonly timeLabel: string
  readonly factsLabel: string
  readonly incomplete: boolean
}

export function toWorkoutHistoryCard(item: WorkoutHistoryItem): WorkoutHistoryCard {
  return {
    id: item.sessionId,
    title: item.trainingDayCode,
    statusLabel: item.status === 'COMPLETED' ? '完整完成' : '提前结束',
    timeLabel: formatUtc(item.completedAt),
    factsLabel: `${item.completedWorkSets} 组 · ${formatVolume(item.completedVolumeKg)} KG·次`,
    incomplete: item.status !== 'COMPLETED',
  }
}

function formatUtc(value: string): string {
  const matched = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value)
  return matched ? `${matched[1]}-${matched[2]}-${matched[3]} ${matched[4]}:${matched[5]} UTC` : value
}

function formatVolume(value: number): string {
  if (!Number.isFinite(value) || value < 0) return '0'
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '')
}
