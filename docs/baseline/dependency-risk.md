# M0-01 依赖风险基线

检查日期：2026-07-23

## 已处理

- `@tarojs/components@4.2.1` 固定依赖 `swiper@11.1.15`，npm audit 报告其命中 prototype pollution critical 公告。
- 项目使用 npm `overrides` 将 Swiper 固定为首个不命中该公告的 `12.1.2`，并以类型检查、单元测试和 Taro `build:weapp` 验证当前骨架兼容。
- `@babel/core` 升级到修复任意文件读取公告的 `7.29.7`。

## 上游待跟踪

Taro 4.2.1 的构建工具链仍固定旧版 esbuild、webpack-dev-server/uuid 和 webpack。npm audit 将它们列为 moderate，主要影响本地开发服务器或构建期能力；P0 当前没有生产 Web 服务，也未连接生产资源。

不使用 `npm audit fix --force`，因为其建议会把 Taro 包降级至 3.x 并破坏已确认架构。每次 Taro 补丁升级时重新运行官方 registry audit；正式公开发布前必须消除 high/critical，并重新评估构建链 moderate 风险。

验证命令：

```powershell
npm ci
npm run typecheck
npm test -- --run
npm run build:weapp
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```
