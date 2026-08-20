# 微信真机安全调试手册

本手册仅用于开发调试，不是生产部署方案。它不会放宽本地假身份的回环地址限制；真机必须通过真实微信 `code` 换取身份，API 请求必须使用 HTTPS。

只登录微信并不足以完成真机运行：小程序还必须能访问已部署的 HTTPS API。使用可公网访问、已配置微信合法域名的体验环境时，扫码打开后不要求手机与电脑处于同一局域网，也不要求数据线；只有后端仍运行在开发电脑上时，才需要同一局域网和受信任的 TLS 代理。

## 1. 前置条件

- 推荐使用已部署的 HTTPS 体验环境；局域网联调必须另有受信任的 TLS 终止代理，不能把 Spring HTTP 端口直接暴露给手机。
- 微信小程序 AppID 可用，且 `WECHAT_APP_ID` 与微信开发者工具项目一致。
- `WECHAT_APP_SECRET`、`FITNESS_DB_URL`、`FITNESS_DB_USERNAME`、`FITNESS_DB_PASSWORD` 已在当前 PowerShell 进程中配置。
- 密钥不得写入本文档、Git、`project.config.json` 或小程序构建产物。
- MySQL 数据库仅用于 local/test/staging-experience，禁止连接生产数据。
- 普通 HTTPS 体验地址和局域网反向代理必须保持 `FITNESS_TRUST_CLOUDBASE_IDENTITY_HEADERS=false`；登录始终通过一次性微信 code 交换。只有已回读确认的 CloudBase MINIAPP 私有入口才可启用该开关。

## 2. 选择 API 地址

推荐直接使用无路径、无查询参数的 HTTPS 体验环境地址，例如 `https://fitness-staging.example.com`。

确需局域网联调时，再选择电脑的私有 IPv4 地址：

```powershell
Get-NetIPConfiguration |
  Where-Object { $_.IPv4DefaultGateway -ne $null } |
  Select-Object InterfaceAlias,@{Name='IPv4';Expression={$_.IPv4Address.IPAddress}}
```

选择手机能够访问的私有 IPv4 地址。VPN、虚拟网卡或多个地址同时存在时，不要依赖自动选择。该地址必须由 HTTPS 反向代理监听，证书需要被手机信任并覆盖该地址。

## 3. 预检并构建真机版本

在 `<repo-root>/miniprogram` 执行：

```powershell
npm run preflight:device -- --host <LAN_IP>
npm run build:weapp:device -- --host <LAN_IP>
```

使用已部署的 HTTPS 体验环境时执行：

```powershell
npm run preflight:device -- --api-base-url https://<STAGING_HOST>
npm run build:weapp:device -- --api-base-url https://<STAGING_HOST>
```

局域网模式默认将请求基址注入为 `https://<LAN_IP>:8443`，也可用 `--port` 指定 TLS 代理端口。显式 HTTPS 地址模式不会要求本地后端环境变量。默认模拟器构建仍保持 `http://127.0.0.1:8080`，不会覆盖源代码配置。

`build:weapp:device` 在构建完成后会检查 JavaScript 产物是否确实包含本次选择的 HTTPS API 地址；地址未注入时命令以非零状态退出，不得继续真机调试。若误把普通模拟器包放到 Android、iOS 或 HarmonyOS 真机运行，首页会立即显示“真机包配置错误”，不会继续白屏等待网络超时。

## 4. 启动局域网体验后端（可选）

在同一个已配置环境变量的 PowerShell 中进入 `<repo-root>/backend`：

```powershell
.\mvnw.cmd spring-boot:run `
  "-Dspring-boot.run.profiles=staging-experience" `
  "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8080"
```

`staging-experience` 会使用真实微信身份适配器、Flyway 和 MySQL 仓储；AI 默认关闭并使用模板降级。启动日志不得包含微信密钥或数据库密码。另行配置 HTTPS 反向代理，使 `https://<LAN_IP>:8443` 转发到 `http://127.0.0.1:8080`。

如果手机无法连接，先检查证书信任、证书地址覆盖、电脑与手机是否同网段，以及 Windows 防火墙是否拦截 TLS 代理端口。不要对 Spring 的 8080 端口创建入站放行规则；确需放行时仅对当前可信网络和 TLS 代理端口创建临时规则，验证后删除。

## 5. 微信开发者工具

1. 从 `<repo-root>` 打开项目，`miniprogramRoot` 应指向 `miniprogram/dist/`。
2. 确认使用与 `WECHAT_APP_ID` 匹配的 AppID。
3. 开发工具可临时关闭业务域名校验，但不得关闭 HTTPS 证书校验来充当验收证据。
4. 确认终端出现 `Device artifact gate passed` 后再选择“真机调试”，不要复用此前指向 `127.0.0.1` 的构建产物。

## 6. 最小验收顺序

1. 登录并完成建档、器械选择和计划确认。
2. 开始训练，执行通用热身和递增热身组。
3. 完成正式组并验证休息计时在前后台切换后按结束时间恢复。
4. 分别执行锁屏、杀进程、弱网、飞行模式、响应丢失和连续点击场景。
5. 恢复网络后确认幂等同步、显式冲突处理和已完成组不丢失。
6. 完成训练，检查历史、趋势、进阶建议和 AI 模板降级总结。

每次执行应在上级产品文档的 `doc/verification/M2_微信真机恢复矩阵.md` 记录设备、系统、微信、基础库版本及结果。没有这些证据时，不得宣称真机门已通过。
