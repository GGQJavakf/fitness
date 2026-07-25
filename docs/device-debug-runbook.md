# 微信真机局域网调试手册

本手册仅用于同一局域网内的开发调试，不是微信体验版或生产部署方案。它不会放宽本地假身份的回环地址限制；真机必须通过真实微信 `code` 换取身份。

## 1. 前置条件

- 手机与开发电脑连接同一可信局域网。
- 微信小程序 AppID 可用，且 `WECHAT_APP_ID` 与微信开发者工具项目一致。
- `WECHAT_APP_SECRET`、`FITNESS_DB_URL`、`FITNESS_DB_USERNAME`、`FITNESS_DB_PASSWORD` 已在当前 PowerShell 进程中配置。
- 密钥不得写入本文档、Git、`project.config.json` 或小程序构建产物。
- MySQL 数据库仅用于 local/test/staging-experience，禁止连接生产数据。

## 2. 选择电脑的局域网地址

```powershell
Get-NetIPConfiguration |
  Where-Object { $_.IPv4DefaultGateway -ne $null } |
  Select-Object InterfaceAlias,@{Name='IPv4';Expression={$_.IPv4Address.IPAddress}}
```

选择手机能够访问的私有 IPv4 地址。VPN、虚拟网卡或多个地址同时存在时，不要依赖自动选择。

## 3. 预检并构建真机版本

在 `<repo-root>/miniprogram` 执行：

```powershell
npm run preflight:device -- --host <LAN_IP>
npm run build:weapp:device -- --host <LAN_IP>
```

预检只显示环境变量键是否存在，不打印其值。构建会将请求基址注入为 `http://<LAN_IP>:8080`；默认构建仍保持 `http://127.0.0.1:8080`，两者不会互相覆盖源代码配置。

## 4. 启动体验后端

在同一个已配置环境变量的 PowerShell 中进入 `<repo-root>/backend`：

```powershell
.\mvnw.cmd spring-boot:run `
  "-Dspring-boot.run.profiles=staging-experience" `
  "-Dspring-boot.run.arguments=--server.address=<LAN_IP> --server.port=8080"
```

`staging-experience` 会使用真实微信身份适配器、Flyway 和 MySQL 仓储；AI 默认关闭并使用模板降级。启动日志不得包含微信密钥或数据库密码。

如果手机无法连接，先检查电脑与手机是否同网段以及 Windows 防火墙是否拦截 8080。不要自动创建永久放行规则；确需放行时仅对当前可信网络和 8080 端口创建临时规则，验证后删除。

## 5. 微信开发者工具

1. 从 `<repo-root>` 打开项目，`miniprogramRoot` 应指向 `miniprogram/dist/`。
2. 确认使用与 `WECHAT_APP_ID` 匹配的 AppID。
3. 仅在开发调试阶段启用“不校验合法域名、web-view、TLS 版本以及 HTTPS 证书”。该设置不能作为体验版验收证据。
4. 重新编译后选择“真机调试”，不要复用此前指向 `127.0.0.1` 的构建产物。

## 6. 最小验收顺序

1. 登录并完成建档、器械选择和计划确认。
2. 开始训练，执行通用热身和递增热身组。
3. 完成正式组并验证休息计时在前后台切换后按结束时间恢复。
4. 分别执行锁屏、杀进程、弱网、飞行模式、响应丢失和连续点击场景。
5. 恢复网络后确认幂等同步、显式冲突处理和已完成组不丢失。
6. 完成训练，检查历史、趋势、进阶建议和 AI 模板降级总结。

每次执行应在上级产品文档的 `doc/verification/M2_微信真机恢复矩阵.md` 记录设备、系统、微信、基础库版本及结果。没有这些证据时，不得宣称真机门已通过。
