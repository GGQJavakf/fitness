# 小程序上线前配置预检

发布预检只读取小程序项目配置、已验证训练内容及当前进程中的环境变量。它不会输出密钥值，不会连接生产数据，也不会上传或发布小程序。

## 体验环境

在 `miniprogram` 目录执行：

```powershell
npm run preflight:staging
```

体验环境允许使用 `AI_VALIDATED` 内容，但要求内容已启用 `staging-experience`，前后端 AppID 一致，微信身份及数据库环境变量键齐全，并配置：

- `TARO_APP_CLOUDBASE_ENV_ID`：已绑定小程序的云环境 ID；
- `TARO_APP_CLOUDBASE_SERVICE_NAME`：CloudBase Run 服务名。

配置服务名后，小程序通过 `wx.cloud.callContainer` 私有链路访问后端；未配置服务名时保留本机 HTTP 调试方式。动作占位资源会作为警告显示，不阻止内部体验验证。

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
