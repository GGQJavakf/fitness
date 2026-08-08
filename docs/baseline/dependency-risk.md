# M0-01 依赖风险基线

检查日期：2026-08-08

## 已处理

- `@tarojs/components@4.2.1` 固定依赖 `swiper@11.1.15`，npm audit 报告其命中 prototype pollution critical 公告。
- 项目使用 npm `overrides` 将 Swiper 固定为首个不命中该公告的 `12.1.2`，并以类型检查、单元测试和 Taro `build:weapp` 验证当前骨架兼容。
- `@babel/core` 升级到修复任意文件读取公告的 `7.29.7`。
- `scss-bundle → globs@0.1.4` 仍依赖 `glob@7` 的回调 API，不能跨主版本覆盖为 Promise API。项目保留兼容的 `glob@7.2.3`，仅将 `minimatch@3.1.5` 的 `brace-expansion` 定向提升到同主版本安全补丁 `1.1.18`，并以真实异步回调调用回归验证。
- Taro 间接依赖的 `fast-uri` 与 `nanoid` 分别定向提升到 `3.1.5`、`3.3.17`，`webpack-dev-server` 保持在兼容 Node 20 的 `5.2.6`；项目 Node 版本范围收紧为 `20 || >=22`，与覆盖后依赖的 engine 声明一致。
- 覆盖后的单元测试、类型检查和 Taro 微信生产构建均通过，`npm audit --omit=dev --audit-level=high` 不再报告 high/critical；没有使用会降级 Taro 主版本的 `npm audit fix --force`。

## 上游待跟踪

Taro 4.2.1 的构建工具链仍固定旧版 esbuild、webpack-dev-server/uuid、PostCSS 和 webpack。生产依赖审计仍将它们列为 13 个 moderate，主要影响本地开发服务器或构建期能力；P0 当前没有生产 Web 服务，也未连接生产资源。`glob@7.2.3` 已停止上游支持，但当前 `globs@0.1.4` 仍依赖其回调 API；后续应随 Taro/`scss-bundle` 升级整体替换，不在补丁版本中强制跨主版本覆盖。

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
