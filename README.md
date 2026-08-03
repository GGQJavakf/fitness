# AI 健身助手

首版交付目标为 Taro + React + TypeScript 微信小程序与 Spring Boot 模块化单体。产品和详细设计位于上级目录 `doc/`。

当前状态：P0 本地整链路已完成，覆盖登录、建档、计划生成与锁定、训练执行与恢复、历史、重量进阶以及 AI 关闭时的模板降级。微信真机验收和真实 AI 接入仍需要体验环境。

## 本地启动

后端默认使用 `local` profile 并只监听回环地址：

```powershell
Set-Location backend
.\mvnw.cmd spring-boot:run
```

小程序默认请求 `http://127.0.0.1:8080`：

```powershell
Copy-Item project.config.example.json project.config.json
Set-Location miniprogram
npm run dev:weapp
```

首次启动前请在本地 `project.config.json` 中填写自己的微信小程序 AppID。该文件不会进入 Git，仓库仅维护脱敏模板 `project.config.example.json`。

### CloudBase AI

小程序成长计划通过微信基础库提供的 `wx.cloud.extend.AI` 调用 CloudBase，不把 API Key 放入小程序。微信基础库需为 3.15.1 或更高版本，并在对应云开发环境中开启配置的生文模型。

本机可创建不会进入 Git 的 `miniprogram/config/cloudbase.json.local`：

```json
{
  "environmentId": "<CloudBase 环境 ID>",
  "model": "hy3"
}
```

也可以在构建前设置 `TARO_APP_CLOUDBASE_ENV_ID` 和 `TARO_APP_CLOUDBASE_AI_MODEL`。AI 返回内容只用于解释和总结，必须通过客户端结构、数字和安全校验；失败时自动使用后端规则模板。关键数字和进阶结论始终来自确定性规则引擎。

微信真机局域网调试必须使用真实微信 code 换取身份，不能开放本地假身份实现。完整步骤见 [微信真机局域网调试手册](docs/device-debug-runbook.md)。

## 验证

```powershell
Set-Location backend
.\mvnw.cmd verify

Set-Location ..\miniprogram
npm run typecheck
npm test -- --run
npm run build:weapp
```
