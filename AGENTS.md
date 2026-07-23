# AI 健身助手开发约束

## 项目概览

- P0 首版只交付 Taro + React + TypeScript 微信小程序和 Spring Boot 模块化单体。
- 不创建 Android/iOS、App 发布链、P1 功能或 production 资源。
- P0 仅 KG、仅成年用户，产品不提供医疗诊断或康复处方。

## 仓库结构

- `miniprogram/`：Taro 微信小程序源码、客户端架构测试和微信开发者工具配置。
- `backend/`：Spring Boot 模块化单体与后端测试。
- `docs/baseline/`：代码仓库基线证据；产品文档在上级 `../doc/`。
- `.learnings/`：仅保留在本地的环境和工具链问题，不进入业务提交。

## 依赖关系速览

- 客户端表现层、平台层和基础设施层依赖应用层，应用层依赖领域层。
- 后端 API 和应用层依赖领域层，基础设施实现内层定义的端口。
- 前后端只通过版本化 OpenAPI 契约交互，不共享数据库模型。

## 文档与事实来源

- 需求：`../doc/AI健身助手微信小程序_PRD_V1.1.md`。
- 设计：`../doc/AI健身助手微信小程序_详细设计_V1.0.md`。
- 开发顺序与验收：`../doc/AI健身助手微信小程序_开发计划_M0-M4_V1.0.md`。
- 架构决策：`../doc/decisions/2026-07-23_Taro-React-DDD客户端架构.md`。

## 不可突破的业务规则

- 重量、组数、次数、休息、频率、训练量、取整和进阶结论由版本化确定性规则引擎产生。
- AI 只生成保守候选、排序和解释；AI 结果必须结构化校验，失败时降级为模板。
- `USER_LOCKED` 字段不得在生成、重平衡或建议采纳时被静默覆盖。
- 生效计划、训练快照和进阶输入快照不可变；订正通过新版本或追加记录。
- 客户端离线草稿不等于服务端成功；同步必须使用幂等键和显式冲突状态。

## 客户端架构

- 依赖方向：`presentation/platform/infrastructure -> application -> domain`。
- `domain` 是纯 TypeScript，禁止依赖 React、Taro、`wx`、HTTP 或存储。
- `application` 定义用例和端口；平台与基础设施实现端口。
- 微信登录、存储、网络和生命周期调用只放在 `src/platform/weapp`。
- 页面只调用应用用例，不复制服务端权威数值规则。
- 新增平台专属代码时必须补边界测试和微信体验版验证记录。

## 后端架构

- 按 identity、profile、content、rules、plan、workout、progression、ai、analytics、privacy 业务能力拆分。
- 模块内部依赖：`api -> application -> domain`，`infrastructure` 实现端口。
- 领域层禁止依赖 Spring、Web、持久化框架、数据库方言和 AI SDK。
- 模块间通过公开应用接口或领域事件协作，不直接访问其他模块的 repository 或表。

## 开发流程

- 功能和缺陷遵循测试先行：先看到针对目标能力的失败，再写最小实现，再跑回归。
- 优先小文件、明确接口和现有工具；不提前创建无业务需求的抽象。
- 变更后检查 `git diff --check` 和 `git status --short`，不得混入备份、日志或诊断制品。

## 安全、密钥和高风险操作

- 不提交凭据、AppID、微信私有配置、真实用户数据、授权头或 AI 原始敏感输入。
- 只允许 `local/test/staging-experience`；不得连接生产数据库、AI 生产服务或正式发布渠道。
- 删除、迁移、数据库破坏性变更和外部发布前必须先验证精确目标与可恢复路径。

## 验证命令

```powershell
Set-Location backend
.\mvnw.cmd verify

Set-Location ..\miniprogram
npm ci
npm run typecheck
npm test -- --run
npm run build:weapp
```

客户端边界扫描：

```powershell
rg -n "wx\.|from ['\"]@tarojs/taro['\"]" miniprogram/src/domain miniprogram/src/application
Get-ChildItem . -Directory -Recurse | Where-Object { $_.Name -in @('android','ios') }
```
