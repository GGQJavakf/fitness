# AI 健身助手

首版交付目标为 Taro + React + TypeScript 微信小程序与 Spring Boot 模块化单体。产品和详细设计位于上级目录 `doc/`。

当前状态：`0.1.2` 技术发布候选已覆盖登录、建档、计划生成与锁定、训练执行与恢复、历史、重量进阶以及 AI 关闭时的模板降级。自动化测试、模拟器关键页面检查和一次性空 MySQL 8 打包验证已通过；体验版预览包已生成。公开发布仍由专业内容审核门禁阻断，微信真机弱网恢复矩阵和真实 AI 验收仍需在体验环境完成。详见 [0.1.2 候选验证记录](docs/release-candidate-0.1.2-validation.md)。

## 本地启动

后端默认使用 `local` profile 并只监听回环地址：

```powershell
Set-Location backend
.\mvnw.cmd spring-boot:run
```

小程序默认请求 `http://127.0.0.1:8080`。仓库根目录的 `project.config.json` 是唯一需要人工维护的微信项目配置：

```powershell
Copy-Item project.config.example.json project.config.json
# 在 project.config.json 中填写自己的微信小程序 AppID
Set-Location miniprogram
npm run dev:weapp
```

`npm run dev:weapp`、`npm run build:weapp` 和发布预检会自动从根配置派生 `miniprogram/project.config.json`，并把其 `miniprogramRoot` 调整为 `./dist`。两份实际配置都被 Git 忽略；仓库只维护脱敏模板，真实 AppID 不进入版本库。微信开发者工具直接导入仓库根目录。

### CloudBase AI

小程序成长计划通过微信基础库提供的 `wx.cloud.extend.AI` 调用 CloudBase，不把 API Key 放入小程序。微信基础库需为 3.15.1 或更高版本，并在对应云开发环境中开启配置的生文模型。

本机可创建不会进入 Git 的 `miniprogram/config/cloudbase.json.local`：

```json
{
  "environmentId": "<CloudBase 环境 ID>",
  "model": "hy3",
  "serviceName": "fitness-api"
}
```

体验版或云端调试必须配置 `serviceName`，小程序才会通过 `wx.cloud.callContainer` 访问 CloudBase Run；仅在本机 HTTP 联调时省略该字段。也可以在构建前设置 `TARO_APP_CLOUDBASE_ENV_ID`、`TARO_APP_CLOUDBASE_AI_MODEL` 和 `TARO_APP_CLOUDBASE_SERVICE_NAME`。AI 返回内容只用于解释和总结，必须通过客户端结构、数字和安全校验；失败时自动使用后端规则模板。关键数字和进阶结论始终来自确定性规则引擎。

微信真机调试必须使用真实微信 code 换取身份并通过 HTTPS 访问 API，不能开放本地假身份或非回环明文 HTTP。完整步骤见 [微信真机安全调试手册](docs/device-debug-runbook.md)。

## 验证

```powershell
Set-Location backend
.\mvnw.cmd verify

Set-Location ..\miniprogram
npm run typecheck
npm test -- --run
npm run build:weapp
```

仓库级验证（不部署、不上传）：

```powershell
node scripts/verify.mjs --target staging-experience
```

仓库级验证要求 Docker 服务可用，或同时配置获批的迁移测试与打包烟测 MySQL 8 临时数据库；随后会检查 Maven XML 报告，关键数据库迁移和打包 JAR 烟测不得缺失或跳过。数据库与进程仅用于本次验证。外部数据库必须按 [发布预检手册](docs/release-preflight.md) 使用非生产一次性空 schema，不得复用或清理已有业务库。
