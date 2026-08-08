const CONTROL_OR_FORMAT_PATTERN = /[\p{Cc}\p{Cf}]/u
const OTHER_UNSAFE_CODE_POINT_PATTERN = /[\p{Cs}\p{Co}\p{Cn}]/u
const DEFAULT_IGNORABLE_CODE_POINT_PATTERN = /\p{Default_Ignorable_Code_Point}/u
const DETECTION_SEPARATOR_PATTERN = /[\p{Z}\p{P}\p{S}\p{M}\p{C}_]+/gu
const ENGLISH_NUMBER_WORD =
  '(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand)'
const ENGLISH_DETECTION_GAP = '[\\p{Z}\\p{P}\\p{S}\\p{M}\\p{C}_]+'

const FORBIDDEN_COMPACT_MARKERS = [
  '忽略之前',
  '忽略以上',
  '忽略系统',
  '系统提示词',
  '开发者消息',
  '越过系统',
  '绕过系统',
  'ignoreprevious',
  'ignoreallprevious',
  'ignoresystem',
  'systemprompt',
  'developermessage',
  'apikey',
  'accesstoken',
  'jailbreak',
  '医疗',
  '诊断',
  '治疗',
  '康复',
  '处方',
  '疾病',
  '医生',
  '手术',
  '术后',
  '高血压',
  '低血压',
  '心脏病',
  '糖尿病',
  '哮喘',
  '关节炎',
  '半月板',
  '韧带',
  '骨折',
  '椎间盘',
  '疝气',
  '孕期',
  '怀孕',
  '疼痛',
  '受伤',
  '损伤',
  '扭伤',
  '拉伤',
  '撕裂',
  '炎症',
  '眩晕',
  '头晕',
  '胸闷',
  '麻木',
  '肿胀',
  '膝伤',
  '肩伤',
  '腰伤',
  '背伤',
  '旧伤',
  '伤后',
  '伤病',
  '身体不适',
  '感到不适',
  '出现不适',
  '持续不适',
  '酸痛',
  '刺痛',
  'medical',
  'diagnosis',
  'treatment',
  'therapy',
  'rehab',
  'doctor',
  'surgery',
  'injury',
  'injured',
  'pain',
  'hypertension',
  'diabetes',
  'fracture',
  'ligament',
  'meniscus',
] as const

const ABSOLUTE_WEIGHT_PATTERN =
  /(?:\d+(?:\.\d+)?|[零〇一二两三四五六七八九十百点半]+)(?:kilograms?|kilos?|pounds?|kgs?|lbs?|公斤|千克|市斤|斤|磅)/i
const ENGLISH_ABSOLUTE_WEIGHT_PATTERN = new RegExp(
  `(?:^|[^a-z])${ENGLISH_NUMBER_WORD}(?:${ENGLISH_DETECTION_GAP}(?:and${ENGLISH_DETECTION_GAP})?${ENGLISH_NUMBER_WORD})*${ENGLISH_DETECTION_GAP}(?:kilograms?|kilos?|pounds?|kgs?|lbs?)(?![a-z])`,
  'iu',
)

export function normalizeSafeTrainingPreference(
  value: string | undefined,
  maximumLength = 300,
): string | null {
  const source = value ?? ''
  if (source.length > maximumLength || containsUnsafeCodePoint(source)) return null
  const trimmed = source.trim()
  const normalized = trimmed.normalize('NFKC')
  if (trimmed.length > maximumLength
    || normalized.length > maximumLength
    || containsUnsafeCodePoint(normalized)) {
    return null
  }
  const compact = compactForDetection(normalized)
  return FORBIDDEN_COMPACT_MARKERS.some((marker) => compact.includes(marker))
    ? null
    : trimmed
}

export function containsAbsoluteWeight(value: string): boolean {
  const normalized = value.normalize('NFKC').toLowerCase()
  return ABSOLUTE_WEIGHT_PATTERN.test(compactForDetection(normalized))
    || ENGLISH_ABSOLUTE_WEIGHT_PATTERN.test(normalized)
}

function compactForDetection(value: string): string {
  return value
    .normalize('NFKC')
    .toLowerCase()
    .replace(DETECTION_SEPARATOR_PATTERN, '')
}

function containsUnsafeCodePoint(value: string): boolean {
  return CONTROL_OR_FORMAT_PATTERN.test(value)
    || OTHER_UNSAFE_CODE_POINT_PATTERN.test(value)
    || DEFAULT_IGNORABLE_CODE_POINT_PATTERN.test(value)
}
