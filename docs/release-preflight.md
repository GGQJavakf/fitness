# 小程序上线前配置预检

发布预检只读取小程序项目配置、已验证训练内容及当前进程中的环境变量。它不会输出密钥值，不会连接生产数据，也不会上传或发布小程序。

## 统一的本地配置文件

本机 staging 验证统一使用仓库根目录的 `.env.staging-experience.local`。该文件已被
`.gitignore` 排除，包含微信、CloudBase Run、数据库和小程序构建所需的全部环境变量；不要再把
同一组值分散到 PowerShell 用户变量、`backend/.env.local` 或
`miniprogram/config/cloudbase.json.local`。新环境可复制受版本控制的 `.env.example` 后在本地填写：

```powershell
Copy-Item .env.example .env.staging-experience.local
```

`node scripts/verify.mjs --target staging-experience`、小程序发布预检、真机构建预检和 Taro
构建会自动读取该文件。文件中的已配置键优先于当前进程中的同名变量，避免旧终端变量污染结果；
加载器只允许发布白名单键，拒绝 `NODE_OPTIONS` 等进程注入项、重复键、符号链接和超过 64 KiB
的文件。直接 Taro 构建同样以该文件中的 `TARO_APP_*` 为准；仅真机构建预检生成的本次 HTTPS
API 地址可以临时覆盖 `TARO_APP_API_BASE_URL`。后端密钥不会传入 Taro 构建进程。CI 没有该本地
文件时继续使用 CI 环境变量。

模板中的 `FITNESS_TRUST_CLOUDBASE_IDENTITY_HEADERS` 默认是 `false`。只有在控制台或 API
回读确认目标服务仅开放 `MINIAPP` 私有入口后，才可在本机配置中改为 `true`；普通 HTTPS 或
公网入口必须保持关闭。原样复制模板会被预检阻断，这是预期的安全门。

当前在线 AI 默认保持关闭；只有完成用途审批、用户同意、计费资格与模型就绪核验后，才应在本地
配置文件中显式启用相关 AI 键。

所有发布目标都必须显式设置 `SPRING_PROFILES_ACTIVE=staging-experience`。空值、`local`、`test` 或其他未支持 profile 会直接阻断预检。

受版本控制的 `miniprogram/project.config.json` 还必须显式配置 `libVersion`，且不得低于 `3.7.1`；当前项目基线为 `3.15.1`。版本按主、次、补丁三段数值比较。

## 体验环境

在 `miniprogram` 目录执行：

```powershell
npm run preflight:staging
```

体验环境允许使用 `AI_VALIDATED` 内容，但要求内容已启用 `staging-experience`，前后端 AppID 一致，微信身份及数据库环境变量键齐全，并配置：

- `TARO_APP_CLOUDBASE_ENV_ID`：已绑定小程序的云环境 ID；
- `TARO_APP_CLOUDBASE_SERVICE_NAME`：CloudBase Run 服务名。

配置服务名后，小程序通过 `wx.cloud.callContainer` 私有链路访问后端；未配置服务名时保留本机 HTTP 调试方式。动作占位资源会作为警告显示，不阻止内部体验验证。

在线 AI 默认关闭。关闭时不要求配置模型，并使用确定性规则与模板路径。仅当显式设置
`TARO_APP_CLOUDBASE_AI_ENABLED=true` 时，体验和公开发布预检才要求同时满足：

- `TARO_APP_CLOUDBASE_AI_APPROVED=true`：当前 AI 用途与数据边界已批准；
- `TARO_APP_CLOUDBASE_AI_ELIGIBLE=true`：当前云环境计费资格已核验；
- `TARO_APP_CLOUDBASE_AI_MODEL_READY=true`：目标 Group 与模型已通过就绪核验；
- `TARO_APP_CLOUDBASE_AI_PROVIDER_GROUP`：只能为 `cloudbase`、`hunyuan-exp` 或已注册的 `custom-*` Group；
- `FITNESS_TRUST_CLOUDBASE_IDENTITY_HEADERS=true`：仅在已回读确认为 `MINIAPP` 私有入口时设置；普通 HTTPS 或公网入口必须保持关闭；
- `TARO_APP_CLOUDBASE_AI_MODEL`：填写就绪核验确认的模型 ID。

预检只判断这些明确证明是否齐全，不输出其值，也不替代运行时的逐用途用户同意检查。

## 公开发布

在 `miniprogram` 目录执行：

```powershell
npm run preflight:release
```

公开发布要求：

- 动作内容、计划模板和训练规则均为 `PUBLIC_RELEASE_APPROVED`；
- 三类内容均显式启用 `public` 环境；
- 启用动作不再使用占位资源；
- 动作替代关系均已通过公开发布审核；
- 前后端 AppID 一致，微信身份及数据库环境变量键齐全。

退出码 `0` 表示配置门通过，`2` 表示仍有发布阻塞项，`1` 表示参数或文件读取错误。预检只验证可以从仓库和当前环境自动判断的条件，不能替代真机恢复矩阵、完整自动化测试、依赖安全审计、专业内容审核及微信平台提审。

## 仓库统一验证

在仓库根目录执行：

```powershell
node scripts/verify.mjs --target staging-experience
```

该入口依次执行客户端 API 类型漂移检查、类型检查、测试、构建、发布配置预检和后端 `verify`（含覆盖率门槛）。后端 `verify` 在 Docker 可用时还会用隔离的临时 MySQL 启动打包 jar，并验证 `staging-experience`、健康状态、DataSource、Flyway、匿名访问拒绝、鉴权业务路由及共享 consumer sample；没有 Docker 的本机环境会明确跳过这项集成 smoke，CI 必须实际执行。它只验证当前代码与配置，不执行部署、上传、远程迁移或提审。

