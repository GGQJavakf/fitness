export type ProgressionDecision = 'INCREASE' | 'KEEP' | 'REDUCE' | 'REVIEW'
export type RecommendationStatus = 'PENDING' | 'APPLIED' | 'MODIFIED' | 'DISMISSED'

export interface ProgressionRecommendationData {
  readonly id: string
  readonly exerciseCode: string
  readonly status: RecommendationStatus
  readonly decision: ProgressionDecision
  readonly reasonCode: string
  readonly currentWeightKg: number
  readonly recommendedWeightKg: number
  readonly acceptedWeightKg?: number
  readonly algorithmVersion: string
  readonly appliedPlanId?: string
  readonly appliedPlanVersionId?: string
  readonly createdAt: string
}

export interface ExerciseTrendPoint {
  readonly sessionId: string
  readonly completedAt: string
  readonly topWeightKg: number
  readonly totalReps: number
  readonly workSetCount: number
}

export interface ExerciseTrendData {
  readonly exerciseCode: string
  readonly unit: 'KG'
  readonly points: readonly ExerciseTrendPoint[]
}

export interface ProgressionPort {
  listRecommendations(status?: RecommendationStatus): Promise<readonly ProgressionRecommendationData[]>
  applyRecommendation(
    id: string,
    expectedVersion: number,
    acceptedWeightKg: number,
    idempotencyKey: string,
  ): Promise<ProgressionRecommendationData>
  dismissRecommendation(id: string, reasonCode?: string): Promise<ProgressionRecommendationData>
  getExerciseTrend(exerciseCode: string): Promise<ExerciseTrendData>
}

export interface ProgressionCardView {
  readonly id: string
  readonly exerciseCode: string
  readonly title: string
  readonly reason: string
  readonly weightLabel: string
  readonly algorithmLabel: string
  readonly recommendedWeightKg: number
  readonly actionable: boolean
  readonly locked: boolean
}

const decisionTitles: Record<ProgressionDecision, string> = {
  INCREASE: '建议加重',
  KEEP: '保持重量',
  REDUCE: '建议减重',
  REVIEW: '需要确认',
}

const reasonTemplates: Record<string, string> = {
  PAIN_OR_SAFETY_FLAG: '记录中存在疼痛或安全提示，本次仅建议人工确认。',
  ANOMALOUS_INPUT: '记录中存在已标记的异常输入，本次不参与自动进阶。',
  CONFLICTING_INPUT: '训练记录相互冲突，需要先核对数据。',
  INSUFFICIENT_HISTORY: '有效训练历史不足，继续记录后再判断。',
  LONG_TRAINING_GAP: '距离上次训练较久，建议先恢复训练节奏。',
  VARIANT_CHANGED: '动作变式发生变化，旧记录不会直接用于进阶。',
  UNIT_CHANGED: '重量单位不一致，P0 不做隐式换算。',
  BODYWEIGHT_REQUIRES_CONFIRMATION: '自重动作需要用户确认，系统不会自动改重量。',
  CONSECUTIVE_BELOW_MIN: '连续训练未达到目标次数下限，规则建议降低一个可用档位。',
  MULTIPLE_FAILED_SETS: '本次存在多个失败正式组，规则建议降低一个可用档位。',
  ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR: '所有有效正式组达到次数上限，且强度反馈处于合理区间。',
  ALL_SETS_AT_MAX_TWICE_WITHOUT_RIR: '连续两次所有有效正式组达到上限，规则允许提高一个档位。',
  RIR_ZERO_AT_MAX: '虽达到次数上限，但余力为 0，本次保持重量。',
  WITHIN_TARGET_RANGE: '有效正式组仍在目标次数区间内，继续积累次数。',
  PARTIAL_AT_MAX: '仅部分正式组达到次数上限，本次保持重量。',
  WEIGHT_USER_LOCKED: '目标重量已锁定，规则只做解释，不会静默覆盖。',
}

export function toProgressionCard(value: ProgressionRecommendationData): ProgressionCardView {
  const locked = value.reasonCode === 'WEIGHT_USER_LOCKED'
  return {
    id: value.id,
    exerciseCode: value.exerciseCode,
    title: decisionTitles[value.decision],
    reason: reasonTemplates[value.reasonCode] ?? `规则原因：${value.reasonCode}`,
    weightLabel: weightLabel(value),
    algorithmLabel: `规则 ${value.algorithmVersion}`,
    recommendedWeightKg: value.recommendedWeightKg,
    actionable: value.status === 'PENDING'
      && !locked
      && (value.decision === 'INCREASE' || value.decision === 'REDUCE'),
    locked,
  }
}

export interface ExerciseTrendRow {
  readonly id: string
  readonly timeLabel: string
  readonly weightLabel: string
  readonly volumeLabel: string
}

export interface RecommendationActionGate {
  tryStart(recommendationId: string): boolean
  finish(recommendationId: string): void
}

export function createRecommendationActionGate(): RecommendationActionGate {
  const active = new Set<string>()
  return {
    tryStart(recommendationId) {
      if (active.has(recommendationId)) return false
      active.add(recommendationId)
      return true
    },
    finish(recommendationId) {
      active.delete(recommendationId)
    },
  }
}

export function toExerciseTrendRows(points: readonly ExerciseTrendPoint[]): ExerciseTrendRow[] {
  return points.map((point) => ({
    id: point.sessionId,
    timeLabel: point.completedAt.slice(0, 10),
    weightLabel: `${formatNumber(point.topWeightKg)} KG`,
    volumeLabel: `${point.workSetCount} 个有效正式组 · 共 ${point.totalReps} 次`,
  }))
}

function weightLabel(value: ProgressionRecommendationData): string {
  const current = formatNumber(value.currentWeightKg)
  if (value.decision === 'INCREASE' || value.decision === 'REDUCE') {
    return `${current} KG → ${formatNumber(value.recommendedWeightKg)} KG`
  }
  if (value.decision === 'KEEP') return `继续使用 ${current} KG`
  return '本次不自动给出新重量'
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(3)))
}
