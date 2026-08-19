export const DEFAULT_FORMAL_WEIGHT_KG = 2.5

export interface WorkoutWeightHistoryPoint {
  readonly completedAt: string
  readonly topWeightKg: number
}

export function chooseAutomaticWorkoutWeight(
  points: readonly WorkoutWeightHistoryPoint[],
  plannedWeightKg?: number,
): number {
  const latest = points
    .map((point) => ({ point, completedAt: Date.parse(point.completedAt) }))
    .filter(({ point, completedAt }) => Number.isFinite(completedAt)
      && Number.isFinite(point.topWeightKg)
      && point.topWeightKg > 0)
    .sort((left, right) => right.completedAt - left.completedAt)[0]?.point
  if (latest) return latest.topWeightKg
  if (Number.isFinite(plannedWeightKg) && (plannedWeightKg ?? 0) > 0) return plannedWeightKg!
  return DEFAULT_FORMAL_WEIGHT_KG
}
