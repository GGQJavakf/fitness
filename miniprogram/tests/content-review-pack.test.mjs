import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

import { buildContentReviewPack } from '../scripts/generate-content-review-pack.mjs'

const repositoryRoot = resolve(import.meta.dirname, '..', '..')

function readJson(path) {
  return JSON.parse(readFileSync(resolve(repositoryRoot, path), 'utf8'))
}

describe('professional content review pack', () => {
  it('binds every active exercise and alternative to the current content digest', () => {
    const pack = buildContentReviewPack({
      exercises: readJson('rule-config/validated/exercises-v1.json'),
      planTemplates: readJson('rule-config/validated/plan-templates-v1.json'),
      ruleConfig: readJson('rule-config/validated/rule-config-v1.json'),
    })

    expect(pack).toContain('# 公开发布专业内容审核包')
    expect(pack).toContain('审核人：________________')
    expect(pack).toContain('专业资质及编号：________________')
    expect(pack).toContain('当前状态：待专业人员填写；此文件本身不构成批准')
    expect(pack).toContain('动作内容 `1.7.1`')
    expect(pack).toContain('共 47 个启用动作、78 条替代关系')
    expect(pack.match(/\| EXERCISE \|/g)).toHaveLength(47)
    expect(pack.match(/\| ALTERNATIVE \|/g)).toHaveLength(78)
  })
})
