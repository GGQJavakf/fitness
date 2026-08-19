import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize)
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]),
    )
  }
  return value
}

function digest(value) {
  return createHash('sha256')
    .update(JSON.stringify(canonicalize(value)))
    .digest('hex')
}

function cell(value) {
  return String(value ?? '').replaceAll('|', '\\|').replaceAll('\n', '<br>')
}

function metadataLine(label, document) {
  const metadata = document.metadata ?? {}
  return `- ${label} \`${cell(metadata.version ?? 'MISSING')}\`：声明摘要 \`${cell(metadata.digestSha256 ?? 'MISSING')}\`；审核包摘要 \`${digest(document)}\``
}

export function buildContentReviewPack({ exercises, planTemplates, ruleConfig }) {
  const activeExercises = Array.isArray(exercises?.exercises)
    ? exercises.exercises.filter((exercise) => exercise?.active === true)
    : []
  const alternatives = activeExercises.flatMap((exercise) => (
    Array.isArray(exercise.alternatives)
      ? exercise.alternatives.map((alternative) => ({ source: exercise, alternative }))
      : []
  ))
  const names = new Map(activeExercises.map((exercise) => [exercise.code, exercise.name]))
  const lines = [
    '# 公开发布专业内容审核包',
    '',
    '> 当前状态：待专业人员填写；此文件本身不构成批准。AI 校验、自动化测试和开发者检查不能替代健身专业审核。',
    '',
    '## 审核人及结论',
    '',
    '- 审核人：________________',
    '- 专业资质及编号：________________',
    '- 审核机构（如适用）：________________',
    '- 审核日期（YYYY-MM-DD）：________________',
    '- 总体结论：[ ] 批准公开发布　[ ] 退回修改',
    '- 签名或可核验电子签署引用：________________',
    '',
    '## 内容身份',
    '',
    metadataLine('动作内容', exercises),
    metadataLine('计划模板', planTemplates),
    metadataLine('训练规则', ruleConfig),
    `- 范围：共 ${activeExercises.length} 个启用动作、${alternatives.length} 条替代关系。`,
    '- 任一上述摘要变化都会使本审核包过期，必须重新生成并复核。',
    '',
    '## 审核边界',
    '',
    '- 逐动作检查适用人群、动作步骤、呼吸、安全提醒、难度、器械、主要肌群及停止条件。',
    '- 逐关系检查源动作与替代动作的动作模式、主要肌群、难度及器械约束是否足够等价。',
    '- 金渐层猫图片是品牌化步骤插画，不是人体解剖或生物力学证明；文字步骤和安全提醒是当前权威指导。',
    '- 每行必须勾选“批准”或“退回”；退回项必须写明问题，不能把待定项视为批准。',
    '',
    '## 逐项审核表',
    '',
    '| 类型 | 编码/关系 | 名称 | 当前机器状态 | 内容指纹 | 专业判断 | 备注 |',
    '| --- | --- | --- | --- | --- | --- | --- |',
  ]

  for (const exercise of activeExercises) {
    const reviewContent = {
      code: exercise.code,
      name: exercise.name,
      plainLanguage: exercise.plainLanguage,
      movementPattern: exercise.movementPattern,
      difficulty: exercise.difficulty,
      equipment: exercise.equipment,
      primaryMuscles: exercise.primaryMuscles,
      instructions: exercise.instructions,
      breathingCues: exercise.breathingCues,
      commonMistakes: exercise.commonMistakes,
      safetyCues: exercise.safetyCues,
      assetStatus: exercise.assetStatus,
      imageRef: exercise.imageRef,
    }
    lines.push(`| EXERCISE | \`${cell(exercise.code)}\` | ${cell(exercise.name)} | ${cell(exercises.metadata?.status)} | \`${digest(reviewContent)}\` | [ ] 批准 [ ] 退回 | |`)
  }

  for (const { source, alternative } of alternatives) {
    const targetName = names.get(alternative.exerciseCode) ?? alternative.exerciseCode
    const relationship = `${source.code} -> ${alternative.exerciseCode}`
    lines.push(`| ALTERNATIVE | \`${cell(relationship)}\` | ${cell(source.name)} -> ${cell(targetName)} | ${cell(alternative.reviewStatus)} | \`${digest({ source: source.code, ...alternative })}\` | [ ] 批准 [ ] 退回 | |`)
  }

  lines.push('', '## 交付说明', '', '完成后请保留本文件、签署引用和对应 Git 提交 SHA；由项目维护者在单独授权的变更中更新发布元数据并重新运行公开发布预检。')
  return lines.join('\n')
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'))
}

function workspacePaths() {
  const scriptDirectory = dirname(fileURLToPath(import.meta.url))
  const repositoryRoot = resolve(scriptDirectory, '..', '..')
  return {
    repositoryRoot,
    output: resolve(repositoryRoot, 'docs', 'public-content-professional-review-pack.md'),
  }
}

function run() {
  const check = process.argv.slice(2).includes('--check')
  if (process.argv.slice(2).some((argument) => argument !== '--check')) {
    throw new Error('Only --check is supported')
  }
  const { repositoryRoot, output } = workspacePaths()
  const pack = `${buildContentReviewPack({
    exercises: readJson(resolve(repositoryRoot, 'rule-config', 'validated', 'exercises-v1.json')),
    planTemplates: readJson(resolve(repositoryRoot, 'rule-config', 'validated', 'plan-templates-v1.json')),
    ruleConfig: readJson(resolve(repositoryRoot, 'rule-config', 'validated', 'rule-config-v1.json')),
  })}\n`
  if (check) {
    const current = readFileSync(output, 'utf8')
    if (current !== pack) throw new Error('Professional content review pack is stale; run npm run generate:content-review-pack')
    console.log('Professional content review pack is current.')
    return
  }
  writeFileSync(output, pack, 'utf8')
  console.log(`Generated ${output}`)
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) run()
