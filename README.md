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
Set-Location miniprogram
npm run dev:weapp
```

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
