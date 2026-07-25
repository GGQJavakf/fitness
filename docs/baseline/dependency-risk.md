# M0-01 依赖风险基线

检查日期：2026-07-25

## 已处理

- `@tarojs/components@4.2.1` 固定依赖 `swiper@11.1.15`，npm audit 报告其命中 prototype pollution critical 公告。
- 项目使用 npm `overrides` 将 Swiper 固定为首个不命中该公告的 `12.1.2`，并以类型检查、单元测试和 Taro `build:weapp` 验证当前骨架兼容。
- `@babel/core` 升级到修复任意文件读取公告的 `7.29.7`。
- Taro 4.2.1 的多个构建依赖通过旧 `glob` 间接使用存在拒绝服务风险的 `brace-expansion`。项目仅将 `scss-bundle → globs` 下的 `glob` 覆盖为 `13.0.6`，并将 Taro 间接依赖的 `webpack-dev-server` 提升到兼容 Node 20 的 `5.2.6`；锁文件解析到 `minimatch@10.2.5` 与 `brace-expansion@5.0.8`，没有破坏仍需要旧 glob API 的其他工具，也没有使用会降级 Taro 主版本的 `npm audit fix --force`。
- 覆盖后的单元测试、类型检查和 Taro 微信生产构建均通过，`npm audit --omit=dev --audit-level=high` 不再报告 high/critical。

## 上游待跟踪

Taro 4.2.1 的构建工具链仍固定旧版 esbuild、webpack-dev-server/uuid 和 webpack。生产依赖审计仍将它们列为 12 个 moderate，主要影响本地开发服务器或构建期能力；P0 当前没有生产 Web 服务，也未连接生产资源。

包含全部开发工具的全量审计仍会报告 Taro 上游工具链中的 high/critical；它们不进入当前 `--omit=dev` 生产依赖门，但正式公开发布前仍必须消除或经过单独发布风险审批。

不使用 `npm audit fix --force`，因为其建议会把 Taro 包降级至 3.x 并破坏已确认架构。每次 Taro 补丁升级时重新运行官方 registry audit；正式公开发布前必须消除 high/critical，并重新评估构建链 moderate 风险。

验证命令：

```powershell
npm ci
npm run typecheck
npm test -- --run
npm run build:weapp
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```
