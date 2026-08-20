# 专业内容 AI 预审记录（2026-08-20）

## 结论

**REVISE / review_required：当前内容可继续用于 `local`、`test`、`staging-experience`，不具备公开发布资格。**

这是基于代码、结构化内容和公开权威依据完成的 AI 预审，不是持证健身专业人员签署。本文不填写专业审核人、资质、签名和批准日期，也不把任何内容状态改为 `PUBLIC_RELEASE_APPROVED`。

## 审核范围与方法

- 动作内容：`exercises-v1.json` 版本 `1.7.1`，47 个启用动作。
- 计划模板：`plan-templates-v1.json` 版本 `1.8.0`，13 个模板、50 个训练日。
- 训练规则：`rule-config-v1.json` 版本 `1.6.0`。
- 替代关系：78 条。
- 校核方法：结构与交叉引用检查、动作模式/难度/主要肌群等价性检查、计划数值边界检查、发布状态检查，以及逐项阅读动作步骤与安全提示。
- 依据基线：[WHO 2020 身体活动指南](https://www.who.int/publications/i/item/9789240015128)、[美国身体活动指南第二版](https://health.gov/paguidelines/second-edition/pdf/Physical_Activity_Guidelines_2nd_edition.pdf)、[ACSM 2026 健康成年人抗阻训练立场声明](https://pubmed.ncbi.nlm.nih.gov/41843416/)。

## 发现

### P0：公开发布专业批准尚未完成（confirmed，report_only）

动作、模板和规则三类元数据均为 `AI_VALIDATED`，激活范围不包含 `public`；78 条替代关系也全部为 `AI_VALIDATED`。现有公开发布门禁正确阻断，不能由本次 AI 预审代签或绕过。

### P1：计划与规则的核心证据基线需要更新复核（confirmed，review_required）

计划模板和训练规则目前都只引用 2009 年 ACSM 立场声明。ACSM 已在 2026 年发布首个 17 年更新版，汇总 137 项系统综述、超过 3 万名参与者，并明确说明其更新 2009 版本。现有 `2～4` 组、`5～15` 次、`45～240` 秒休息等确定性区间不能仅凭旧引用继续获得公开专业背书；专业审核人应按 2026 依据重新确认目标处方、周训练量和进阶逻辑，并记录“维持”或“调整”的理由。

### P1：9 个动作的替代动作覆盖仍有明确缺口（confirmed，review_required）

- 没有替代候选：`STANDING_WALL_CALF_RAISE`、`GLUTE_BRIDGE_EXERCISE`、`CABLE_STRAIGHT_ARM_PULLDOWN`、`NEUTRAL_GRIP_PULLDOWN`、`PRONE_W_RAISE`、`PRONE_Y_RAISE`、`FLOOR_PRONE_COBRA`、`CONTRALATERAL_LIMB_RAISE`。
- 语义不完全等价：`LAT_PULLDOWN -> CABLE_STRAIGHT_ARM_PULLDOWN` 的主要肌群集合不同。

其余 77 条声明关系中，77 条通过动作模式、难度和主要肌群集合的结构化等价检查；上述 1 条不等价关系和 8 个空候选动作与现有测试中的 `review-required` 缺口一致。专业审核人需要决定补足候选、明确禁止替换，或保留缺口并给出用户可理解的原因。

### P2：动作指导字段完整性不足（confirmed，review_required）

47 个动作都有执行步骤和至少一条安全提示，但 47 个动作都没有结构化 `breathingCues` 与 `commonMistakes` 字段。部分提示已散落在步骤或安全文案中，例如平板支撑包含“自然呼吸”，但无法保证每个动作以一致方式展示。建议专业审核人逐动作决定是否补齐呼吸节奏、常见代偿和停止条件；这属于内容质量补强，不应机械批量生成后直接批准。

## 已通过的预审项

- 47 个启用动作均有通俗说明、执行步骤、安全提示、动作模式、难度、器械和主要肌群。
- 内容明确限定为已满 18 周岁的成年一般健身用户，单位仅使用 KG，不提供医疗诊断或康复处方。
- 未发现自指替代、未知动作引用、重复替代排名或未启用动作进入模板。
- 计划模板覆盖每周 2～6 次训练；规则含疼痛、胸部不适、头晕和严重不适等停止原因，并禁止按人口属性猜测起始重量。
- 动作、模板、规则和替代关系均未被错误激活到公开环境。

## 专业人员下一步签署清单

1. 按 `docs/public-content-professional-review-pack.md` 逐项审核 47 个动作和 78 条替代关系。
2. 对照 ACSM 2026 立场声明复核目标处方、周训练量、训练频率与进阶规则，并记录差异处置。
3. 对上述 9 个替代缺口逐项作出“补足、禁止替换或有理由保留”的决定。
4. 决定是否补齐结构化呼吸提示与常见错误；抽查动作图和文字是否一致。
5. 由具备相应资质的审核人填写姓名、资质、日期与签名；之后再走单独授权的状态和公开环境变更流程。
