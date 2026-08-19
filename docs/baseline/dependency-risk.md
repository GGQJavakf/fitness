# M0-01 依赖风险基线

检查日期：2026-08-08；工具链复核：2026-08-12

## 已处理

- `@tarojs/components@4.2.1` 固定依赖 `swiper@11.1.15`，npm audit 报告其命中 prototype pollution critical 公告。
- 项目使用 npm `overrides` 将 Swiper 固定为首个不命中该公告的 `12.1.2`，并以类型检查、单元测试和 Taro `build:weapp` 验证当前骨架兼容。
- `@babel/core` 升级到修复任意文件读取公告的 `7.29.7`。
- `scss-bundle → globs@0.1.4` 仍依赖 `glob@7` 的回调 API，不能跨主版本覆盖为 Promise API。项目保留兼容的 `glob@7.2.3`，仅将 `minimatch@3.1.5` 的 `brace-expansion` 定向提升到同主版本安全补丁 `1.1.18`，并以真实异步回调调用回归验证。
- Taro 间接依赖的 `fast-uri`、`nanoid`、`esbuild`、`postcss`、`uuid`、`adm-zip`、`js-yaml`、`glob@10` 与 `serialize-javascript` 均已定向提升到兼容的安全版本；`webpack-dev-server` 保持在兼容 Node 20 的 `5.2.6`。项目 Node 版本范围收紧为 `20 || >=22`，与覆盖后依赖的 engine 声明一致。
- 覆盖后的单元测试、类型检查和 Taro 微信生产构建均通过，`npm audit --omit=dev --audit-level=high` 不再报告 high/critical；没有使用会降级 Taro 主版本的 `npm audit fix --force`。
- 后端 Jackson BOM 从 `2.21.4` 提升至修复版 `2.21.5`；Log4j 依赖族从命中 `GHSA-qv9r-c865-cp47` 的 `2.24.3` 统一提升至修复版 `2.25.5`。OSV 对 49 个 Maven runtime coordinates 的批量复扫为 0 个已知漏洞匹配。该扫描已纳入仓库统一 `verify`，数据源不可用或响应错位时均 fail closed。
- 小程序构建已从通用 `@tarojs/cli` 切换到仓库内仅支持 `weapp` 的构建入口，继续复用 Taro 4.2.1 的官方 service、平台插件、React 插件和 webpack runner。模板下载与解压链不再安装；旧 CLI 与新入口对同一源码生成 165 个文件，逐文件 SHA-256 一致。
- Taro 4.2.1 声明精确 webpack 5.91.0 peer；项目将直接依赖和 override 同步固定为安全版 5.109.2，并将其构建进度插件 webpackbar 更新至兼容 webpack 5 新校验的 7.0.0。标准（无 `--force`）空缓存 `npm ci`、完整测试、类型检查和微信生产构建共同作为兼容门禁。
- 全量开发工具审计从 1 critical、13 high、4 moderate、2 low 降为 0 个已知漏洞。`verify` 同时执行 packaged 与完整 toolchain audit，审计发现任何漏洞都会使当前步骤非零退出。

## 上游待跟踪

Taro 4.2.1 的 webpack peer 元数据仍固定为 `5.91.0`，上游尚未声明 5.109.2。项目因此持续用空缓存标准 `npm ci`、全量测试和真实微信构建验证 override；后续 Taro 正式放宽或升级 peer 后应移除项目 override，回到完全由上游声明的组合。

`glob@7.2.3` 已停止上游支持，但当前 `globs@0.1.4` 仍依赖其回调 API；后续应随 Taro/`scss-bundle` 升级整体替换，不在补丁版本中强制跨主版本覆盖。

不使用 `npm audit fix --force`，因为其建议会把 Taro 包降级至 3.x 并破坏已确认架构。每次 Taro 补丁升级时重新运行官方 registry audit；正式公开发布前继续要求完整审计为零，并重新评估所有构建链公告。

验证命令：

```powershell
npm ci --ignore-scripts
npm run typecheck
npm test -- --run
npm run build:weapp
npm audit --omit=dev --audit-level=low --registry=https://registry.npmjs.org
npm audit --audit-level=low --registry=https://registry.npmjs.org
node ../scripts/audit-maven-runtime-osv.mjs
```
