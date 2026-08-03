const EXERCISE_NAMES: Readonly<Record<string, string>> = {
  GOBLET_SQUAT: '高脚杯深蹲',
  DUMBBELL_FRONT_SQUAT: '双哑铃前蹲',
  BODYWEIGHT_SQUAT: '自重深蹲',
  DUMBBELL_ROMANIAN_DEADLIFT: '哑铃罗马尼亚硬拉',
  DUMBBELL_DEADLIFT: '哑铃硬拉',
  BODYWEIGHT_HIP_HINGE: '自重髋铰链',
  DUMBBELL_BENCH_PRESS: '哑铃卧推',
  DUMBBELL_FLOOR_PRESS: '哑铃地板卧推',
  INCLINE_PUSH_UP: '上斜俯卧撑',
  SEATED_CABLE_ROW: '坐姿绳索划船',
  CABLE_HIGH_ROW: '绳索高位划船',
  CABLE_SINGLE_ARM_ROW: '单臂绳索划船',
  DUMBBELL_OVERHEAD_PRESS: '哑铃肩上推举',
  SEATED_DUMBBELL_PRESS: '坐姿哑铃推举',
  SINGLE_ARM_DUMBBELL_PRESS: '单臂哑铃推举',
  LAT_PULLDOWN: '高位下拉',
  CABLE_STRAIGHT_ARM_PULLDOWN: '直臂绳索下拉',
  NEUTRAL_GRIP_PULLDOWN: '对握高位下拉',
  DEAD_BUG: '死虫式',
  BIRD_DOG: '鸟狗式',
  PLANK: '平板支撑',
}

const PLAN_ISSUE_MESSAGES: Readonly<Record<string, string>> = {
  RULE_VERSION_NOT_SUPPORTED: '当前规则版本已不受支持，请重新生成计划。',
  P0_UNIT_NOT_SUPPORTED: 'P0 仅支持 KG，请使用公斤填写重量。',
  SESSION_FREQUENCY_OUT_OF_RANGE: '每周训练频率需保持在 2～6 天。',
  EXERCISE_COUNT_OUT_OF_RANGE: '本次训练的动作数量不在支持范围内。',
  DUPLICATE_EXERCISE: '同一训练日中出现了重复动作。',
  EXERCISE_NOT_ELIGIBLE: '当前动作与器械或安全条件不匹配。',
  DUPLICATE_MOVEMENT_PATTERN: '本次训练的动作模式过于重复。',
  PRIMARY_MUSCLE_VOLUME_OUT_OF_RANGE: '主要肌群训练量不在保守范围内。',
  RECOVERY_WINDOW_TOO_SHORT: '相邻训练日的恢复时间过短。',
  SESSION_DURATION_EXCEEDED: '预计训练时长超出了你选择的时间。',
  INITIAL_WEIGHT_NEEDS_CALIBRATION: '该动作需要在首次训练时校准重量。',
  WORK_SETS_OUT_OF_RANGE: '工作组数不在安全支持范围内。',
  REP_RANGE_OUT_OF_RANGE: '目标次数不在支持范围内。',
  REST_OUT_OF_RANGE: '组间休息时间不在建议范围内。',
}

function readableCode(value: string): string {
  const words = value.toLowerCase().split('_').filter(Boolean)
  if (!words.length) return value
  return [words[0][0]?.toUpperCase() + words[0].slice(1), ...words.slice(1)].join(' ')
}

export function goalDisplayName(value?: string): string {
  return ({ GENERAL_FITNESS: '一般健身', STRENGTH: '提升力量', HYPERTROPHY: '增肌' } as Record<string, string>)[value ?? ''] ?? readableCode(value ?? '未设置')
}

export function experienceDisplayName(value?: string): string {
  return ({ BEGINNER: '刚开始训练', INTERMEDIATE: '有训练经验', ADVANCED: '进阶训练者' } as Record<string, string>)[value ?? ''] ?? readableCode(value ?? '未设置')
}

export function locationDisplayName(value?: string): string {
  return ({ HOME: '居家', GYM: '健身房', OTHER: '其他场地' } as Record<string, string>)[value ?? ''] ?? readableCode(value ?? '未设置')
}

export function weightStatusDisplayName(value?: string): string {
  return ({ KNOWN: '使用已知重量', NEEDS_CALIBRATION: '首次训练时校准重量', BODYWEIGHT: '自重训练' } as Record<string, string>)[value ?? ''] ?? readableCode(value ?? '待确认')
}

export function exerciseDisplayName(value: string): string {
  return EXERCISE_NAMES[value] ?? readableCode(value)
}

export function trainingDayDisplayName(value: string): string {
  const matched = /^DAY[_\s-]*(.+)$/i.exec(value)
  return matched ? `训练日 ${matched[1]}` : readableCode(value)
}

export function planIssueDisplayMessage(reasonCode: string): string {
  return PLAN_ISSUE_MESSAGES[reasonCode] ?? `计划需要调整：${readableCode(reasonCode)}`
}

export function planFieldDisplayName(fieldPath: string): string {
  const matched = /^\/days\/([^/]+)\/exercises\/([^/]+)\/([^/]+)$/.exec(fieldPath)
  if (!matched) return '计划整体设置'
  const fieldName = ({
    workSets: '工作组数',
    repMin: '最少次数',
    repMax: '最多次数',
    restSeconds: '休息时间',
    initialWeightKg: '初始重量',
  } as Record<string, string>)[matched[3]] ?? readableCode(matched[3])
  const dayName = matched[1].startsWith('DAY_') ? `训练日 ${matched[1].slice(4)}` : readableCode(matched[1])
  return `${dayName} · ${exerciseDisplayName(matched[2])} · ${fieldName}`
}

export function evidenceRows(evidence: Readonly<Record<string, string>>): ReadonlyArray<readonly [string, string]> {
  return Object.entries(evidence).sort(([left], [right]) => left.localeCompare(right))
}

export function evidenceFieldDisplayName(value: string): string {
  return ({
    actualWeightKg: '实际重量',
    actualReps: '实际次数',
    status: '完成状态',
    rir: '训练余力',
    discomfort: '身体反馈',
    setType: '训练组类型',
    updatedAt: '记录时间',
  } as Record<string, string>)[value] ?? '记录信息'
}

export function evidenceValueDisplayName(value: string): string {
  return ({
    COMPLETED: '已完成',
    FAILED: '未完成',
    SKIPPED: '已跳过',
    PAIN: '疼痛或明显不适',
    NONE: '无不适',
    UNKNOWN: '未填写',
    WARMUP: '热身组',
    WORK: '正式组',
  } as Record<string, string>)[value] ?? value
}