## 使用受控的外部 MySQL 8 验证

仅当数据库是明确批准的临时验证库时，才允许后端门禁连接外部 MySQL。数据库必须：

- 产品为 MySQL 8；仓库内隔离环境固定使用 MySQL 8.0.44 作为参考基线；
- 库名必须是兼容旧流程的精确 `fitness_m0`，或更安全的独立临时库
  `fitness_verify_<12-32 位小写字母或数字>`。推荐每次新建唯一的 `fitness_verify_*` 空库；
  门禁不会代为创建、删除或清空任何库；
- 远程地址必须是单一 IP 字面量和显式端口，并同时显式开启 `allow-remote`；多主机、
  故障转移、负载均衡、复制 URL 和 DNS 主机名均会被拒绝；
- packaged smoke 只接受同一次 Maven `verify` 中由迁移测试写入的一次性验证标记；标记同时
  绑定规范化 JDBC URL、库名、MySQL `server_uuid` 及 V001-V024 的 Flyway checksum，消费后
  立即删除。因此不能跳过空库迁移，单独把 smoke 指向任意已有库；
- 默认使用 `VERIFY_IDENTITY`。如果现有实例证书没有匹配的 DNS/IP SAN，只能显式开启
  `allow-pinned-ca`，使用数据库管理员或云平台通过可信渠道提供并核验指纹的 CA 证书构建
  PKCS12/JKS 信任库，再使用 `VERIFY_CA`；不得把从当前网络连接抓取的服务器叶子证书直接
  当成信任锚，也不得启用系统信任库回退。

如果验证负责人明确接受“连接已加密，但不校验数据库身份”的风险，可同时显式开启
`allow-remote` 和 `allow-unverified-tls`。此例外模式使用 `sslMode=REQUIRED`，仍要求
`Ssl_cipher` 非空；它与 `allow-pinned-ca` 互斥，且默认关闭。该模式只证明指定 IP 当前可完成
MySQL 兼容性、迁移和 packaged smoke，不提供防中间人或错误主机保证。没有本次明确风险接受时，
发布验证仍应使用 `VERIFY_IDENTITY` 或可信 CA 的 `VERIFY_CA`。

JDBC URL、用户名、数据库密码和信任库配置均通过环境变量注入；密码和信任库密码可改用
单行秘密文件。不得把这些值放入 Maven `-D` 参数、脚本、仓库文件或日志。秘密文件必须
是普通非符号链接文件。推荐使用秘密文件：

```powershell
$env:FITNESS_TEST_MYSQL_PASSWORD_FILE = '<database-password-file>'
$env:FITNESS_TEST_MYSQL_JDBC_URL = 'jdbc:mysql://<approved-ip>:3306/fitness_verify_<unique-lowercase-suffix>'
$env:FITNESS_TEST_MYSQL_USERNAME = '<database-user>'
$env:FITNESS_TEST_MYSQL_TRUST_STORE = '<mysql-ca-truststore.p12>'
$env:FITNESS_TEST_MYSQL_TRUST_STORE_TYPE = 'PKCS12'
$env:FITNESS_TEST_MYSQL_TRUST_STORE_PASSWORD_FILE = '<truststore-password-file>'

$env:FITNESS_SMOKE_MYSQL_PASSWORD_FILE = $env:FITNESS_TEST_MYSQL_PASSWORD_FILE
$env:FITNESS_SMOKE_MYSQL_JDBC_URL = $env:FITNESS_TEST_MYSQL_JDBC_URL
$env:FITNESS_SMOKE_MYSQL_USERNAME = $env:FITNESS_TEST_MYSQL_USERNAME
$env:FITNESS_SMOKE_MYSQL_TRUST_STORE = $env:FITNESS_TEST_MYSQL_TRUST_STORE
$env:FITNESS_SMOKE_MYSQL_TRUST_STORE_TYPE = $env:FITNESS_TEST_MYSQL_TRUST_STORE_TYPE
$env:FITNESS_SMOKE_MYSQL_TRUST_STORE_PASSWORD_FILE = $env:FITNESS_TEST_MYSQL_TRUST_STORE_PASSWORD_FILE

Set-Location backend
.\mvnw.cmd -q verify `
  '-Dfitness.test.mysql.allow-remote=true' `
  '-Dfitness.test.mysql.allow-pinned-ca=true' `
  '-Dfitness.smoke.mysql.allow-remote=true' `
  '-Dfitness.smoke.mysql.allow-pinned-ca=true'
```

负责人明确接受身份不校验风险时，将上面两个 `allow-pinned-ca` 参数及全部信任库变量移除，
改为：

```powershell
.\mvnw.cmd -q verify `
  '-Dfitness.test.mysql.allow-remote=true' `
  '-Dfitness.test.mysql.allow-unverified-tls=true' `
  '-Dfitness.smoke.mysql.allow-remote=true' `
  '-Dfitness.smoke.mysql.allow-unverified-tls=true'
```

执行成功必须同时满足迁移测试零跳过、packaged smoke 零跳过以及远程会话
`Ssl_cipher` 非空。失败日志会脱敏 JDBC URL、用户名、数据库密码、信任库位置和信任库密码。
运行结束后清除上述进程环境变量，并只按本次预先记录的精确临时库名回收该库；不得使用通配符，
也不得把已有体验库或生产库作为临时验证库。本门禁不会自动执行破坏性清理。
