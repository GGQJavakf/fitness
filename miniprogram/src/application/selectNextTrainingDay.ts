import type { WorkoutHistoryItem } from './history'

export function selectNextTrainingDayCode(
  days: readonly { readonly code: string }[],
  history: readonly WorkoutHistoryItem[],
): string {
  const firstDay = days[0]?.code ?? ''
  if (!firstDay) return ''
  const dayCodes = new Set(days.map((day) => day.code))
  const latestCompleted = history
    .filter((item) => item.status === 'COMPLETED' && dayCodes.has(item.trainingDayCode))
    .map((item) => ({ item, completedAt: Date.parse(item.completedAt) }))
    .filter((entry) => Number.isFinite(entry.completedAt))
    .sort((left, right) => right.completedAt - left.completedAt)[0]?.item
  if (!latestCompleted) return firstDay
  const currentIndex = days.findIndex((day) => day.code === latestCompleted.trainingDayCode)
  return days[(currentIndex + 1) % days.length]?.code ?? firstDay
}
